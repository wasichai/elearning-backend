// la entrada del bundle de Remotion (src/renderer.mjs): una única composición, «guion»
import { createElement as h } from 'react'
import { Composition, registerRoot } from 'remotion'
import './fonts.mjs'
import { metadata, Video } from './Video.mjs'

const EXAMPLE = {
  guion: {
    version: 1,
    idioma: 'es',
    titulo: 'Vídeo',
    tipografia: 'inter',
    salida: { resolucion: '1280x720', fps: 30 },
    secciones: [{ id: 'portada', titulo: 'Portada', segundos: 1, diapositiva: { tipo: 'portada', titulo: 'Vídeo' } }]
  },
  recursos: {}
}

const Root = () =>
  h(Composition, {
    id: 'guion',
    component: Video,
    defaultProps: EXAMPLE,
    calculateMetadata: ({ props }) => metadata(props),
    ...metadata(EXAMPLE)
  })

registerRoot(Root)
