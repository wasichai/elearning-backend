// el guion (RF-MED-01) y la petición de render (ADR-0040 §2), validados con zod. Es la misma forma que
// schema/guion.schema.json: test/schema.test.mjs comprueba con los ejemplos compartidos que zod y el esquema dicen lo mismo.
// Todo objeto es estricto (un campo de más es un error): ningún campo es un componente, una expresión, una ruta, una URL ni
// HTML. Las reglas que el esquema no expresa van en rules(), y el backend (elearning.media.video.ScriptSchema) las repite
import { createHash } from 'node:crypto'
import { z } from 'zod'

const SHA256 = /^[0-9a-f]{64}$/
const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/

const titulo = z.string().min(1).max(200)
const linea = z.string().min(1).max(300)

const portada = z.strictObject({ tipo: z.literal('portada'), titulo, subtitulo: linea.optional() })
const vinetas = z.strictObject({ tipo: z.literal('vinetas'), titulo, vinetas: z.array(linea).min(1).max(8) })
const imagen = z.strictObject({ tipo: z.literal('imagen'), imagen: z.string().regex(SHA256), alt: linea, pie: linea.optional() })
const codigo = z.strictObject({
  tipo: z.literal('codigo'),
  titulo: titulo.optional(),
  lenguaje: z
    .string()
    .regex(/^[a-z0-9+#-]{1,32}$/)
    .optional(),
  codigo: z.string().min(1).max(4000)
})
const cierre = z.strictObject({ tipo: z.literal('cierre'), titulo, mensaje: linea.optional() })

const subtitulo = z.strictObject({ desde: z.number().min(0).max(600), hasta: z.number().min(0).max(600), texto: z.string().min(1).max(500) })
const traza = z.strictObject({ doc: z.string().min(1).max(200), ancla: z.string().max(200).optional() })

const seccion = z.strictObject({
  id: z.string().regex(/^[a-z0-9][a-z0-9-]{0,63}$/),
  titulo,
  segundos: z.number().int().min(1).max(600),
  diapositiva: z.discriminatedUnion('tipo', [portada, vinetas, imagen, codigo, cierre]),
  narracion: z.string().max(10000).optional(),
  subtitulos: z.array(subtitulo).max(200).optional(),
  trazas: z.array(traza).max(20).optional()
})

export const RESOLUCIONES = ['1280x720', '1920x1080']
export const FPS = [25, 30]
export const TIPOGRAFIAS = ['inter', 'source-serif-4']

export const guionSchema = z.strictObject({
  version: z.literal(1),
  idioma: z.string().regex(/^[a-z]{2}(-[A-Z]{2})?$/),
  titulo,
  objetivos: z.array(linea).max(20).optional(),
  tipografia: z.enum(TIPOGRAFIAS).optional(),
  salida: z.strictObject({ resolucion: z.enum(RESOLUCIONES).optional(), fps: z.union(FPS.map((fps) => z.literal(fps))).optional() }).optional(),
  secciones: z.array(seccion).min(1).max(100)
})

// lo que manda el backend en PUT /renders/{trabajo}: el guion y las imágenes que nombran sus diapositivas, por sha256
export const peticionSchema = z.strictObject({
  guion: guionSchema,
  recursos: z.record(z.string().regex(SHA256), z.string().regex(BASE64)).optional()
})

// 20 MB de imágenes como mucho por petición (ADR-0040 §2)
export const MAX_RECURSOS_BYTES = 20 * 1024 * 1024

// solo PNG, JPEG o WebP, por sus primeros bytes (los mismos que detecta Wasichai)
export function imageType(bytes) {
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png'
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg'
  if (bytes.length >= 12 && bytes.toString('latin1', 0, 4) === 'RIFF' && bytes.toString('latin1', 8, 12) === 'WEBP') return 'image/webp'
  return null
}

// las reglas del guion que JSON Schema no expresa: ids únicos; cada subtítulo dentro de su sección, con desde < hasta y sin
// pisar al anterior; la duración total dentro de maxSeconds. Devuelve los problemas como { path, message }
export function rules(guion, { maxSeconds } = {}) {
  const issues = []
  const seen = new Set()
  guion.secciones.forEach((s, i) => {
    if (seen.has(s.id)) issues.push({ path: `secciones.${i}.id`, message: `the section id ${s.id} is repeated` })
    seen.add(s.id)
    let previous = 0
    ;(s.subtitulos ?? []).forEach((sub, j) => {
      const path = `secciones.${i}.subtitulos.${j}`
      if (sub.hasta <= sub.desde) issues.push({ path, message: 'a subtitle must end after it starts' })
      if (sub.hasta > s.segundos) issues.push({ path, message: `a subtitle must end within its section (${s.segundos} s)` })
      if (sub.desde < previous) issues.push({ path, message: 'subtitles must be in order and must not overlap' })
      previous = Math.max(previous, sub.hasta)
    })
  })
  const total = duration(guion)
  if (maxSeconds != null && total > maxSeconds) issues.push({ path: 'secciones', message: `the video lasts ${total} s, more than the ${maxSeconds} s allowed` })
  return issues
}

export function duration(guion) {
  return guion.secciones.reduce((sum, s) => sum + s.segundos, 0)
}

// valida una petición ya leída como JSON. { value } con el guion normalizado (los valores por defecto puestos) y las imágenes
// decodificadas, o { issues } si no vale
export function parsePeticion(body, { maxSeconds } = {}) {
  const parsed = peticionSchema.safeParse(body)
  if (!parsed.success) return { issues: parsed.error.issues.map((issue) => ({ path: issue.path.join('.'), message: issue.message })) }
  const { guion } = parsed.data
  const issues = rules(guion, { maxSeconds })
  const recursos = {}
  let total = 0
  for (const [sha, base64] of Object.entries(parsed.data.recursos ?? {})) {
    const bytes = Buffer.from(base64, 'base64')
    total += bytes.length
    const type = imageType(bytes)
    if (type == null) issues.push({ path: `recursos.${sha}`, message: 'only PNG, JPEG or WebP images are accepted' })
    else if (createHash('sha256').update(bytes).digest('hex') !== sha) issues.push({ path: `recursos.${sha}`, message: 'the image does not match its sha256' })
    else recursos[sha] = { type, base64 }
  }
  if (total > MAX_RECURSOS_BYTES) issues.push({ path: 'recursos', message: `the images take ${total} bytes, more than ${MAX_RECURSOS_BYTES}` })
  guion.secciones.forEach((s, i) => {
    if (s.diapositiva.tipo === 'imagen' && !(s.diapositiva.imagen in (parsed.data.recursos ?? {}))) {
      issues.push({ path: `secciones.${i}.diapositiva.imagen`, message: 'the image is not among the resources of the request' })
    }
  })
  if (issues.length > 0) return { issues }
  return { value: { guion: normalize(guion), recursos } }
}

export function normalize(guion) {
  return {
    ...guion,
    tipografia: guion.tipografia ?? 'inter',
    salida: { resolucion: guion.salida?.resolucion ?? '1280x720', fps: guion.salida?.fps ?? 30 }
  }
}
