// las diapositivas del proyecto: una lista cerrada, elegida por el tipo del guion. Todo texto va como nodo de texto de React,
// que lo escapa; nunca dangerouslySetInnerHTML, nunca un componente o un estilo que venga del guion (ADR-0040 §2)
import { createElement as h } from 'react'
import { AbsoluteFill, Img } from 'remotion'

const FAMILIES = { inter: 'Inter', 'source-serif-4': 'Source Serif 4' }
const MONO = 'JetBrains Mono'

const page = (font) => ({
  backgroundColor: '#ffffff',
  color: '#1b1f24',
  fontFamily: `'${FAMILIES[font] ?? FAMILIES.inter}', sans-serif`,
  padding: '6%',
  justifyContent: 'center'
})

const heading = { fontSize: 64, fontWeight: 700, margin: 0, lineHeight: 1.15 }
const body = { fontSize: 40, lineHeight: 1.4 }

const KINDS = {
  portada: (d) => [
    h('h1', { key: 't', style: { ...heading, fontSize: 80 } }, d.titulo),
    d.subtitulo && h('p', { key: 's', style: { ...body, opacity: 0.7 } }, d.subtitulo)
  ],
  vinetas: (d) => [
    h('h2', { key: 't', style: heading }, d.titulo),
    h(
      'ul',
      { key: 'l', style: { ...body, marginTop: 32 } },
      d.vinetas.map((v, i) => h('li', { key: i }, v))
    )
  ],
  imagen: (d, recursos) => [
    h(Img, {
      key: 'i',
      src: imageSource(d, recursos),
      alt: d.alt,
      style: { maxWidth: '100%', maxHeight: d.pie ? '80%' : '100%', objectFit: 'contain', alignSelf: 'center' }
    }),
    d.pie && h('p', { key: 'p', style: { ...body, fontSize: 32, textAlign: 'center' } }, d.pie)
  ],
  codigo: (d) => [
    d.titulo && h('h2', { key: 't', style: heading }, d.titulo),
    h('pre', { key: 'c', style: { fontFamily: `'${MONO}', monospace`, fontSize: 32, background: '#f3f4f6', padding: 32, whiteSpace: 'pre-wrap' } }, d.codigo)
  ],
  cierre: (d) => [
    h('h2', { key: 't', style: { ...heading, textAlign: 'center' } }, d.titulo),
    d.mensaje && h('p', { key: 'm', style: { ...body, textAlign: 'center' } }, d.mensaje)
  ]
}

// la imagen va dentro de la petición, por su sha256: un data: URL, nunca una dirección de red
export function imageSource(diapositiva, recursos) {
  const image = recursos[diapositiva.imagen]
  if (image == null) throw new Error(`the image ${diapositiva.imagen} is not among the resources`)
  return `data:${image.type};base64,${image.base64}`
}

export function Slide({ diapositiva, tipografia, recursos = {} }) {
  const kind = KINDS[diapositiva.tipo]
  if (kind == null) throw new Error(`unknown slide type ${diapositiva.tipo}`)
  return h(AbsoluteFill, { style: page(tipografia) }, ...kind(diapositiva, recursos).filter(Boolean))
}
