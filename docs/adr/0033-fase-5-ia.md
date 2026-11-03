# ADR-0033: Fase 5 — Embabel 1.5.4, proveedores Anthropic y OpenAI, límites, trabajos y `AgentRun`

**Estado**: aceptada · 2026-11-03 · precisa [ADR-0018](0018-fase-4-estudio-revision-e-importador.md) §4 y
[ADR-0022](0022-limites-y-ciclo-de-vida-de-los-trabajos.md) (la cola gana un estado y un tipo de usuario más)

## Contexto

La Fase 5 ([roadmap](../product/roadmap.md#f5--ia-y-multimedia), épica [E32 #271](https://github.com/wasichai/elearning-backend/issues/271))
produce borradores con IA que después revisan y publican personas. [`docs/ai/README.md`](../ai/README.md) dejó el diseño
con tres cosas abiertas «a confirmar con un ADR en F5»: la versión de Embabel, dónde viven los trabajos (`lms.ai_job`) y
dónde vive `AgentRun`. La dirección decidió el 2026-11-03: proveedores **Anthropic y OpenAI**; mientras no haya claves,
un **doble HTTP** (WireMock) de sus API; Embabel en su **última versión**.

Hechos consultados el 2026-11-03 (fuentes al final; lo no comprobado contra el código se marca «a verificar»):

- **Embabel 1.5.4** (release del 2026-10-28, Apache-2.0) es la última. Depende de Spring Boot 4.1.1, Spring AI 2.0.1,
  `anthropic-java` 2.40.1 y `openai-java` 4.49.0; bytecode Java 21; `embabel-agent-api` trae `spring-webflux`, no MVC.
  Wasichai 1.8.0, que ya consumimos, la usa con Boot 4.1.2 y Kotlin 2.4.21: no hay incompatibilidad con este repositorio
  (Boot 4.1.2, JDK 25, WebFlux).
- **1.5.3 no sirve**: no conoce Claude 5.x ni GPT-6 y Claude 5 responde 400 ([embabel-agent#2172](https://github.com/embabel/embabel-agent/issues/2172)).
  1.5.4 añade `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5` y `gpt-6-*`, y pasa la URL base también al
  cliente asíncrono ([#2112](https://github.com/embabel/embabel-agent/issues/2112)), sin lo cual el doble HTTP no
  interceptaría todas las llamadas. `docs/ai` §2 y RF-IA-08 citaban 1.5.3 como «verificada»; este ADR lo corrige.
- **OpenAI va por la Responses API** (`/v1/responses`); Anthropic, por `/v1/messages`.
- **Los starters de proveedor no arrancan sin clave.** El `EmbabelGate` de Wasichai solo cubre Anthropic y forma parte de
  `wasichai-agent`, que no usamos (ver §8).
- **Embabel reintenta 10 veces** por defecto dentro de cada llamada; con la cola de ADR-0018 encima, un fallo costaría
  hasta 10 × `max_intentos` llamadas pagadas.
- `ANTHROPIC_BASE_URL` y `OPENAI_BASE_URL` tienen **precedencia** sobre las propiedades de Spring: un despliegue que las
  tenga puestas en el entorno ignora la URL de `application.yaml`.
- La cola `lms.trabajo` (ADR-0018 §4, ADR-0022) ya ofrece idempotencia por clave, tope por persona, fallo permanente,
  recomprobación de permisos al ejecutar y retención. Sus estados son `QUEUED`, `RUNNING`, `DONE` y `FAILED`
  (`V9__trabajos.sql`); no hay cancelación.
- Las tablas fijas de `lms` ya guardan JSON en `jsonb` (`lms.trabajo.datos`, `resultado`); `AgentRun` necesita JSON
  (entradas, salida, validaciones) que un Custom Object hoy no expresa (ADR-0004).

## Decisión

1. **Embabel 1.5.4.** Se añaden `embabel-agent-starter`, `-starter-anthropic` y `-starter-openai` 1.5.4 a
   `gradle/libs.versions.toml`, alineados con Wasichai 1.8.0. Spring AI, `anthropic-java` y `openai-java` **no se fijan
   por separado**: llegan con Embabel. Subir Embabel es una decisión con su prueba, no un cambio de versión suelto. Si el
   BOM de Wasichai ya gestiona la versión, se usa la del BOM (a verificar en E32.2).
2. **Dos proveedores, ambos opcionales; Anthropic por defecto.** Anthropic por `/v1/messages`, OpenAI por la Responses
   API. Cada uno se activa solo si tiene clave (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`). Una **compuerta propia**
   (`EnvironmentPostProcessor` en `elearning.ai`) excluye la autoconfiguración del proveedor sin clave y la de la
   plataforma de Embabel si no queda ninguno; entonces la canalización informa «sin proveedor» y usa el doble determinista
   (`docs/ai` §7). Con las dos claves, el proveedor por defecto es Anthropic (`LMS_AI_PROVIDER`).
3. **Modelo por paso, como configuración.** Ningún nombre de modelo en el código. Valores propuestos:

   | Paso                                               | Anthropic           | OpenAI        |
   | -------------------------------------------------- | ------------------- | ------------- |
   | Extracción (análisis de fuentes)                   | `claude-haiku-5-5`  | `gpt-6-luna`  |
   | Diseño pedagógico, borrador, guion, opinión        | `claude-sonnet-5-5` | `gpt-6.1-sol` |
   | Referencia en la comparación de modelos (RF-IA-09) | `claude-opus-5-5`   | `gpt-6-astra` |

   Los de Anthropic están en el catálogo de 1.5.4; los de OpenAI salen de la página de modelos de OpenAI y su presencia
   en el catálogo de 1.5.4 (sobre todo `gpt-6.1-sol`, que no sigue el patrón `gpt-6-*`) es **a verificar en E32.2**. Un
   modelo desconocido por defecto o en un paso **falla al arrancar** con un mensaje claro, no en la primera ejecución.

4. **Uso mínimo de Embabel: una llamada tipada por paso.** Cada paso llama a `PromptRunner.createObject` con su tipo Kotlin
   de salida desde `elearning.ai`. Sin planificador GOAP, sin herramientas y con el **cliente MCP desactivado**: el modelo
   solo recibe las entradas de su ejecución (`docs/ai` §6, RF-IA-06). La interfaz pequeña por paso de `docs/ai` §2
   (`SourceAnalyzer`, `CourseDesigner`…) tiene dos implementaciones, Embabel y el doble determinista. `ArchitectureTest`
   impide importar `com.embabel` fuera de `elearning.ai`. El nombre exacto de la API es a verificar contra el código de
   1.5.4 en E32.2; si no existe tal cual, se usa su equivalente tipado y se documenta en `docs/ai` §2.
5. **Trabajos en `lms.trabajo`, no en una tabla nueva.** `lms.ai_job` no se crea: cada paso es un `JobHandler` de la cola
   de ADR-0018/0022, con su clave de idempotencia (hash de paso, entradas, hashes de fuentes, versiones de instrucciones y
   plantilla y modelo), su tope por persona y la recomprobación de permisos al ejecutar. La cola gana el estado
   **`CANCELLED`**: cancelar lo marca y el manejador lo comprueba entre llamadas al modelo; un trabajo cancelado no deja
   borrador. Corre en un dispatcher acotado, nunca en el event loop.
6. **Un solo nivel de reintento.** El reintento interno de Embabel baja a **1–2 intentos** por proveedor
   (`max-attempts`; nombre exacto de la propiedad a verificar en E32.2) y la política vive en la cola: 429 y 5xx son
   pasajeros (espera creciente, tope de la cola); 400, 401, salida que no cumple el esquema y presupuesto agotado son
   `PermanentJobFailure` (ADR-0022 §5).
7. **`AgentRun` es la tabla fija `lms.ejecucion_agente`** (`ModuleMigration` orden 200, como el resto de `lms`), con los
   campos de `docs/ai` §4 y `generator` (`MODEL` o `DETERMINISTIC`); `inputs`, `output` y `validations` en `jsonb`. Una
   fila por ejecución de un paso, ligada a su trabajo (`trabajo_id`). Solo inserción salvo el cierre de la propia ejecución
   y las intervenciones humanas, que se añaden. Se lee por API propia bajo `/api/lms/ai/**` con el mismo alcance que el
   curso. A diferencia de `lms.trabajo`, **no se purga** con `lms.jobs.retention`: es la trazabilidad de lo generado
   (RF-IA-03) y su retención se define en F6. El nombre Kotlin no es `AgentRun`, que ya existe en `wasichai-agent`.
8. **Sin `wasichai-agent`.** La canalización usa Embabel directamente; el asistente de preguntas de Wasichai sigue siendo
   opcional y queda fuera de E32–E38. `WASICHAI_AGENT_ENABLED` solo importa si algún día se añade el asistente.
9. **Límites.** `max_tokens` por paso (configurable, siempre enviado al proveedor); **concurrencia por organización**
   (pasos en `RUNNING` del tipo IA); **presupuesto por curso** (suma de `coste` de sus `AgentRun` con la tarifa
   configurada). Superar concurrencia o presupuesto al encolar es `429` con `Retry-After` (`RetryLaterException`, como
   ADR-0022 §2); el presupuesto se vuelve a mirar antes de cada llamada. Ninguna ejecución itera sin límite: un paso, una
   llamada, más los reintentos de corrección acotados de `docs/ai` §5.
10. **Permisos `GENERAR` y `VALIDAR`.** Acciones declaradas de Wasichai (ADR-042 de Wasichai) comprobadas con
    `CurrentUser.requirePermission`; `APROBAR` y `PUBLICAR` siguen siendo las de F4 y ningún trabajo de IA las tiene.
    Sin comparar nombres de rol.
11. **Pruebas sin proveedor real.** Dos dobles con usos distintos: el **determinista** (sin red) para la lógica de la
    canalización y el desarrollo sin claves; el **doble HTTP** (WireMock, puerto dinámico, `ANTHROPIC_BASE_URL` y
    `OPENAI_BASE_URL` apuntando a él) para probar Embabel, la deserialización, los errores 400/429/500 y el uso de tokens.
    Una petición no emparejada hace fallar la prueba. **Ninguna prueba de CI llama a `api.anthropic.com` ni a
    `api.openai.com`**; la comparación de modelos con proveedores reales es una tarea a mano fuera de `build` (E35).
12. **Claves.** Solo en el entorno del backend; nunca en registros, `AgentRun`, `audit_log`, errores ni actuator
    (RNF-SEG-09). Una prueba con claves centinela y los registros en DEBUG lo comprueba (E32.5).

El vídeo y su renderizador (Remotion o alternativa) **no** se deciden aquí: dependen de la licencia de Remotion
([#267](https://github.com/wasichai/elearning-backend/issues/267)) y tendrán su propio ADR (E37.1, [#296](https://github.com/wasichai/elearning-backend/issues/296)).

## Alternativas consideradas

- **Quedarse en Embabel 1.5.3.** Rechazada: no conoce los modelos actuales y falla con Claude 5.
- **Spring AI `ChatClient` directo.** Sería una dependencia menos, pero repetiría lo que Embabel ya da (salida tipada,
  catálogo de modelos, dos proveedores tras una interfaz) y RF-IA-08 pide Embabel. Queda como salida documentada si
  algo de Embabel no fuera compatible (`docs/ai` §2).
- **SDK oficiales (`anthropic-java`, `openai-java`) directos.** Rechazada: dos clientes, dos formatos de salida estructurada
  y dos lógicas de reintento que mantener; Embabel ya los envuelve.
- **Tabla propia `lms.ai_job`** (como decía `docs/ai` §5). Rechazada: duplicaría la cola, su idempotencia, su tope y su
  retención (ADR-0018/0022). Basta un estado más.
- **`AgentRun` como Custom Object** (como esbozaba `domain-model.md`). Rechazada: sus campos son JSON y el registro no se
  edita por formularios; ADR-0004 manda a tabla fija.
- **Usar el planificador y herramientas de Embabel.** Rechazada: cada paso tiene una entrada y una salida conocidas; un
  agente que planifica o llama herramientas abre la puerta a la inyección de instrucciones (RF-IA-06) sin aportar nada.
- **Solo Anthropic.** Rechazada por la dirección: dos proveedores permiten comparar modelos (RF-IA-09) y no depender de uno.

## Consecuencias

- `lms.trabajo` cambia su restricción de estado (migración nueva) y su índice parcial sigue cubriendo solo `QUEUED` y
  `RUNNING`: un trabajo cancelado libera su clave como uno terminado.
- Sin claves, todo arranca y la suite pasa (RF-IA-08). Con una sola clave, solo existen los modelos de ese proveedor y un
  paso configurado con un modelo del otro falla al arrancar.
- Un despliegue con `ANTHROPIC_BASE_URL`/`OPENAI_BASE_URL` en su entorno manda las llamadas ahí aunque la configuración
  diga otra cosa; la guía de operación lo avisa.
- `docs/ai`, `requirements.md` (RF-IA-08), `domain-model.md`, `docs/operations` y el roadmap se alinean con este ADR en
  el mismo cambio. `docs/research/reference-repositories.md` sigue registrando lo leído de 1.5.3; los hechos de 1.5.4 son
  los de este ADR.
- Los pasos se implementan en las épicas E32–E38 ([roadmap](../product/roadmap.md#f5--ia-y-multimedia)).

## Fuentes

Consultadas el 2026-11-03 (hallazgos de la épica [#271](https://github.com/wasichai/elearning-backend/issues/271)):

- [Embabel v1.5.4, notas de la versión](https://github.com/embabel/embabel-agent/releases/tag/v1.5.4) y
  [diferencias v1.5.3…v1.5.4](https://github.com/embabel/embabel-agent/compare/v1.5.3...v1.5.4)
- [Modelos de Claude](https://platform.claude.com/docs/en/about-claude/models/overview)
- [Modelos de OpenAI](https://developers.openai.com/api/docs/models)
