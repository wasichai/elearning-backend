// el servidor del trabajador con un renderizador simulado (escribe unos bytes en lugar de llamar a Remotion) y un ffprobe
// simulado: autenticación, validación (solo datos, nunca código), idempotencia del PUT, concurrencia, descarga y borrado
import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { after, beforeEach, describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createWorker } from '../src/server.mjs'

const TOKEN = 'a-test-token-that-is-long-enough-1234'
const EXAMPLES = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'schema', 'ejemplos')
const example = (kind, name) => JSON.parse(readFileSync(path.join(EXAMPLES, kind, name), 'utf8'))
const MINIMO = example('validos', 'minimo.json')
const PNG = readFileSync(path.join(EXAMPLES, 'recursos', 'azul.png'))
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex')

// un renderizador que tarda delayMs, cuenta sus llamadas y puede fallar
function fakeRenderer() {
  const fake = {
    calls: 0,
    delayMs: 0,
    fail: null,
    async render({ guion, output }) {
      fake.calls += 1
      await new Promise((resolve) => setTimeout(resolve, fake.delayMs))
      if (fake.fail) throw new Error(fake.fail)
      await writeFile(output, `fake mp4 of ${guion.titulo}`)
    }
  }
  return fake
}

const dirs = []
async function start(options = {}) {
  const workDir = mkdtempSync(path.join(os.tmpdir(), 'render-worker-test-'))
  dirs.push(workDir)
  const renderer = fakeRenderer()
  let clock = 0
  const worker = createWorker({
    token: TOKEN,
    renderer,
    probe: async () => ({ format: { format_name: 'mov,mp4,m4a,3gp,3g2,mj2' } }),
    workDir,
    versions: { trabajador: 'test', remotion: '4.0.540' },
    now: () => clock,
    ...options
  })
  await new Promise((resolve) => worker.server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${worker.server.address().port}`
  const call = (method, p, body, token = TOKEN) =>
    fetch(base + p, {
      method,
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body)
    })
  servers.push(worker.server)
  return { worker, renderer, workDir, call, tick: (ms) => (clock += ms) }
}

const servers = []
after(() => {
  servers.forEach((server) => server.close())
  dirs.forEach((dir) => rmSync(dir, { recursive: true, force: true }))
})

describe('the render worker', () => {
  let w
  beforeEach(async () => {
    w = await start()
  })

  it('refuses to start without a long enough token', () => {
    assert.throws(() => createWorker({ token: 'short', renderer: fakeRenderer(), probe: async () => ({}), workDir: os.tmpdir() }), /at least 32/)
  })

  it('answers health without a token and anything else only with the right one', async () => {
    assert.equal((await w.call('GET', '/health', undefined, null)).status, 200)
    const id = randomUUID()
    assert.equal((await w.call('PUT', `/renders/${id}`, { guion: MINIMO }, null)).status, 401)
    assert.equal((await w.call('PUT', `/renders/${id}`, { guion: MINIMO }, 'a-wrong-token-that-is-long-enough-12345')).status, 401)
    assert.equal(w.renderer.calls, 0)
  })

  it('renders, reports and serves the video and its subtitles', async () => {
    const id = randomUUID()
    const res = await w.call('PUT', `/renders/${id}`, { guion: example('validos', 'completo.json'), recursos: { [sha256(PNG)]: PNG.toString('base64') } })
    assert.equal(res.status, 200, await res.clone().text())
    const report = await res.json()
    assert.equal(report.trabajo, id)
    assert.equal(report.duracionSegundos, 22)
    assert.deepEqual(report.salida, { resolucion: '1280x720', fps: 25 })
    assert.deepEqual(report.versiones, { trabajador: 'test', remotion: '4.0.540' })
    assert.equal(report.ffprobe.format.format_name, 'mov,mp4,m4a,3gp,3g2,mj2')

    const video = Buffer.from(await (await w.call('GET', `/renders/${id}/video.mp4`)).arrayBuffer())
    assert.equal(sha256(video), report.video.sha256)
    assert.equal(video.length, report.video.bytes)
    const vtt = await w.call('GET', `/renders/${id}/subtitulos.vtt`)
    assert.equal(vtt.headers.get('content-type'), 'text/vtt; charset=utf-8')
    const text = await vtt.text()
    assert.ok(text.startsWith('WEBVTT\n\n00:00:00.000 --> 00:00:03.500\n'), text)
    assert.equal(sha256(Buffer.from(text)), report.subtitulos.sha256)
  })

  it('is idempotent by job id: the same request again returns the same report without rendering again', async () => {
    const id = randomUUID()
    w.renderer.delayMs = 100
    const [first, second] = await Promise.all([w.call('PUT', `/renders/${id}`, { guion: MINIMO }), w.call('PUT', `/renders/${id}`, { guion: MINIMO })])
    const third = await w.call('PUT', `/renders/${id}`, { guion: MINIMO })
    const reports = await Promise.all([first, second, third].map((r) => r.json()))
    assert.deepEqual([first.status, second.status, third.status], [200, 200, 200])
    assert.deepEqual(reports[1], reports[0])
    assert.deepEqual(reports[2], reports[0])
    assert.equal(w.renderer.calls, 1)
  })

  it('refuses the same job id with another script', async () => {
    const id = randomUUID()
    assert.equal((await w.call('PUT', `/renders/${id}`, { guion: MINIMO })).status, 200)
    const other = await w.call('PUT', `/renders/${id}`, { guion: { ...MINIMO, titulo: 'Otro' } })
    assert.equal(other.status, 409)
  })

  // ADR-0039 §5 y criterio de #267: solo datos, nunca código. Nada de esto llega al renderizador
  for (const name of ['codigo-componente.json', 'diapositiva-html.json', 'diapositiva-con-expresion.json', 'imagen-url.json', 'fuente-descargada.json']) {
    it(`rejects a request with code, HTML or a URL: ${name}`, async () => {
      const res = await w.call('PUT', `/renders/${randomUUID()}`, { guion: example('invalidos', name) })
      assert.equal(res.status, 400)
      const body = await res.json()
      assert.equal(body.error, 'the request does not follow the script schema')
      assert.ok(body.issues.length > 0)
      assert.equal(w.renderer.calls, 0)
    })
  }

  it('rejects code next to the script, in the request itself', async () => {
    const res = await w.call('PUT', `/renders/${randomUUID()}`, { guion: MINIMO, composicion: 'export default () => null', entryPoint: '/etc/passwd' })
    assert.equal(res.status, 400)
    assert.equal(w.renderer.calls, 0)
  })

  for (const name of [
    'regla-subtitulo-fuera-de-su-seccion.json',
    'regla-subtitulo-al-reves.json',
    'regla-ids-repetidos.json',
    'regla-subtitulos-solapados.json'
  ]) {
    it(`rejects a script that breaks a rule: ${name}`, async () => {
      assert.equal((await w.call('PUT', `/renders/${randomUUID()}`, { guion: example('invalidos', name) })).status, 400)
    })
  }

  it('rejects images that are missing, not images or not what their hash says', async () => {
    const guion = example('validos', 'completo.json')
    const html = Buffer.from('<html><script>alert(1)</script></html>')
    const svg = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')
    for (const recursos of [
      {},
      { [sha256(html)]: html.toString('base64') },
      { [sha256(svg)]: svg.toString('base64') },
      { [sha256(html)]: PNG.toString('base64') }
    ]) {
      assert.equal((await w.call('PUT', `/renders/${randomUUID()}`, { guion, recursos })).status, 400, JSON.stringify(Object.keys(recursos)))
    }
    assert.equal(w.renderer.calls, 0)
  })

  it('rejects what is not JSON and a video longer than allowed', async () => {
    assert.equal((await w.call('PUT', `/renders/${randomUUID()}`, 'export default () => null')).status, 400)
    const long = { ...MINIMO, secciones: [1, 2].map((n) => ({ ...MINIMO.secciones[0], id: `s${n}`, segundos: 500 })) }
    const res = await w.call('PUT', `/renders/${randomUUID()}`, { guion: long })
    assert.equal(res.status, 400)
    assert.match(JSON.stringify(await res.json()), /more than the 900 s allowed/)
  })

  it('rejects a request larger than its limit', async () => {
    const small = await start({ maxBodyBytes: 1024 })
    const res = await small.call('PUT', `/renders/${randomUUID()}`, { guion: { ...MINIMO, titulo: 'x'.repeat(2000) } })
    assert.equal(res.status, 413)
  })

  it('answers 503 when it is already rendering as many videos as it may', async () => {
    w.renderer.delayMs = 200
    const first = w.call('PUT', `/renders/${randomUUID()}`, { guion: MINIMO })
    await new Promise((resolve) => setTimeout(resolve, 50))
    const busy = await w.call('PUT', `/renders/${randomUUID()}`, { guion: MINIMO })
    assert.equal(busy.status, 503)
    assert.equal(busy.headers.get('retry-after'), '30')
    assert.equal((await first).status, 200)
  })

  it('answers 422 when the render fails, and a new request tries again', async () => {
    const id = randomUUID()
    w.renderer.fail = 'Chrome crashed'
    const failed = await w.call('PUT', `/renders/${id}`, { guion: MINIMO })
    assert.equal(failed.status, 422)
    assert.match((await failed.json()).error, /could not be rendered: Chrome crashed/)
    w.renderer.fail = null
    assert.equal((await w.call('PUT', `/renders/${id}`, { guion: MINIMO })).status, 200)
    assert.equal(w.renderer.calls, 2)
  })

  it('answers 422 when the video is larger than allowed', async () => {
    const tiny = await start({ maxBytes: 4 })
    const res = await tiny.call('PUT', `/renders/${randomUUID()}`, { guion: MINIMO })
    assert.equal(res.status, 422)
    assert.match((await res.json()).error, /more than 4/)
  })

  it('deletes what the backend collected, and does not serve it again', async () => {
    const id = randomUUID()
    await w.call('PUT', `/renders/${id}`, { guion: MINIMO })
    assert.ok(existsSync(path.join(w.workDir, id, 'video.mp4')))
    assert.equal((await w.call('DELETE', `/renders/${id}`)).status, 204)
    assert.equal((await w.call('DELETE', `/renders/${id}`)).status, 204, 'borrar otra vez es inocuo')
    assert.ok(!existsSync(path.join(w.workDir, id)))
    assert.equal((await w.call('GET', `/renders/${id}/video.mp4`)).status, 404)
  })

  it('removes on its own what nobody collected for an hour', async () => {
    const id = randomUUID()
    await w.call('PUT', `/renders/${id}`, { guion: MINIMO })
    w.tick(59 * 60 * 1000)
    await w.worker.sweep()
    assert.ok(existsSync(path.join(w.workDir, id)))
    w.tick(2 * 60 * 1000)
    await w.worker.sweep()
    assert.ok(!existsSync(path.join(w.workDir, id)))
    assert.equal((await w.call('GET', `/renders/${id}/subtitulos.vtt`)).status, 404)
  })

  it('only knows job ids, not paths', async () => {
    assert.equal((await w.call('PUT', '/renders/..%2F..%2Fetc', { guion: MINIMO })).status, 404)
    assert.equal((await w.call('GET', `/renders/${randomUUID()}/../../package.json`)).status, 404)
  })
})
