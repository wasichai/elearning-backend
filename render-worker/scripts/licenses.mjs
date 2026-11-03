// informe de licencias de las dependencias del trabajador (criterio de #267, ADR-0039): recorre node_modules y lista cada
// paquete con su licencia. Falla si algún paquete que no es de Remotion no tiene una licencia libre de la lista, y si
// Remotion no aparece (el informe tiene que mostrarlo). Remotion (remotion y @remotion/*) va aparte, con su licencia propia
// (remotion.dev/license): es la única dependencia no libre del proyecto y solo vive aquí. En CI corre sobre una instalación
// de producción (yarn install --production), que es lo que lleva la imagen.
//
//   node scripts/licenses.mjs [--out informe.md]
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const ALLOWED = new Set([
  '0BSD',
  'Apache-2.0',
  'BlueOak-1.0.0',
  'BSD-2-Clause',
  'BSD-3-Clause',
  'CC-BY-4.0',
  'CC0-1.0',
  'ISC',
  'MIT',
  'MPL-2.0',
  'Python-2.0',
  'Unlicense'
])

const isRemotion = (name) => name === 'remotion' || name.startsWith('@remotion/')

function licenseOf(pkg) {
  if (typeof pkg.license === 'string') return pkg.license
  if (pkg.license?.type) return pkg.license.type
  if (Array.isArray(pkg.licenses)) return pkg.licenses.map((l) => l.type ?? l).join(' OR ')
  return null
}

// una expresión SPDX sencilla: con OR basta una permitida; con AND, todas
export function allowed(license) {
  if (!license) return false
  const expression = license.replace(/[()]/g, ' ').trim()
  if (/\sOR\s/.test(expression)) return expression.split(/\s+OR\s+/).some((part) => allowed(part))
  if (/\sAND\s/.test(expression)) return expression.split(/\s+AND\s+/).every((part) => allowed(part))
  return ALLOWED.has(expression)
}

function collect(dir, found) {
  if (!existsSync(dir)) return
  for (const entry of readdirSync(dir)) {
    if (entry.startsWith('.')) continue
    const full = path.join(dir, entry)
    if (entry.startsWith('@')) {
      collect(full, found)
      continue
    }
    const manifest = path.join(full, 'package.json')
    if (!existsSync(manifest)) continue
    const pkg = JSON.parse(readFileSync(manifest, 'utf8'))
    found.set(`${pkg.name}@${pkg.version}`, { name: pkg.name, version: pkg.version, license: licenseOf(pkg) })
    collect(path.join(full, 'node_modules'), found)
  }
}

const found = new Map()
collect(path.join(ROOT, 'node_modules'), found)
const packages = [...found.values()].sort((a, b) => a.name.localeCompare(b.name))
const remotion = packages.filter((p) => isRemotion(p.name))
const rejected = packages.filter((p) => !isRemotion(p.name) && !allowed(p.license))
const counts = {}
for (const p of packages.filter((p) => !isRemotion(p.name))) counts[p.license] = (counts[p.license] ?? 0) + 1

const lines = [
  '# Licencias de render-worker',
  '',
  `${packages.length} paquetes instalados.`,
  '',
  '## Remotion (licencia propia, ADR-0039: solo en este paquete)',
  '',
  ...remotion.map((p) => `- ${p.name}@${p.version}: ${p.license ?? 'sin campo license'}`),
  '',
  '## El resto, por licencia',
  '',
  ...Object.entries(counts)
    .sort()
    .map(([license, n]) => `- ${license}: ${n}`),
  '',
  '## Paquetes',
  '',
  ...packages.filter((p) => !isRemotion(p.name)).map((p) => `- ${p.name}@${p.version}: ${p.license}`),
  ''
]
const report = lines.join('\n')
const out = process.argv.indexOf('--out')
if (out > 0) writeFileSync(process.argv[out + 1], report)
console.log(lines.slice(0, lines.indexOf('## Paquetes')).join('\n'))

if (remotion.length === 0) {
  console.error('Remotion no está entre las dependencias instaladas: el informe no puede mostrarlo')
  process.exit(1)
}
if (rejected.length > 0) {
  console.error(`Licencias fuera de la lista (${[...ALLOWED].join(', ')}):`)
  for (const p of rejected) console.error(`- ${p.name}@${p.version}: ${p.license ?? 'sin licencia declarada'}`)
  process.exit(1)
}
