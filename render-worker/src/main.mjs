// arranque del trabajador. Configuración solo por entorno:
//   RENDER_WORKER_TOKEN           obligatorio, 32 caracteres o más: el secreto compartido con el backend (LMS_RENDER_WORKER_TOKEN)
//   PORT, HOST                    8095 y 0.0.0.0: escucha solo en la red interna del despliegue, sin salida a Internet
//   RENDER_WORKER_DIR             dónde quedan los vídeos hasta que el backend los recoge (por defecto, el temporal del sistema)
//   RENDER_WORKER_MAX_CONCURRENT  renders a la vez (1); con más, 503
//   RENDER_WORKER_MAX_SECONDS     duración máxima de un vídeo (900, como lms.render.max-seconds)
//   RENDER_WORKER_MAX_BYTES       tamaño máximo del MP4 (268435456, como lms.render.max-bytes)
//   RENDER_WORKER_BROWSER         opcional: el Chrome que usa Remotion; sin él, el Chrome Headless Shell que descargó la imagen
//   FFPROBE                       opcional: otro ffprobe que el de Remotion
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import { createProbe } from './ffprobe.mjs'
import { createRemotionRenderer } from './renderer.mjs'
import { takeSecret } from './secret.mjs'
import { createWorker } from './server.mjs'

const require = createRequire(import.meta.url)
const env = process.env
// lo primero: fuera de process.env antes de lanzar nada, ningún proceso hijo lo hereda (#369)
const token = takeSecret('RENDER_WORKER_TOKEN')
const int = (name, fallback) => {
  const value = env[name] ? Number(env[name]) : fallback
  if (!Number.isInteger(value) || value <= 0) throw new Error(`${name} must be a positive integer`)
  return value
}

const maxBytes = int('RENDER_WORKER_MAX_BYTES', 256 * 1024 * 1024)
const log = (message) => console.log(`${new Date().toISOString()} ${message}`)
const { server, sweep } = createWorker({
  token,
  renderer: createRemotionRenderer({ browserExecutable: env.RENDER_WORKER_BROWSER || null, maxBytes }),
  probe: createProbe(),
  workDir: env.RENDER_WORKER_DIR || path.join(os.tmpdir(), 'render-worker'),
  maxConcurrent: int('RENDER_WORKER_MAX_CONCURRENT', 1),
  maxSeconds: int('RENDER_WORKER_MAX_SECONDS', 900),
  maxBytes,
  versions: { trabajador: require('../package.json').version, remotion: require('remotion/package.json').version },
  log
})

setInterval(() => sweep().catch((error) => log(`sweep failed: ${error.message}`)), 5 * 60 * 1000).unref()
const port = int('PORT', 8095)
server.listen(port, env.HOST || '0.0.0.0', () => log(`render worker listening on ${port}`))
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => server.close(() => process.exit(0)))
