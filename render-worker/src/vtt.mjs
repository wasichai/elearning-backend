// los subtítulos del guion como WebVTT. Cada subtítulo cuenta desde el principio de su sección; aquí pasan a tiempo del
// vídeo. El texto es dato: se escapa (&, <, >) y no puede cerrar la señal con una línea vacía ni con «-->»
export function buildVtt(guion) {
  const cues = []
  let offset = 0
  for (const seccion of guion.secciones) {
    for (const sub of seccion.subtitulos ?? []) {
      cues.push(`${timestamp(offset + sub.desde)} --> ${timestamp(offset + sub.hasta)}\n${escape(sub.texto)}`)
    }
    offset += seccion.segundos
  }
  return ['WEBVTT', ...cues].join('\n\n') + '\n'
}

export function timestamp(seconds) {
  const millis = Math.round(seconds * 1000)
  const h = Math.floor(millis / 3_600_000)
  const m = Math.floor((millis % 3_600_000) / 60_000)
  const s = Math.floor((millis % 60_000) / 1000)
  const ms = millis % 1000
  return `${pad(h, 2)}:${pad(m, 2)}:${pad(s, 2)}.${pad(ms, 3)}`
}

function pad(value, width) {
  return String(value).padStart(width, '0')
}

function escape(text) {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .join('\n')
}
