// un secreto del entorno se lee una vez y sale de process.env (#369): lo que el trabajador lance después (el Chrome de
// Remotion, ffprobe) hereda process.env y no lo necesita
export function takeSecret(name, env = process.env) {
  const value = env[name]
  delete env[name]
  return value
}
