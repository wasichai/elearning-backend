// el renderizador de verdad: Remotion (ADR-0039, ADR-0040). Empaqueta una vez las composiciones del proyecto
// (src/remotion/, con fonts/ como carpeta pública) y renderiza cada guion a H.264. Sin red: todo lo que usa la composición
// viene en las props o en el paquete. No se pasa clave de licencia: el titular usa la Free License (ADR-0039 §2) y cada
// despliegue responde de la suya
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { bundle } from '@remotion/bundler'
import { renderMedia, selectComposition } from '@remotion/renderer'

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const COMPOSITION = 'guion'

// la tasa de bits de vídeo: la de una diapositiva a 720p o 1080p, rebajada si hace falta para que la duración pedida quepa
// en maxBytes (con un 10 % de margen para el contenedor)
export function videoBitrate(seconds, maxBytes, resolucion) {
  const wanted = resolucion === '1920x1080' ? 2_000_000 : 1_200_000
  const fits = Math.floor((maxBytes * 8 * 0.9) / Math.max(seconds, 1))
  return `${Math.floor(Math.max(Math.min(wanted, fits), 100_000) / 1000)}k`
}

export function createRemotionRenderer({ browserExecutable = null, maxBytes, log = () => {} } = {}) {
  let serveUrl = null
  const bundled = () => {
    serveUrl ??= bundle({ entryPoint: path.join(ROOT, 'src', 'remotion', 'index.mjs'), publicDir: path.join(ROOT, 'fonts') })
    return serveUrl
  }
  return {
    async render({ guion, recursos, output, seconds }) {
      const url = await bundled()
      const inputProps = { guion, recursos }
      const composition = await selectComposition({ serveUrl: url, id: COMPOSITION, inputProps, browserExecutable, logLevel: 'error' })
      await renderMedia({
        composition,
        serveUrl: url,
        codec: 'h264',
        outputLocation: output,
        inputProps,
        browserExecutable,
        muted: true,
        videoBitrate: videoBitrate(seconds, maxBytes, guion.salida.resolucion),
        logLevel: 'error',
        onProgress: ({ progress }) => log(progress)
      })
    }
  }
}
