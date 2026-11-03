import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { buildVtt, timestamp } from '../src/vtt.mjs'

const guion = (secciones) => ({ version: 1, idioma: 'es', titulo: 't', secciones })
const seccion = (id, segundos, subtitulos) => ({ id, titulo: id, segundos, diapositiva: { tipo: 'portada', titulo: id }, subtitulos })

describe('the subtitles', () => {
  it('turn section times into video times', () => {
    const vtt = buildVtt(
      guion([seccion('a', 4, [{ desde: 0, hasta: 3.5, texto: 'Uno' }]), seccion('b', 6, []), seccion('c', 5, [{ desde: 1.25, hasta: 2, texto: 'Dos' }])])
    )
    assert.equal(vtt, 'WEBVTT\n\n00:00:00.000 --> 00:00:03.500\nUno\n\n00:00:11.250 --> 00:00:12.000\nDos\n')
  })

  it('escape their text, which is data', () => {
    const vtt = buildVtt(guion([seccion('a', 4, [{ desde: 0, hasta: 1, texto: '<b>x</b> & y --> z\n\n00:00:00.000 --> 00:01:00.000' }])]))
    assert.equal(vtt, 'WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n&lt;b&gt;x&lt;/b&gt; &amp; y --&gt; z\n00:00:00.000 --&gt; 00:01:00.000\n')
  })

  it('format hours', () => assert.equal(timestamp(3725.5), '01:02:05.500'))
})
