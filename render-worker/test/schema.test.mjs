// el guion se valida igual con zod (el trabajador) y con schema/guion.schema.json (el contrato que también aplica el backend):
// los mismos ejemplos de schema/ejemplos/ dan lo mismo en los dos. Los inválidos «regla-*» cumplen el esquema y solo los
// rechazan las reglas en código, como en el backend
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import { FPS, guionSchema, RESOLUCIONES, rules, TIPOGRAFIAS } from '../src/guion.mjs'

const SCHEMA_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'schema')
const schema = JSON.parse(readFileSync(path.join(SCHEMA_DIR, 'guion.schema.json'), 'utf8'))
const validate = new Ajv2020({ strict: true, allErrors: true }).compile(schema)

const examples = (kind) =>
  readdirSync(path.join(SCHEMA_DIR, 'ejemplos', kind))
    .filter((name) => name.endsWith('.json'))
    .map((name) => [name, JSON.parse(readFileSync(path.join(SCHEMA_DIR, 'ejemplos', kind, name), 'utf8'))])

const zodAccepts = (guion) => guionSchema.safeParse(guion).success && rules(guion).length === 0

describe('the script schema', () => {
  for (const [name, guion] of examples('validos')) {
    it(`accepts ${name} in zod and in JSON Schema`, () => {
      assert.ok(validate(guion), JSON.stringify(validate.errors))
      const parsed = guionSchema.safeParse(guion)
      assert.ok(parsed.success, JSON.stringify(parsed.error?.issues))
      assert.deepEqual(rules(parsed.data), [])
    })
  }

  for (const [name, guion] of examples('invalidos')) {
    const ruleOnly = name.startsWith('regla-')
    it(`rejects ${name}${ruleOnly ? ' by a rule the schema cannot express' : ' in zod and in JSON Schema'}`, () => {
      assert.equal(zodAccepts(guion), false)
      assert.equal(validate(guion), ruleOnly, JSON.stringify(validate.errors))
    })
  }

  it('has examples of both kinds', () => {
    assert.ok(examples('validos').length >= 2)
    assert.ok(examples('invalidos').filter(([name]) => name.startsWith('regla-')).length >= 3)
  })

  // ADR-0040 §2: additionalProperties false en todos los objetos
  it('closes every object of the schema', () => {
    const open = []
    const walk = (node, at) => {
      if (Array.isArray(node)) return node.forEach((child, i) => walk(child, `${at}/${i}`))
      if (node === null || typeof node !== 'object') return
      if (node.type === 'object' && node.additionalProperties !== false) open.push(at)
      for (const [key, child] of Object.entries(node)) walk(child, `${at}/${key}`)
    }
    walk(schema, '#')
    assert.deepEqual(open, [])
  })

  it('names the same closed lists as zod', () => {
    assert.deepEqual(schema.properties.tipografia.enum, TIPOGRAFIAS)
    assert.deepEqual(schema.$defs.salida.properties.resolucion.enum, RESOLUCIONES)
    assert.deepEqual(schema.$defs.salida.properties.fps.enum, FPS)
    const kinds = schema.$defs.seccion.properties.diapositiva.oneOf.map((ref) => schema.$defs[ref.$ref.split('/').pop()].properties.tipo.const)
    assert.deepEqual(kinds, ['portada', 'vinetas', 'imagen', 'codigo', 'cierre'])
  })
})
