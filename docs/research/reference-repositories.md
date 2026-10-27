# Repositorios de referencia

Qué hay realmente en las bibliotecas y materiales sobre los que se construye wasichai-learning, leído el
2026-10-27 con `gh api` (sin clonar) y contra los registros públicos. Cada afirmación cita su origen. «Ausente
(verificado)» significa que se buscó en el árbol completo y no está; lo no revisado se dice como tal.

| Repositorio | Commit consultado | Fecha |
|---|---|---|
| [wasichai/wasichai](https://github.com/wasichai/wasichai) | `a9ad74d56338` | 2026-10-26 |
| [wasichai/wasichai-ui](https://github.com/wasichai/wasichai-ui) | `731bad64a69f` | 2026-10-26 |
| [wasichai/sgspe](https://github.com/wasichai/sgspe) | `0ebf69ffcbe3` | 2026-10-25 |
| [wasichai/sgspe-ui](https://github.com/wasichai/sgspe-ui) | `7852e90a31c1` | 2026-10-25 |
| [wasichai/srtm-backend](https://github.com/wasichai/srtm-backend) | `6daacd307726` | 2026-10-27 |
| [embabel/embabel-agent](https://github.com/embabel/embabel-agent) | `v1.5.3` (`4fbd06ad335b`) | 2026-10-05 |

## 1. Versiones reales

Fuente autoritativa: `repo1.maven.org/maven2/**/maven-metadata.xml` y `registry.npmjs.org`. El índice de
búsqueda de `search.maven.org` estaba desactualizado (devolvía Spring Boot 3.5.3) y no se usa.

| Pieza | Wasichai la fija en | Última estable publicada |
|---|---|---|
| Kotlin | `2.4.21` (`gradle/libs.versions.toml`) | 2.4.21 |
| Spring Boot | `4.1.1` | 4.1.2 (4.2.0-RC1 no estable) |
| Gradle | `9.8.2` (`gradle-wrapper.properties`) | 9.8.2 |
| JDK | `jvmToolchain(25)`; CI temurin 25 | — |
| PostgreSQL | `postgres:18` (`WasichaiTestDatabase.DEFAULT_IMAGE`) | imagen `postgres:18.6` |
| Flyway | `13.10.3` | 13.11.1 |
| Testcontainers | `2.0.5` | — |
| Jackson | 3 (`tools.jackson.module:jackson-module-kotlin`) | — |
| Embabel Agent | `1.5.3` (`embabel-agent-starter`, `-starter-anthropic`) | 1.5.3, Apache-2.0 |
| Spring AI | sin entrada propia | 2.0.2 (Embabel 1.5.3 depende de `spring-ai-*:2.0.1`) |
| springdoc-openapi | ausente (verificado) | 3.2.0 (línea Boot 4) |
| r2dbc-postgresql | vía starter | 1.1.3.RELEASE |

`embabel-agent-api-1.5.3.pom` declara `spring-boot-starter:4.1.1` y `spring-ai-client-chat:2.0.1`: Embabel
es compatible con el stack objetivo.

wasichai-ui 1.3.0 (`packages/*/package.json`): React `^19.3.0`, Vite `8.3.5`, Tailwind `4.3.3`, TypeScript
`7.0.2`, react-router `^8.4.0`, @tanstack/react-query `^5.103.1`, zod `^4.6.6`, i18next `^26.4.2`,
react-hook-form `^7.89.0`, vitest `5.0.4`, Testing Library 16.3; Radix + componentes estilo shadcn;
`engines.node: ">=26"`; yarn 1 (sin `packageManager`). Playwright y Storybook: ausentes (verificado) en
wasichai-ui; Playwright 1.63.0 sí está en `sgspe-ui`.

## 2. wasichai (backend)

**Qué es.** «A metadata-driven application platform, as libraries» (`README.md`). Reglas de `CLAUDE.md` que
este proyecto hereda: WebFlux reactivo, «JPA / Hibernate / Envers are forbidden, and so are Spring Data
repositories»; SQL con `DatabaseClient` y valores ligados; nombres de esquema desde `WasichaiSchemas`; sin
escaneo de componentes de librería; multi-tenant por `organization_id`; «No overengineering: an abstraction
needs a concrete second user»; `.editorconfig` es ley (160 columnas); Conventional Commits; «Port 5432 is
never used by a test»; historial en `docs/HISTORY.md` (lo más nuevo primero) y decisiones en `docs/adr/`,
que se cambian con un ADR nuevo, nunca editando uno viejo.

**Módulos** (`settings.gradle.kts`): `wasichai-core` (metadata, registros, identidad, organizaciones,
auditoría), `wasichai-bom`, `wasichai-test`, y opcionales `views`, `forms`, `pages`, `workflow`,
`automation`, `documents`, `gis`, `agent`, `notifications`, `files`; un starter por módulo en `starters/`.

**Consumo.** Grupo Maven `wasichai`, versión 1.6.0 (`gradle.properties`, release 2026-10-26), 24 artefactos
(`.github/scripts/check-maven-publications.sh`), publicados **solo** en
`https://maven.pkg.github.com/wasichai/wasichai` (`build-logic/src/main/kotlin/wasichai.publishing.gradle.kts`).
`docs/development/releasing.md`: «GitHub Packages requires a token even for public packages; `read:packages`
is enough to install». Maven Central: ausente (verificado, 404 en `io|com|org|dev|net|pe/wasichai`). Las
convenciones de `build-logic` no se publican. Los samples (`simple-sample`, `documents-sample`, `gis-sample`,
`full-sample`) solo tienen un `README.md` de una línea: no hay ejemplo ejecutable de app consumidora.

**Extensión** (`docs/guides/build-your-app.md`, ADR-024/025/026): un módulo propio es una librería con
`@AutoConfiguration` registrada en `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
beans que implementan los SPI del core y su `ModuleMigration` con historial Flyway propio
(`flyway_history_<nombre>`). SPI disponibles (`docs/architecture/overview.md`): `FieldTypeHandler`,
`RecordQueryContributor`, `SystemColumnContributor`, `RecordChangeListener`, `RecordWriteGuard`,
`RecordReadScope`, `RecordReadMask`, `MetadataChangeListener`, `WorkflowStates`, `ModuleMigration`,
`PageComponentProvider`, `DocumentIssuer`, `NotificationSource`, `DeliveryChannel`, `FileStore`,
`AgentAccessPolicy`, `AgentResultFilter`, `AgentAnswerFilter`, `AgentRunListener`. API programática:
`RecordService` (`create`, `createAll`, `update`, `patch`, `delete`, `list`, `asPlatform`,
`forEachOrganization`), `ClusterLock`, `TenantDirectory`, `CurrentUser`. La app se anota con
`@WasichaiApplication` y no puede vivir bajo el paquete `wasichai`.

**Seguridad.** JWT HS256 propio, «OIDC-ready» (ADR-010, `docs/security/authentication.md`); claims `sub`,
`org`, `email`, `roles`, `jti`; secreto ≥ 32 bytes (`WASICHAI_JWT_SECRET`). La cadena de Wasichai es
`@Order(0)` con `anyExchange().authenticated()` y `permitAll` solo para login, token y salud; un módulo con
cadena propia necesita `@Order` menor que cero (ADR-025). Permisos `(rol, objeto, acción)` con acciones
`READ`, `CREATE`, `UPDATE`, `DELETE`, `MANAGE_*` y acciones declaradas por objeto (ADR-042);
`CurrentUser.requirePermission(user, action, objectId)` (`wasichai-core/.../identity/CurrentUser.kt`); un
usuario puede tener **varios roles** (`user_roles`, PK `(user_id, role_id)`). Tenancy por columna
`organization_id`; RLS ausente (verificado). Auditoría `audit_log` append-only con triggers (ADR-049, 050, 054).

**Persistencia.** R2DBC `DatabaseClient` + corrutinas; repositorios escritos a mano (ejemplo:
`wasichai-forms/.../FormRepository.kt`). Flyway corre aparte con una conexión JDBC de vida corta
(`WasichaiMigrations`, ADR-008); migraciones en `db/wasichai/<módulo>/V<n>__<nombre>.sql`. Esquemas: metadata
(`wasichai`) y datos (`app_data`, una tabla física por Custom Object, ADR-004). Tipos de campo
(`docs/domain/metadata-model.md`): `TEXT`, `LONG_TEXT`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`, `DATETIME`,
`ENUM`, `EMAIL`, `URL`, `UUID`, `RELATION` (+ `FILE`/`IMAGE` con files, `GEOMETRY` con gis); `JSON` está
«planned». Relaciones `MANY_TO_ONE`, `ONE_TO_ONE`, `ONE_TO_MANY`, `MANY_TO_MANY`.

**REST.** Prefijo `/api`, controladores anotados con funciones `suspend`, errores RFC 7807 `ProblemDetail`
con `WasichaiException` extensible, ETag/If-Match, `Idempotency-Key` (ADR-058). OpenAPI ausente (verificado):
la API se documenta a mano en `docs/api/rest.md`.

**Capacidades reutilizables.** `wasichai-files` (`FileStore` local y S3), `wasichai-notifications` (email,
SSE con LISTEN/NOTIFY), `wasichai-automation` (cola persistente, ADR-016), `wasichai-workflow` (estado en el
registro, ADR-013), `wasichai-documents` (documentos congelados al emitir, ADR-023; sin motor PDF en el
servidor), `wasichai-agent` (Embabel, nueve herramientas de solo lectura, `POST /api/agent/ask`, ADR-014/015/056).
Menú/navegación del backend: ausente (verificado); vive en el registro del frontend (ADR-028).

**Pruebas.** `./gradlew build` (ktlint + unitarias + arquitectura), `./gradlew integrationTest` (tag
`integration`, Testcontainers o BD externa `WASICHAI_TEST_DB_*` cuyo nombre termina en `_test`).
`WasichaiIntegrationTest` (`@SpringBootTest(RANDOM_PORT)`, `WebTestClient`). La prueba de arquitectura es
escaneo de fuentes con AssertJ (`CoreArchitectureTest`); MockK, Kotest, ArchUnit, Konsist: ausentes (verificado).

## 3. wasichai-ui (frontend)

Paquetes `@wasichai/{ui,core,testing,agent,automation,documents,forms,gis,pages,views,workflow}` 1.3.0,
publicados **solo** en `npm.pkg.github.com` (`.npmrc`, `publishConfig`); en npm público: ausentes
(verificado, 404). `docs/README.md`: «Consumers: `.npmrc` with `@wasichai:registry=https://npm.pkg.github.com`
and `//npm.pkg.github.com/:_authToken=<PAT with read:packages>`».

**Registro de módulos** (`packages/core/src/registry/contract.ts`, ADR-028): `WasichaiModule { id, basePath,
routes: [{ id, path, component | lazy, chrome: 'shell' | 'bare' | 'public' }], navGroups, nav: [{ group,
labelKey, order, route, icon, visible(permissions) }], providers, i18n, …slots }`. `createRegistry` valida al
montar (ids duplicados, rutas, nav a rutas desconocidas). Arranque: `<WasichaiApp config modules />`
(`app/WasichaiApp.tsx`). Las rutas `public` se montan fuera de la sesión (`app/WasichaiRoutes.tsx`). Enlaces
solo con `useWasichaiLinks().to('<módulo>:<ruta>')`; cada paquete tiene `boundaries.test.ts` contra URLs
literales e importaciones entre módulos. i18n por espacio de nombres = id del módulo.

**Componentes** (`packages/ui/src/index.ts`, `packages/core`): `Button`, `Input`, `Textarea`, `Label`,
`Select`, `Table`, `Badge`, `Pagination`, `Alert`, `Dialog`, `ConfirmDialog`, `Card`, `Tabs`,
`DropdownMenu`; `DataTable`, `DynamicForm`, `AppShell`, `PageHeader`, `QueryState`, `EmptyState`,
`ErrorState`, `LoadingState`. Tema con tokens OKLCH en `packages/ui/src/theme.css`, claro/oscuro.
Autenticación: `POST /auth/login`, token en `localStorage`, permisos desde `GET /auth/me/permissions`;
«hiding by permission is a courtesy, the server decides». `@wasichai/testing`: `renderWithProviders`,
`mockFetch`, `jsonResponse`.

## 4. sgspe y sgspe-ui: la aplicación de referencia

- **Backend** (`sgspe`): un único proyecto Gradle, `@WasichaiApplication class SgspeApplication`,
  `platform("wasichai:wasichai-bom:1.4.0")` desde GitHub Packages con `mavenLocal()` de respaldo
  (`settings.gradle.kts`); credenciales `GITHUB_ACTOR`/`GITHUB_TOKEN=$(gh auth token)`, «El token nunca va a
  un archivo» (`CLAUDE.md`). Paquetes `sgspe.<contexto>`.
- **Modelo como metadata**: los objetos de negocio son Custom Objects en `model/model.json`, `roles.json`,
  `workflows/*.json`, aplicados por REST con scripts python3 de solo biblioteca estándar (`model/apply.py`).
  Las pruebas de integración ejecutan esos mismos scripts contra la app de prueba
  (`src/test/kotlin/sgspe/SgspeApiTest.kt`).
- **Reglas**: `RecordWriteGuard` (`common/Rules.kt`, `risk/RiskWriteGuard.kt`); permisos con
  `CurrentUser.requirePermission`, «nunca compara nombres de rol» (`common/Permissions.kt`); comandos propios
  en `/api/sgspe/**` con `Idempotency-Key`.
- **Tablas fijas** solo para lo técnico: `db/sgspe/<módulo>/V1__*.sql` con `ModuleMigration` orden 200 y
  esquema propio.
- **Entorno**: `compose.yml` con postgis, minio, mailpit, geoserver, latex y slides, todos en loopback.
- **Frontend** (`sgspe-ui`, repositorio aparte por su ADR-0010): `/admin` es `WasichaiApp`; el portal
  principal usa `createBrowserRouter` propio y solo registra módulos sin pantallas
  (`src/portal/PortalApp.tsx`). Playwright con API simulada en el navegador (`e2e/api.ts`).
- **Documentación** en español con nombres técnicos en inglés; ADR `docs/adr/NNNN-kebab.md`.

## 5. Manuales SRTM y SGSPE

| | SRTM (`srtm-backend/docs/manuales`) | SGSPE (`sgspe/docs/manuals`) |
|---|---|---|
| Fuentes | 23 `.adoc`, ~171 capturas, 9 diagramas Mermaid prerenderizados | 47 `.adoc`, 56 capturas, 11 diagramas |
| Organización | un libro por perfil (`usuario/registrador`, `arbitrios-emision`, `fiscalizador`, `anuncios`, `consulta`, `normativa`) + administrador + sistema; compartidos en `comun/`, `referencia/` | 4 maestros (`user`, `administrator`, `system`, `reference`) que incluyen capítulos `NN-tema.adoc` |
| Atributos | `atributos.adoc` | `attributes.adoc` (versión inyectada por `build-docs.sh`) |
| Grafo `include::` | profundidad 1; sin `tag=` ni `lines=`; rutas sin atributos | profundidad 2; solo `leveloffset=+1`; `reference/generated/*` generado desde `model/` |
| Construcción | `Makefile`: Asciidoctor 2.0.26, asciidoctor-pdf 2.3.15, `herramientas/validar.py`; CI `manuales.yml` | `scripts/build-docs.sh`, `validate-docs.py`; sin CI; PDF versionados en `output/pdf/` |

**Anatomía.** Procedimientos con forma fija: *Objetivo*, *Requisitos previos*, pasos numerados, *Resultado
esperado*, *Verificación*, *Casos especiales* (SRTM: bloque de ejemplo con lista horizontal y el comentario
`// fin de los pasos`; SGSPE: párrafos en negrita). Imágenes con `[[fig-…]]` y texto alternativo extenso,
tablas, admoniciones, `btn:`/`menu:`, referencias `<<id>>` entre fragmentos (los fragmentos **no** son
autocontenidos), glosarios y preguntas frecuentes.

**Preparación pedagógica.** Son documentación de referencia y procedimientos, **no** material didáctico: sin
objetivos de aprendizaje, ejercicios, cuestionarios, resúmenes ni duraciones (búsqueda verificada). Equivalentes
aprovechables: objetivo y verificación por tarea, secciones «a quién se dirige», FAQ, tablas de errores. La
excepción es SRTM `docs/presentaciones/src/videos/*.ts`: guiones Remotion con `objetivo`, audiencia y
`segundos` por escena, trazados a anclas del manual (`fuentes: [{ doc, ancla }]`).

**Candidatos para cursos de demostración**: SRTM `comun/acceso.adoc` y `usuario/anuncios.adoc` (con su guion
`06-anuncios.ts`); SGSPE `user/02-primeros-pasos.adoc` y `user/05-compromisos.adoc`.

**Datos.** Las capturas usan datos declarados ficticios (RUC 20999000020, DNI 95000077, «Compañía Minera
Wayra Ejemplo S.A.C.»); sí aparecen una institución real (Municipalidad Distrital de Perené) y credenciales
de desarrollo (`admin@wasichai.local`). La semilla de este proyecto no reutiliza ninguno.

## 6. Licencias

| Repositorio | Resultado |
|---|---|
| wasichai | sin licencia (verificado: árbol, `GET /license` 404, POM sin `licenses`, sin cabeceras) |
| wasichai-ui | sin licencia; ningún `package.json` declara `license` |
| srtm-backend | sin licencia del proyecto; solo licencias OFL/DejaVu/Liberation de fuentes |
| sgspe / sgspe-ui | sin licencia; `slides/package.json`: `"license": "UNLICENSED"` |
| embabel-agent | Apache-2.0 |

`SECURITY.md` de wasichai, wasichai-ui y srtm-backend es la plantilla de GitHub sin completar. Las
consecuencias están en [`docs/legal/licensing.md`](../legal/licensing.md).

## 7. Créditos académicos: fuentes normativas

- **Estados Unidos** — [34 CFR 600.2](https://www.ecfr.gov/current/title-34/subtitle-B/chapter-VI/part-600):
  una hora de crédito aproxima «not less than one hour of classroom or direct faculty instruction and a
  minimum of two hours of out-of-class student work each week for approximately fifteen weeks» para un
  crédito semestral, o una cantidad equivalente de trabajo en otras actividades (laboratorio, prácticas)
  según la institución y su acreditadora.
- **Europa** — ECTS ([EHEA](https://ehea.info/), ECTS Users' Guide): 60 créditos por año académico a tiempo
  completo, de 1500 a 1800 horas; un crédito equivale a 25–30 horas de carga total del estudiante,
  incluido el trabajo autónomo.

## 8. Entorno local verificado

Claude Code 2.1.312. Superpowers no está instalado (sin plugin ni skills; solo el marketplace
`claude-plugins-official`). JDK 25 y 26 (sdkman), Gradle del sistema 4.4.1 (se usa el wrapper), Node 26.12.1
por fnm, yarn 1.22.22, Docker 29.1.3, python3.14; sin `psql` ni `asciidoctor` locales.
