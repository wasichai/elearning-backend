// el servidor HTTP interno del trabajador (ADR-0040 §3), con node:http y sin framework. Solo lo llama el backend, con
// «Authorization: Bearer <RENDER_WORKER_TOKEN>»:
//
//   PUT    /renders/{trabajo}                 el guion y sus imágenes; responde al terminar con el informe
//   GET    /renders/{trabajo}/video.mp4       los bytes del vídeo
//   GET    /renders/{trabajo}/subtitulos.vtt  los subtítulos
//   DELETE /renders/{trabajo}                 el backend ya los guardó: se borran
//   GET    /health                            sin token: el trabajador está vivo
//
// PUT es idempotente por id de trabajo: hecho, devuelve el mismo informe sin renderizar otra vez; en curso, espera al mismo
// render. 400 (no cumple el esquema), 409 (el mismo id con otro guion), 413 (demasiado grande) y 422 (render imposible) son
// permanentes; 503 (lleno), pasajero; 415, un cuerpo que no se declara JSON. Lo que lleva más de una hora sin recoger se
// borra solo. El secreto no se escribe nunca en el registro: solo se compara su hash
import { createHash, timingSafeEqual } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { mkdir, rm, stat, writeFile } from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import { duration, parsePeticion } from './guion.mjs'
import { buildVtt } from './vtt.mjs'

const ID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
const RENDER = new RegExp(`^/renders/(${ID})$`)
const FILE = new RegExp(`^/renders/(${ID})/(video\\.mp4|subtitulos\\.vtt)$`)
const TYPES = { 'video.mp4': 'video/mp4', 'subtitulos.vtt': 'text/vtt; charset=utf-8' }

// la petición más grande: 20 MB de imágenes en base64 (un tercio más) y el guion
export const MAX_BODY_BYTES = 32 * 1024 * 1024

class HttpError extends Error {
  constructor(status, message, issues) {
    super(message)
    this.status = status
    this.issues = issues
  }
}

// renderer.render({ guion, recursos, output, seconds }) escribe el MP4 en output; probe(file) devuelve la salida de ffprobe
export function createWorker({
  token,
  renderer,
  probe,
  workDir,
  maxConcurrent = 1,
  maxSeconds = 900,
  maxBytes = 256 * 1024 * 1024,
  maxBodyBytes = MAX_BODY_BYTES,
  ttlMs = 60 * 60 * 1000,
  versions = {},
  now = () => Date.now(),
  log = () => {}
}) {
  if (!token || token.length < 32) throw new Error('RENDER_WORKER_TOKEN must be at least 32 characters')
  const expected = createHash('sha256').update(`Bearer ${token}`).digest()
  // id -> { digest, state: 'running' | 'done', promise, report, finishedAt }
  const renders = new Map()
  const running = () => [...renders.values()].filter((r) => r.state === 'running').length

  const authorized = (req) =>
    timingSafeEqual(
      createHash('sha256')
        .update(req.headers.authorization ?? '')
        .digest(),
      expected
    )

  async function put(id, req) {
    // solo JSON (revisión de seguridad de la F5, #302): ni formularios, ni texto, ni multipart, aunque su cuerpo se lea como JSON
    if (!isJson(req.headers['content-type'])) throw new HttpError(415, 'the request must be application/json')
    const body = await readJson(req, maxBodyBytes)
    const parsed = parsePeticion(body, { maxSeconds })
    if (parsed.issues) throw new HttpError(400, 'the request does not follow the script schema', parsed.issues)
    const digest = createHash('sha256').update(JSON.stringify(body)).digest('hex')
    const existing = renders.get(id)
    if (existing) {
      if (existing.digest !== digest) throw new HttpError(409, `render ${id} was asked for with another script`)
      return existing.state === 'done' ? existing.report : existing.promise
    }
    if (running() >= maxConcurrent) throw new HttpError(503, `already rendering ${maxConcurrent} videos: try again later`)
    const entry = { digest, state: 'running' }
    entry.promise = render(id, parsed.value).then(
      (report) => {
        Object.assign(entry, { state: 'done', report, finishedAt: now() })
        return report
      },
      (error) => {
        renders.delete(id)
        rm(dirOf(id), { recursive: true, force: true }).catch(() => {})
        throw error instanceof HttpError ? error : new HttpError(422, `the video could not be rendered: ${error.message}`)
      }
    )
    renders.set(id, entry)
    return entry.promise
  }

  async function render(id, { guion, recursos }) {
    const dir = dirOf(id)
    await rm(dir, { recursive: true, force: true })
    await mkdir(dir, { recursive: true })
    const video = path.join(dir, 'video.mp4')
    const subtitles = path.join(dir, 'subtitulos.vtt')
    const seconds = duration(guion)
    const started = now()
    await renderer.render({ guion, recursos, output: video, seconds })
    await writeFile(subtitles, buildVtt(guion))
    const videoFile = await describe(video)
    if (videoFile.bytes > maxBytes) throw new HttpError(422, `the video takes ${videoFile.bytes} bytes, more than ${maxBytes}`)
    const report = {
      trabajo: id,
      video: videoFile,
      subtitulos: await describe(subtitles),
      duracionSegundos: seconds,
      salida: guion.salida,
      ffprobe: await probe(video),
      versiones: versions,
      milisegundos: now() - started
    }
    log(`render ${id}: ${seconds} s in ${report.milisegundos} ms`)
    return report
  }

  const dirOf = (id) => path.join(workDir, id)

  async function download(id, name, res) {
    const entry = renders.get(id)
    if (!entry || entry.state !== 'done') throw new HttpError(404, `render ${id} is not finished`)
    const file = path.join(dirOf(id), name)
    const { size } = await stat(file)
    res.writeHead(200, { 'content-type': TYPES[name], 'content-length': size })
    createReadStream(file).pipe(res)
  }

  async function remove(id) {
    const entry = renders.get(id)
    if (entry?.state === 'running') throw new HttpError(409, `render ${id} is still running`)
    renders.delete(id)
    await rm(dirOf(id), { recursive: true, force: true })
  }

  async function sweep() {
    const before = now() - ttlMs
    for (const [id, entry] of renders) {
      if (entry.state === 'done' && entry.finishedAt < before) {
        log(`render ${id} was never collected: removed`)
        await remove(id)
      }
    }
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://worker')
    if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true, rendering: running() })
    if (!authorized(req)) throw new HttpError(401, 'missing or wrong bearer token')
    const render = RENDER.exec(url.pathname)
    const file = FILE.exec(url.pathname)
    if (render && req.method === 'PUT') return send(res, 200, await put(render[1], req))
    if (render && req.method === 'DELETE') {
      await remove(render[1])
      res.writeHead(204).end()
      return
    }
    if (file && req.method === 'GET') return download(file[1], file[2], res)
    throw new HttpError(404, 'not found')
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch((error) => {
      const status = error instanceof HttpError ? error.status : 500
      if (status === 500) log(`unexpected error: ${error.stack}`)
      if (res.headersSent) return res.destroy()
      const headers = status === 503 ? { 'retry-after': '30' } : {}
      if (status === 413) headers.connection = 'close'
      send(res, status, { error: status === 500 ? 'internal error' : error.message, ...(error.issues ? { issues: error.issues } : {}) }, headers)
    })
  })
  // un render largo no corta la conexión del PUT; el tiempo límite lo pone el backend (lms.render.timeout)
  server.requestTimeout = 0
  server.headersTimeout = 60_000
  return { server, sweep, renders }
}

function send(res, status, body, headers = {}) {
  const text = JSON.stringify(body)
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(text), ...headers })
  res.end(text)
}

async function describe(file) {
  const hash = createHash('sha256')
  let bytes = 0
  for await (const chunk of createReadStream(file)) {
    hash.update(chunk)
    bytes += chunk.length
  }
  return { archivo: path.basename(file), sha256: hash.digest('hex'), bytes }
}

// application/json, con o sin parámetros (charset)
function isJson(contentType) {
  return (contentType ?? '').split(';')[0].trim().toLowerCase() === 'application/json'
}

// el cuerpo entero en memoria, con tope: uno declarado mayor se rechaza sin leerlo; uno sin longitud que se pasa se lee
// hasta el final sin guardarlo y se rechaza
function readJson(req, max) {
  return new Promise((resolve, reject) => {
    const tooLarge = () => new HttpError(413, `the request is larger than ${max} bytes`)
    if (Number(req.headers['content-length']) > max) return reject(tooLarge())
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size <= max) chunks.push(chunk)
    })
    req.on('error', reject)
    req.on('end', () => {
      if (size > max) return reject(tooLarge())
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      } catch {
        reject(new HttpError(400, 'the request is not JSON'))
      }
    })
  })
}
