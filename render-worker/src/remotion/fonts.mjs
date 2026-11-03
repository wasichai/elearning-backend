// las fuentes OFL del paquete (fonts/, servidas como carpeta pública del bundle): nunca se descargan al renderizar
import { loadFont } from '@remotion/fonts'
import { staticFile } from 'remotion'

const FONTS = [
  ['Inter', 'inter-latin-400-normal.woff2', '400'],
  ['Inter', 'inter-latin-700-normal.woff2', '700'],
  ['Source Serif 4', 'source-serif-4-latin-400-normal.woff2', '400'],
  ['Source Serif 4', 'source-serif-4-latin-700-normal.woff2', '700'],
  ['JetBrains Mono', 'jetbrains-mono-latin-400-normal.woff2', '400']
]

for (const [family, file, weight] of FONTS) loadFont({ family, url: staticFile(file), weight })
