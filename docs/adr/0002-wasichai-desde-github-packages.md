# ADR-0002: Wasichai se consume como librerías desde GitHub Packages

**Estado**: aceptada · 2026-10-27

## Contexto

- Wasichai se distribuye como librerías: una BOM y un starter por módulo, con auto-configuración explícita
  ([ADR-024 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0024-libraries-and-starters.md)).
  La versión vigente es **1.6.0** (grupo Maven `wasichai`, 24 artefactos); el frontend, `@wasichai/*` **1.3.0**.
- Ambos se publican **solo** en GitHub Packages: `https://maven.pkg.github.com/wasichai/wasichai`
  (`build-logic/.../wasichai.publishing.gradle.kts`) y `npm.pkg.github.com`. Maven Central y npm público: ausentes
  (verificado, 404).
- `docs/development/releasing.md` de Wasichai: «GitHub Packages requires a token even for public packages;
  `read:packages` is enough to install».
- La aplicación de referencia, sgspe, ya lo hace así: `platform("wasichai:wasichai-bom:…")` desde GitHub Packages con
  `mavenLocal()` de respaldo (`settings.gradle.kts`) y «El token nunca va a un archivo» (`CLAUDE.md`).

Ver [`reference-repositories.md` §2–§4](../research/reference-repositories.md).

## Decisión

**Backend:** `platform("wasichai:wasichai-bom:1.6.0")` y los starters que se usen, resueltos desde
`maven.pkg.github.com/wasichai/wasichai` con `content { includeGroup("wasichai") }`, de modo que ese repositorio solo
sirve el grupo `wasichai`. `mavenLocal()` queda como respaldo para probar un Wasichai compilado en local.

**Frontend:** `@wasichai/*` en 1.3.0 exacta, con `.npmrc` del repositorio que solo dice
`@wasichai:registry=https://npm.pkg.github.com`.

**El token nunca está en un archivo del repositorio.** Gradle lo lee de `GITHUB_ACTOR` / `GITHUB_TOKEN`
(`providers.environmentVariable`); en local, `GITHUB_TOKEN=$(gh auth token)` con el alcance `read:packages`. El token
de npm va en el `~/.npmrc` del usuario, fuera del repositorio. En CI, `packages: read` del `GITHUB_TOKEN` del flujo.

Las versiones del stack (Kotlin, Spring Boot, Flyway…) se alinean con las que fija Wasichai 1.6.0 vía la BOM, no con
la última publicada.

## Alternativas consideradas

- **Composite build con clones hermanos** (`includeBuild("../wasichai")`). Permite depurar Wasichai, pero ata la
  compilación a un checkout local y a su `build-logic`, que Wasichai no publica; un colaborador tendría que clonar y
  compilar dos repositorios más. Rechazado como modo normal; `mavenLocal()` cubre el caso de probar un cambio local.
- **Vendorizar el código de Wasichai** en este repositorio. Rechazado: ningún repositorio de Wasichai tiene licencia
  (ver [ADR-0009](0009-licencia-diferida.md)), así que copiar su código no tiene base legal; además, la copia divergiría.
- **Esperar a que Wasichai publique en Maven Central / npm.** Fuera del control de este proyecto y sin fecha.

## Consecuencias

- Compilar exige una cuenta de GitHub y un token personal (PAT) con `read:packages`, **también para un colaborador
  open source que solo quiera ejecutar las pruebas**. Es una barrera de entrada real; `getting-started.md` la explica
  paso a paso.
- Si el token falta o no tiene el alcance, la resolución falla al inicio con 401; no hay modo degradado.
- Subir de versión de Wasichai es cambiar la BOM y el `package.json`, y leer su `docs/HISTORY.md` y sus ADR nuevos.
- `mavenLocal()` puede ocultar un artefacto que no existe publicado: CI no lo usa (a verificar al escribir el flujo).
- Abierto: si Wasichai llega a publicar en un registro público, un ADR nuevo retira el requisito del token.
