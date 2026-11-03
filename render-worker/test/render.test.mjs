// el render de verdad con Remotion y su ffprobe: solo con RENDER_WORKER_TEST_RENDER=required (CI, que descarga antes el
// Chrome Headless Shell con yarn ensure-browser) o con RENDER_WORKER_BROWSER (un Chrome del sistema). Sin ninguno, se salta:
// las demás pruebas usan un renderizador simulado
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createProbe } from '../src/ffprobe.mjs'
import { duration, parsePeticion } from '../src/guion.mjs'
import { createRemotionRenderer } from '../src/renderer.mjs'

const EXAMPLES = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'schema', 'ejemplos')
const enabled = process.env.RENDER_WORKER_TEST_RENDER === 'required' || Boolean(process.env.RENDER_WORKER_BROWSER)

describe('the real render', { skip: enabled ? false : 'set RENDER_WORKER_TEST_RENDER=required or RENDER_WORKER_BROWSER' }, () => {
  it('renders the complete example to an H.264 MP4 of its resolution, fps and duration', { timeout: 10 * 60 * 1000 }, async () => {
    const png = readFileSync(path.join(EXAMPLES, 'recursos', 'azul.png'))
    const body = {
      guion: JSON.parse(readFileSync(path.join(EXAMPLES, 'validos', 'completo.json'), 'utf8')),
      recursos: { [createHash('sha256').update(png).digest('hex')]: png.toString('base64') }
    }
    const { value, issues } = parsePeticion(body)
    assert.equal(issues, undefined)
    const dir = mkdtempSync(path.join(os.tmpdir(), 'render-worker-real-'))
    try {
      const output = path.join(dir, 'video.mp4')
      const renderer = createRemotionRenderer({ browserExecutable: process.env.RENDER_WORKER_BROWSER || null, maxBytes: 256 * 1024 * 1024 })
      await renderer.render({ ...value, output, seconds: duration(value.guion) })
      const probe = await createProbe()(output)
      const video = probe.streams.find((s) => s.codec_type === 'video')
      assert.equal(video.codec_name, 'h264')
      assert.deepEqual([video.width, video.height, video.r_frame_rate], [1280, 720, '25/1'])
      assert.ok(Math.abs(Number(probe.format.duration) - 22) <= 1 / 25 + 0.1, probe.format.duration)
      assert.equal(probe.streams.filter((s) => s.codec_type === 'audio').length, 0)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})
