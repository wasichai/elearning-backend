// las diapositivas pintan el texto del guion como texto: lo que parece HTML o código se escapa, nunca se interpreta
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { createElement as h } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { imageSource, Slide } from '../src/remotion/slides.mjs'
import { metadata } from '../src/remotion/Video.mjs'

const markup = (diapositiva, recursos) => renderToStaticMarkup(h(Slide, { diapositiva, tipografia: 'inter', recursos }))

describe('the slides', () => {
  it('render HTML-looking text as text', () => {
    const html = markup({ tipo: 'vinetas', titulo: '<script>alert(1)</script>', vinetas: ['<img src=x onerror=alert(1)>'] })
    assert.ok(!html.includes('<script>'), html)
    assert.ok(!html.includes('<img'), html)
    assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'), html)
  })

  it('show code as text', () => {
    const html = markup({ tipo: 'codigo', codigo: 'const a = <div dangerouslySetInnerHTML={{__html: x}} />' })
    assert.ok(html.includes('&lt;div dangerouslySetInnerHTML'), html)
  })

  it('draw an image from the request, never from a URL', () => {
    const slide = { tipo: 'imagen', imagen: 'a'.repeat(64), alt: 'Un gráfico' }
    assert.equal(imageSource(slide, { ['a'.repeat(64)]: { type: 'image/png', base64: 'iVBORw0KGgo=' } }), 'data:image/png;base64,iVBORw0KGgo=')
    assert.throws(() => imageSource(slide, {}), /not among the resources/)
  })

  it('refuse a slide type outside the closed list', () => {
    assert.throws(() => markup({ tipo: 'html', html: '<b>x</b>' }), /unknown slide type html/)
  })

  it('take duration, fps and size from the script', () => {
    const guion = {
      salida: { resolucion: '1920x1080', fps: 25 },
      tipografia: 'inter',
      secciones: [
        { id: 'a', segundos: 3, diapositiva: { tipo: 'portada', titulo: 'A' } },
        { id: 'b', segundos: 4, diapositiva: { tipo: 'cierre', titulo: 'B' } }
      ]
    }
    assert.deepEqual(metadata({ guion }), { durationInFrames: 175, fps: 25, width: 1920, height: 1080 })
  })
})

describe('the video bitrate', () => {
  it('is the one of its resolution while it fits, and lower when it would not', async () => {
    const { videoBitrate } = await import('../src/renderer.mjs')
    assert.equal(videoBitrate(60, 256 * 1024 * 1024, '1280x720'), '1200k')
    assert.equal(videoBitrate(60, 256 * 1024 * 1024, '1920x1080'), '2000k')
    assert.equal(videoBitrate(900, 50 * 1024 * 1024, '1920x1080'), '419k')
  })
})
