// ffprobe sobre el MP4 renderizado (ADR-0040 §5): su salida va en el informe y el backend la compara con el guion. Por
// defecto, el ffprobe que trae Remotion en @remotion/compositor-<plataforma> (su compilación y licencia, en
// docs/legal/licensing.md); FFPROBE nombra otro. Se ejecuta con execFile, sin shell, con la ruta como argumento y con un
// entorno mínimo
import { execFile } from 'node:child_process'
import { createRequire } from 'node:module'
import path from 'node:path'

const require = createRequire(import.meta.url)

// con un entorno mínimo, no el del trabajador (#369): PATH y, para el de Remotion, la carpeta de sus bibliotecas
const minimal = (extra = {}) => ({ ...(process.env.PATH ? { PATH: process.env.PATH } : {}), ...extra })

export function defaultFfprobe() {
  if (process.env.FFPROBE) return { command: process.env.FFPROBE, env: minimal() }
  const libc = process.platform === 'linux' ? '-gnu' : process.platform === 'win32' ? '-msvc' : ''
  const dir = path.dirname(require.resolve(`@remotion/compositor-${process.platform}-${process.arch}${libc}/package.json`))
  return { command: path.join(dir, process.platform === 'win32' ? 'ffprobe.exe' : 'ffprobe'), env: minimal({ LD_LIBRARY_PATH: dir }) }
}

export function createProbe(ffprobe = defaultFfprobe()) {
  return (file) =>
    new Promise((resolve, reject) => {
      execFile(
        ffprobe.command,
        ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', file],
        { env: ffprobe.env, maxBuffer: 1024 * 1024, timeout: 60_000 },
        (error, stdout, stderr) => {
          if (error) reject(new Error(`ffprobe failed: ${(stderr || error.message).toString().trim().slice(0, 500)}`))
          else resolve(JSON.parse(stdout))
        }
      )
    })
}
