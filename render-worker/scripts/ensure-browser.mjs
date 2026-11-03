// descarga el Chrome Headless Shell que usa Remotion: lo ejecuta el Dockerfile al construir la imagen, nunca al renderizar
import { ensureBrowser } from '@remotion/renderer'

await ensureBrowser()
console.log('Chrome Headless Shell ready')
