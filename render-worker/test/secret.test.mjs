// el secreto del trabajador no llega a sus procesos hijos (#369, L2): main.mjs lo saca de process.env al leerlo, así que ni
// el Chrome de Remotion ni ffprobe lo heredan, y ffprobe corre además con un entorno mínimo, no con el del trabajador
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { after, describe, it } from 'node:test'
import { createProbe, defaultFfprobe } from '../src/ffprobe.mjs'
import { takeSecret } from '../src/secret.mjs'

const NAME = 'RENDER_WORKER_TOKEN'
const SECRET = 'a-secret-that-no-child-may-see-1234567'
const dir = mkdtempSync(path.join(os.tmpdir(), 'render-worker-secret-'))
after(() => rmSync(dir, { recursive: true, force: true }))

// lo que ve un proceso hijo lanzado con el entorno por defecto, el que heredan Chrome y cualquier otro
const childSees = () => execFileSync(process.execPath, ['-e', `process.stdout.write(process.env.${NAME} ?? '<none>')`]).toString()

describe('el secreto del trabajador', () => {
  it('se lee una vez y sale de process.env: un hijo ya no lo ve', () => {
    process.env[NAME] = SECRET
    assert.equal(childSees(), SECRET, 'sin sacarlo, el hijo lo hereda')

    assert.equal(takeSecret(NAME), SECRET)
    assert.equal(process.env[NAME], undefined)
    assert.equal(childSees(), '<none>')
    assert.equal(takeSecret(NAME), undefined, 'leído, ya no está')
  })

  it('ffprobe corre con un entorno mínimo, sin el secreto ni lo demás del trabajador', async () => {
    // un ffprobe falso que responde con lo que ve de su entorno
    const fake = path.join(dir, 'ffprobe')
    writeFileSync(fake, `#!${process.execPath}\nprocess.stdout.write(JSON.stringify({ env: process.env }))\n`)
    chmodSync(fake, 0o755)
    process.env[NAME] = SECRET
    process.env.RENDER_WORKER_OTHER = 'otro'
    process.env.FFPROBE = fake
    try {
      const { env } = await createProbe(defaultFfprobe())(path.join(dir, 'video.mp4'))
      assert.equal(env[NAME], undefined)
      assert.equal(env.RENDER_WORKER_OTHER, undefined)
      assert.deepEqual(
        Object.keys(env).filter((key) => key !== 'PATH' && key !== 'LD_LIBRARY_PATH'),
        []
      )
    } finally {
      delete process.env[NAME]
      delete process.env.RENDER_WORKER_OTHER
      delete process.env.FFPROBE
    }
  })

  it('el ffprobe de Remotion lleva solo PATH y la carpeta de sus bibliotecas', () => {
    process.env[NAME] = SECRET
    try {
      const { env } = defaultFfprobe()
      assert.equal(env[NAME], undefined)
      assert.deepEqual(
        Object.keys(env).filter((key) => key !== 'PATH' && key !== 'LD_LIBRARY_PATH'),
        []
      )
      if (process.platform === 'linux') assert.ok(env.LD_LIBRARY_PATH)
    } finally {
      delete process.env[NAME]
    }
  })
})
