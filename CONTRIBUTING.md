# Contribuir

Gracias por tu interés. Antes de nada, ten en cuenta que **la licencia del proyecto aún no está decidida**
([docs/legal/licensing.md](docs/legal/licensing.md)); hasta entonces no se aceptan contribuciones externas de código.

## Cómo se trabaja

1. Lee [CLAUDE.md](CLAUDE.md): reglas del proyecto, válidas para personas y agentes.
2. Cada cambio nace de un issue (las tareas grandes son épicas con pasos como sub-issues).
3. Rama desde `main`, commits [Conventional Commits](https://www.conventionalcommits.org/) en español
   (`feat: …`, `fix: …`, `docs: …`), comprobados por commitlint.
4. Pruebas primero. Un cambio de permisos lleva su prueba de autorización negativa.
5. Antes del PR: `./gradlew ktlintFormat build integrationTest` y `yarn format:check`.
6. El PR enlaza su issue (`Closes #N`), describe qué y por qué, y cómo se verificó.

## Entorno

[docs/development/getting-started.md](docs/development/getting-started.md). Necesitarás un token personal de GitHub con
`read:packages` para descargar Wasichai; nunca lo guardes en el repositorio.

## Datos

Solo datos sintéticos. Nada de datos personales reales ni credenciales en fixtures, capturas o issues.
