# Pruebas

Estrategia de pruebas de wasichai-learning (backend en este repositorio, frontend en `elearning-frontend`). Hoy no hay
código: todo es **planificado**, empezando por el primer incremento (catálogo público); las herramientas de Wasichai
citadas están verificadas en `wasichai/wasichai@a9ad74d56338:docs/modules/testing.md` y
[`docs/research/reference-repositories.md`](../research/reference-repositories.md). Las pruebas de autorización
negativa obligatorias están en [`docs/security/README.md`](../security/README.md#9-pruebas-de-autorización-negativa).

El proceso es TDD manual (rojo → verde → refactor) y una fase no se da por completa por crear archivos: las
comprobaciones se ejecutan de verdad ([ADR-0001](../adr/0001-proceso-de-ingenieria-sin-superpowers.md)).

## Niveles

| Nivel             | Qué cubre                                                                    | Herramientas                                 | Comando                     |
| ----------------- | ---------------------------------------------------------------------------- | -------------------------------------------- | --------------------------- |
| Dominio           | Carga y créditos (`workload`), filtros, transiciones, finalización, progreso | JUnit 5 + AssertJ                            | `./gradlew build`           |
| Integración       | Contratos, permisos, aislamiento, migraciones, guards, idempotencia          | `WasichaiIntegrationTest`, `postgres:18`     | `./gradlew integrationTest` |
| Aprovisionamiento | `model/apply.py`: validación del modelo, idempotencia                        | `unittest` (biblioteca estándar)             | `python3 -m unittest`       |
| Arquitectura      | Fronteras del monolito                                                       | Escaneo de fuentes con AssertJ               | `./gradlew build`           |
| Frontend          | Componentes, formularios, rutas y permisos, registro, estados, accesibilidad | Vitest, Testing Library, `@wasichai/testing` | `yarn test`                 |
| E2E               | Flujos de usuario en navegador                                               | Playwright, API simulada o backend real      | `yarn e2e`                  |

Como en Wasichai, no se usan MockK, Kotest ni ArchUnit; la prueba de arquitectura sigue el patrón de su
`CoreArchitectureTest`. La integración usa PostgreSQL real con Testcontainers (`WasichaiTestDatabase`).

### Integración

- La prueba aprovisiona el modelo con el **mismo** script que se usa en local (`model/apply.py` contra la aplicación
  de prueba), como hace `SgspeApiTest` en sgspe. Si el modelo no se aplica, la prueba falla.
- `WasichaiIntegrationTest` arranca con `wasichai.seed.dev=true`, un secreto JWT fijo y el servidor en `127.0.0.1`.
  `bearer()` entra como el administrador semilla; `bearer(email, password)` como otro usuario, que es lo que usan las
  pruebas de permisos (nunca `ADMIN` como sujeto de una prueba negativa).
- La base se comparte en toda la suite: cada prueba crea sus propios datos con `uniqueName(...)` y no depende del orden.
- No se usa `@ActiveProfiles("test")`: un perfil llamado `test` puede desactivar beans de librerías (Embabel, entre
  otras), según advierte `wasichai-test`.
- Sin Docker, `WASICHAI_TEST_DB_HOST` y el resto de `WASICHAI_TEST_DB_*` apuntan a una base externa cuyo nombre debe
  terminar en `_test`; la suite la vacía al empezar.
- Cobertura del primer incremento: borrador invisible por slug (`404`), filtros y orden, otra organización invisible,
  `/api/lms/**` privado → `401`, escritura anónima en la ruta pública rechazada.

### Doble HTTP de los proveedores de IA

Para probar la canalización con Embabel sin claves reales ni red ([ADR-0033](../adr/0033-fase-5-ia.md) §11). Es un
WireMock (`org.wiremock:wiremock-standalone`, la variante que sombrea su Jetty: la aplicación es Netty) en `127.0.0.1` y
puerto dinámico, uno por JVM, que hace de Anthropic (`POST /v1/messages`) y de OpenAI (Responses API,
`POST /v1/responses`). Vive en `src/test/kotlin/elearning/ai/ProviderDouble.kt`:

- **`ProviderDoubleApiTest`** es la base: extiende `LmsApiTest`, arranca la aplicación con los dos proveedores (claves
  ficticias por `@TestPropertySource`, porque `AiProviderGate` las mira antes de que Spring aplique
  `@DynamicPropertySource`; `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL` y las `base-url` de Embabel por
  `@DynamicPropertySource`, que gana a las del entorno de quien corre la suite) y trae una tarifa para cada modelo de
  extracción. Todas las clases que la extienden comparten contexto.
- **Respuestas** en `src/test/resources/ai/anthropic/` y `ai/openai/`: `messages-outline.json` y `responses-outline.json`
  (la salida tipada de ejemplo y su `usage`), `*-invalid-json.json` y `error-{400,401,429,500}.json` con el cuerpo de error de
  cada API. Se preparan con `ProviderDouble.anthropicReplies("…")`, `openAiReplies("…")`, `anthropicFails(429)` u
  `openAiFails(500)`; un caso nuevo es un archivo nuevo.
- **Lo enviado**: `anthropicRequests()` y `openAiRequests()` devuelven el cuerpo JSON de cada petición, en orden;
  `requestCount()`, todas las que llegaron. Así se comprueban el modelo, `max_tokens` y el prompt, y cuántas veces se
  reintentó.
- **Nada sin emparejar**: una respuesta solo empareja con la ruta, la clave ficticia de su proveedor (y
  `anthropic-version`); lo demás recibe el 404 de WireMock. Antes de cada prueba el doble se vacía y después
  `assertNoUnmatchedRequests()` la hace fallar si quedó una petición sin emparejar. Como las URL base apuntan al doble,
  ninguna prueba llega a `api.anthropic.com` ni a `api.openai.com`.
- **Reintentos reales**: Embabel hace 2 intentos por llamada y el SDK de cada proveedor repite 408, 409, 429 y 5xx dos
  veces más dentro de cada uno (`docs/ai` §2). Las respuestas de error llevan `Retry-After-Ms: 1` para que los SDK no
  esperen hasta 8 s; el número de peticiones es el mismo.

`ModelCallsApiTest` es el ejemplo: una salida tipada (`CourseOutline`) por cada proveedor, 400 y 401 (fallos
permanentes), 429 y 500 (pasajeros) y JSON inválido con su recuento, la petición no emparejada y el registro `AgentRun`
con el consumo del proveedor y su coste. `SourceAnalysisAnthropicApiTest` y `SourceAnalysisOpenAiApiTest` recorren un paso
entero por la API (`SourceAnalysisProviderApiTest`, una subclase por proveedor: la de OpenAI pone `lms.ai.provider=openai`),
con sus respuestas `messages-source-analysis.json` y `responses-source-analysis.json`; `CourseDesignAnthropicApiTest` y
`CourseDesignOpenAiApiTest` (`CourseDesignProviderApiTest`) encadenan el análisis y el diseño (`*-course-design.json`, y
`*-course-design-unaligned.json` para `INVALID_OUTPUT`) y comprueban el `AgentRun` entero.

`ProviderKeysApiTest` (RNF-SEG-09) busca las claves ficticias del doble donde no deben estar: con Embabel, Spring AI,
OkHttp y los SDK en DEBUG y la salida capturada desde antes de crear su contexto (`OutputCaptureExtension`; la prueba
comprueba que el arranque quedó dentro), un paso de prueba en la cola hace una ejecución completa y otra con 401 por
proveedor; después recorre la salida, todas las tablas de la base (`lms` y `audit_log` incluidas), los errores del
trabajo y de `AgentRun` y actuator. Su contexto es propio y se cierra al terminar, y los niveles de registro vuelven a
los de antes.

**Sin Spring, `FakeOperationContext`.** Para ver qué recibe Embabel en cada llamada sin HTTP: `FakeOperationContext` y
`FakePromptRunner` vienen en `embabel-agent-api` 1.5.4 (`com.embabel.agent.test.unit`), no hace falta otra dependencia.
`ModelCalls` acepta un proveedor de contexto en su constructor interno; con el falso, `expectResponse` fija la salida y
`llmInvocations` guarda los mensajes y las instrucciones (`interaction.promptContributors`). `SourceAnalysisTest` y `CourseDesignTest` lo usan.

**No sustituye al doble determinista** (`docs/ai` §7). El doble HTTP prueba la frontera con el proveedor: Embabel, lo que
se envía, la deserialización, los errores y el consumo de tokens. La lógica de la canalización (idempotencia, cancelación,
rechazo de salida inválida, permisos) y el desarrollo sin claves usan el determinista, que no habla HTTP ni pasa por
Embabel.

### Arquitectura

Prueba de escaneo de fuentes en `src/test` que falla si en `src/main`:

- algún archivo vive en el paquete `wasichai` o debajo;
- un paquete de capacidad importa a otro fuera del DAG permitido (`common` ← `catalog`, `course`, `workload`…;
  `workload` sin dependencias de Spring);
- aparece un literal de nombre de rol (`"ESTUDIANTE"`, `"DOCENTE"`, `"CREADOR"`, `"REVISOR"`, `"ADMIN"`) fuera de
  `model/roles.json`;
- se importa JPA, Hibernate, Spring Data o JDBC bloqueante (`java.sql`, `JdbcTemplate`).

Las fronteras exactas se fijan en [`docs/architecture/overview.md`](../architecture/overview.md).

### Frontend

- `renderWithProviders` monta los mismos proveedores que `<WasichaiApp>` (registro, cliente API, i18n, React Query,
  sesión) con `user` y `permissions` controlables: así se prueba que una ruta o acción se oculta sin permiso, sabiendo
  que el servidor decide.
- `mockFetch`/`jsonResponse` para la API; los esquemas Zod del contrato validan las respuestas simuladas.
- Cada módulo (`catalogModule`, `classroomModule`…) tiene `boundaries.test.ts`: sin URLs literales y sin importar otro
  módulo.
- Accesibilidad funcional: consultas por rol y etiqueta, navegación con teclado en los componentes propios.

### E2E

- **API simulada** en el navegador (`e2e/api.ts`, patrón de `sgspe-ui`): rápido, determinista, sin backend. Es el modo
  por defecto en CI.
- **Smoke real** con `E2E_REAL=1`: contra backend y base levantados en local, con datos de demostración.
- Capturas de pantalla para revisión visual, en escritorio y móvil.

## Comandos

```bash
# backend: ktlint + unitarias + arquitectura
GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token) ./gradlew build

# backend: integración (Docker o WASICHAI_TEST_DB_*)
GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token) ./gradlew integrationTest

# aprovisionamiento
python3 model/apply.py --validate-only
python3 -m unittest discover -s model    # ubicación exacta de las pruebas: se fija en F1

# frontend (en elearning-frontend)
yarn lint && yarn typecheck && yarn test && yarn build && yarn e2e
E2E_REAL=1 yarn e2e                       # smoke contra el backend local
```

## Regla del puerto 5432

**Ninguna prueba usa el puerto 5432**, regla heredada de Wasichai: es el puerto de cualquier PostgreSQL del equipo del
desarrollador, y una suite que vacía la base no debe poder tocarlo por accidente. Las pruebas usan Testcontainers
(puerto aleatorio) o una base externa explícita terminada en `_test`; el entorno local usa `127.0.0.1:5436`. Una
prueba de arquitectura o de configuración comprueba que `5432` no aparece en `src/test` ni en la configuración de
pruebas.

## Datos sintéticos

- Usuarios, matrículas, intentos, calificaciones y valoraciones de prueba y de demostración son inventados; ningún
  dato personal real ni credencial real.
- Los cursos de demostración (A SRTM, B SGSPE, C IA aplicada) usan títulos, resúmenes propios y referencias
  `repo@sha:ruta#ancla`, nunca texto copiado de los manuales ([licencias](../legal/licensing.md)), y se marcan como
  contenido ilustrativo.
- Tampoco se reutilizan los datos ficticios de las capturas de los manuales (RUC, DNI, razones sociales).
- La única credencial conocida es la semilla de desarrollo de Wasichai (`admin@wasichai.local` / `admin`), que existe
  solo con `wasichai.seed.dev=true`.

## Matriz de flujos E2E

Los 13 flujos de la especificación (§20) y la fase en que llega cada uno ([roadmap](../product/roadmap.md)). El flujo 0
no está en la lista de la especificación: es el del primer incremento.

| #   | Flujo                                                                                         | Fase                          | Datos necesarios                                     |
| --- | --------------------------------------------------------------------------------------------- | ----------------------------- | ---------------------------------------------------- |
| 0   | Visitante anónimo busca, filtra y abre la ficha de un curso publicado; un borrador no aparece | Primer incremento             | Cursos publicados y uno en borrador                  |
| 1   | Login de estudiante                                                                           | F2                            | Usuario ESTUDIANTE                                   |
| 2   | Matrícula en una edición                                                                      | F2                            | Edición con ventana de matrícula abierta             |
| 3   | Abrir una unidad y un recurso                                                                 | F2                            | Versión publicada con unidades, lecciones y recursos |
| 4   | Progreso persistido (salir y volver continúa donde quedó)                                     | F2                            | Matrícula activa                                     |
| 5   | Rendir una evaluación                                                                         | F3                            | Evaluación con instantánea de versión                |
| 6   | Ver resultado y retroalimentación                                                             | F3                            | Intento calificado                                   |
| 7   | Docente ve solo lo permitido (sus ediciones, su alumnado)                                     | F3                            | Dos ediciones con docentes distintos                 |
| 8   | Crear y editar un borrador                                                                    | F4                            | Usuario CREADOR                                      |
| 9   | Enviar a revisión                                                                             | F4                            | Borrador válido                                      |
| 10  | Revisar, pedir cambios y aprobar                                                              | F4                            | REVISOR asignado distinto del autor                  |
| 11  | Publicar                                                                                      | F4                            | Versión aprobada, usuario con `PUBLICAR`             |
| 12  | Lo publicado es visible al alumno sin alterar lo que ya consumía                              | F4                            | Matrícula sobre la versión anterior                  |
| 13  | Sin permiso no puede (ruta oculta y acción rechazada por el servidor)                         | Primer incremento y cada fase | Usuarios de un rol, otra organización                |

F5 añade un flujo propio con el doble determinista (brief → borrador validado → revisión → publicación tras
aprobación) y F6 la regresión completa con accesibilidad y capturas.
