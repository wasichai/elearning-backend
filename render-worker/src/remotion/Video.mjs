// el vídeo: una secuencia por sección del guion, con su diapositiva, una tras otra
import { createElement as h } from 'react'
import { AbsoluteFill, Sequence } from 'remotion'
import { Slide } from './slides.mjs'

export function Video({ guion, recursos }) {
  const fps = guion.salida.fps
  let from = 0
  const sequences = guion.secciones.map((seccion) => {
    const frames = seccion.segundos * fps
    const sequence = h(
      Sequence,
      { key: seccion.id, from, durationInFrames: frames, name: seccion.id },
      h(Slide, { diapositiva: seccion.diapositiva, tipografia: guion.tipografia, recursos })
    )
    from += frames
    return sequence
  })
  return h(AbsoluteFill, { style: { backgroundColor: '#ffffff' } }, ...sequences)
}

// lo que Remotion necesita de la composición: duración, fps y tamaño salen del guion, nunca de otra parte
export function metadata({ guion }) {
  const [width, height] = guion.salida.resolucion.split('x').map(Number)
  const seconds = guion.secciones.reduce((sum, s) => sum + s.segundos, 0)
  return { durationInFrames: seconds * guion.salida.fps, fps: guion.salida.fps, width, height }
}
