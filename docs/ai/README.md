# IA y multimedia (Fase 5)

**Estado: planificado (F5). Nada de esto está implementado.** Este documento fija el diseño para que F5 empiece con
decisiones tomadas y riesgos conocidos; las de versión, proveedores, trabajos, límites y `AgentRun` están en
[ADR-0033](../adr/0033-fase-5-ia.md). Requisitos de origen: secciones 10, 11 y 12 de la especificación, resumidas en
[`docs/product/requirements.md`](../product/requirements.md); fase y criterio de aceptación en
[`docs/product/roadmap.md`](../product/roadmap.md). Amenazas en [`docs/security/README.md`](../security/README.md#45-agentes-ia-f5).

Principio: la IA prepara borradores y comprobaciones; **personas** aprueban y publican. Una canalización con pasos
explícitos, no agentes sueltos. Lo publicado no depende del proveedor de IA.

## 1. Canalización editorial

Diez pasos de la especificación. «Embabel» significa que el paso llama a un modelo a través de Embabel; «código»,
que es lógica determinista del módulo `elearning.ai` o de `authoring`; «persona», una acción humana con permiso propio.

1. **Brief** — temática, público, objetivos, duración, idioma, nivel, expertos y fuentes autorizadas. _Persona_
   (CREADOR) con formulario validado → `Brief`. Fuentes solo de la lista permitida o de paquetes importados
   ([importador](../security/README.md#46-importador-asciidoc-f4)). Implementado en E33.1 ([#278](https://github.com/wasichai/elearning-backend/issues/278)):
   Custom Object `brief` del curso y `BriefGuard` ([modelo](../architecture/domain-model.md)); se guarda a medias en
   `BORRADOR` y solo pasa a `LISTO` completo, que es lo que consumirán los pasos.
2. **Análisis de fuentes con procedencia** — _código_ (importador: grafo `include::`, secciones, anclas) + _Embabel_
   (extracción de conceptos y procedimientos) → `SourceAnalysis`, cada elemento ligado a `repo@sha:ruta#ancla`. Lo que
   no tiene procedencia se descarta. Implementado en E33.4 ([#281](https://github.com/wasichai/elearning-backend/issues/281)):
   `POST /api/lms/ai/source-analysis` con un brief `LISTO`; es el paso de referencia del patrón de §5.
3. **Diseño pedagógico** — resultados medibles, temario, secuencia, distribución horaria, actividades, evaluaciones
   alineadas, lecturas y criterios de aprobación. _Embabel_ → `CourseDesign`. Horas y créditos los calcula el dominio
   `workload` (código), no el modelo. Implementado en E33.5 ([#282](https://github.com/wasichai/elearning-backend/issues/282)):
   `POST /api/lms/ai/course-design` con la ejecución `SUCCEEDED` de un análisis; ver §5.
4. **Borrador con plantillas versionadas** — _Embabel_ → `LessonDraft` en la fuente canónica (AsciiDoc). Plantilla y
   versión quedan en `AgentRun`. Implementado en E33.6 ([#283](https://github.com/wasichai/elearning-backend/issues/283)):
   `POST /api/lms/ai/lesson-draft` con la ejecución `SUCCEEDED` de un diseño deja una `version_curso` `BORRADOR` a nombre de
   quien lo pidió; ver §5.
5. **Multimedia** — guiones, diapositivas, narración, subtítulos y Remotion. _Embabel_ (guion y estructura) + _código_
   (render) → `VideoScript`, `SlideDeck`. Un vídeo solo está «terminado» si se renderizó y validó (§12). El guion está
   implementado en E36.1 ([#293](https://github.com/wasichai/elearning-backend/issues/293)): `POST /api/lms/ai/scripts` con un
   brief `LISTO` y una versión de recurso `VIDEO` en borrador; se guarda como versión inmutable (`guion_video`) con el guion
   del render de ADR-0040, sus subtítulos WebVTT y su transcripción, y cada sección traza a las secciones de la fuente.
6. **Validaciones automáticas** — _código_: estructura, referencias, enlaces, recursos, duplicidad (hashes),
   accesibilidad y metadatos → `ValidationReport`. _Embabel_ solo aporta una **opinión** sobre la coherencia
   contenido-evaluación, que nunca aprueba ni bloquea por sí sola. La parte de código está implementada en E34.1
   ([#286](https://github.com/wasichai/elearning-backend/issues/286)) y la opinión en E34.2
   ([#287](https://github.com/wasichai/elearning-backend/issues/287)): ver §13.
7. **Revisión humana** — aprobar, pedir cambios, comentar fragmentos, rechazar. _Persona_ (REVISOR asignado) →
   `ContentReview` y `ReviewComment` ligados a la versión, en la bandeja del revisor.
8. **Correcciones** — _persona_ (CREADOR), con ayuda opcional de Embabel → nueva versión. No borra versiones anteriores
   ni toca las decisiones del revisor (`appendOnly`).
9. **Aprobación final** — _persona_ con `APROBAR`, distinta del autor → `APROBADA`
   ([seguridad §3](../security/README.md#3-separación-de-deberes)).
10. **Publicación** — _persona_ con `PUBLICAR` → versión `PUBLICADA` inmutable. Nunca la hace un agente ni un trabajo
    automático.

Los pasos 7 a 10 son el flujo editorial de F4 (`wasichai-workflow`); F5 solo añade los pasos que producen borradores y
validaciones. Extraer de una fuente, diseñar pedagógicamente y aprobar son tres cosas distintas
([ADR-0010](../adr/0010-importacion-asciidoc-por-etapas.md)).

## 2. Embabel y Spring AI

Decisión en [ADR-0033](../adr/0033-fase-5-ia.md) §1–§4.

- **Versión**: Embabel Agent **1.5.4** (Apache-2.0, release del 2026-10-28), la misma que Wasichai 1.8.0. Depende de
  Spring Boot 4.1.1, Spring AI 2.0.1, `anthropic-java` 2.40.1 y `openai-java` 4.49.0, con bytecode Java 21 y
  `spring-webflux` (no MVC): compatible con este repositorio. Spring AI y los SDK no se fijan por separado. La 1.5.3,
  que se había leído en [referencias](../research/reference-repositories.md#1-versiones-reales), no conoce Claude 5 ni
  GPT-6 y falla con 400 contra Claude 5.
- **Proveedores**: Anthropic (`/v1/messages`) y OpenAI (Responses API, `/v1/responses`), ambos opcionales; cada uno se
  activa solo con su clave, mediante una compuerta propia en `elearning.ai` (los starters no arrancan sin clave y el
  `EmbabelGate` de Wasichai solo cubre Anthropic). Con los dos, Anthropic es el de por defecto. Hecho en E32.2
  ([#273](https://github.com/wasichai/elearning-backend/issues/273)): `AiProviderGate` deja fuera la autoconfiguración de
  cada proveedor sin clave y, sin ninguno, las cinco de la plataforma; `AiStatus` dice al arrancar qué proveedor quedó
  (ninguno: doble determinista) y comprueba los modelos y `max-attempts`. Verificado contra 1.5.4: el BOM de Wasichai no
  gestiona Embabel (la versión va en `gradle/libs.versions.toml`); las propiedades son
  `embabel.agent.platform.models.{anthropic,openai}.{api-key,base-url,max-attempts}` y `embabel.models.default-llm`; Embabel
  trae el cliente MCP de Spring AI, que se apaga con `spring.ai.mcp.client.enabled=false` y
  `spring.ai.mcp.client.annotation-scanner.enabled=false`; con Embabel en el classpath no llega Spring MVC ni Tomcat (la
  tarea `noServletStack` de `check` lo vigila).
- **Uso selectivo**: Embabel solo en los pasos 2–5 (y la opinión del 6), con **una llamada tipada por paso**
  (`PromptRunner.createObject`), sin planificador, sin herramientas y con el cliente MCP desactivado. Verificado contra
  1.5.4 en E32.3 ([#274](https://github.com/wasichai/elearning-backend/issues/274)): la llamada es
  `Ai.withLlm(LlmOptions.withModel(m).withMaxTokens(n)).createObject(prompt, type)`, y `elearning.ai.ModelCalls` la hace
  sobre un `ExecutingOperationContext` propio por llamada (un bean prototipo de Embabel), cuyo `agentProcess.usage()` da
  los tokens que informó el proveedor; esos van a `AgentRun`. OpenAI va por la Responses API (`gpt-6-*` se declara así en
  el catálogo de 1.5.4) y recibe `max_output_tokens` y `text.format` `json_schema`; Anthropic recibe `max_tokens` y el
  esquema JSON en `system`. Desde E33.4, `ModelCalls.createObject` recibe aparte las instrucciones de sistema
  (`withSystemPrompt`, que Embabel junta con el esquema en `system` de Anthropic y en `instructions` de la Responses API) y
  el mensaje de usuario, donde va el texto no confiable (§6). Sin abstracción multi-proveedor propia: una interfaz interna pequeña por paso
  (`SourceAnalyzer`, `CourseDesigner`, `LessonDrafter`, `ScriptWriter`) que tiene dos implementaciones, Embabel y el
  doble determinista (§7). Sirve para probar y para comparar modelos (§9). Si algo no fuera compatible, se documenta
  aquí y se entrega la alternativa con Spring AI `ChatClient` directo.
- **Llamadas bloqueantes**: la canalización corre siempre en un trabajo (§5), en un dispatcher acotado, nunca en el
  event loop de WebFlux.
- **Reintentos**: el interno de Embabel (10 por defecto) baja a 1–2; la política de reintentos es la de la cola (§5).
  Medido con el doble HTTP en E32.3: en 1.5.4 el reintento que envuelve cada llamada tipada, con los dos proveedores, es
  el de `embabel.agent.platform.llm-operations.data-binding.max-attempts` (repite cualquier fallo, un 400 incluido; el
  segundo intento lleva el error de validación y es la corrección acotada de §5). El `max-attempts` de cada proveedor solo
  cuenta en el camino Chat Completions de OpenAI, que la canalización no usa. Los dos quedan en 2 y `AiStatus` impide
  arrancar con más. Debajo, `anthropic-java` y `openai-java` repiten 408, 409, 429 y 5xx dos veces dentro de cada intento
  (respetan `Retry-After`) y Embabel 1.5.4 no deja configurarlo para Anthropic: un 429 son 2 × 3 = 6 peticiones antes de
  volver a la cola. `ModelCalls` traduce a lo que espera `AiStepRunner` (§5): un estado HTTP del proveedor es
  `AiProviderFailure` (408, 429 y 5xx pasajeros, que la cola reintenta; 400, 401, 403, 404, 409, 422… permanentes) y la
  salida que no se deserializa tras la corrección es `AiInvalidOutput`, con el consumo de los dos intentos
  (`INVALID_OUTPUT`); un fallo de red sale tal cual y la cola lo reintenta.
- **Perfiles**: `wasichai-test` advierte que un perfil llamado `test` puede desactivar los agentes de Embabel; las
  pruebas no lo usan.
- **Catálogo de modelos**: 1.5.4 conoce `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5` y `gpt-6-*`. El
  modelo es configuración por paso, no código; propuesta: `claude-haiku-5-5`/`gpt-6-luna` para extracción,
  `claude-sonnet-5-5`/`gpt-6.1-sol` para diseño, borrador y guion, `claude-opus-5-5`/`gpt-6-astra` como referencia de
  comparación; los seis están en el catálogo de 1.5.4 (verificado en E32.2, `gpt-6.1-sol` incluido). Viven en
  `lms.ai.models.{anthropic,openai}` (`default`, `extraction`, `design`, `draft`, `script`, `review`, `reference`). Un
  modelo desconocido, el de por defecto incluido, falla al arrancar con un mensaje que nombra la propiedad y los modelos
  que sí hay.

## 3. Relación con `wasichai-agent`

`wasichai-agent` es otra cosa: un **asistente de preguntas** de solo lectura (`POST /api/agent/ask`, nueve
herramientas que leen como el llamante, sin herramientas de escritura; ADR-014/015/056 de Wasichai). No es la
canalización editorial y no se modifica.

- **El asistente** — opcional, para preguntas de docentes y administradores sobre sus cursos. Respeta
  `RecordReadScope`, así que no ve más que el usuario.
- **`AgentAccessPolicy`** — habilita el asistente por organización o permiso; desactivado para ESTUDIANTE salvo
  decisión explícita.
- **`AgentResultFilter`** — quita o seudonimiza datos personales de estudiantes antes de que salgan al proveedor; si un
  filtro falla, la ejecución se cierra sin enviar nada (verificado).
- **`AgentRunListener`** — registra cada pregunta del asistente como `AgentRun` de tipo `ASSISTANT` (pregunta,
  respuesta, `AgentUsage`, error).
- **`EmbabelGate`** — precedente: sin clave, Embabel queda fuera del contexto y la aplicación arranca. La canalización
  sigue el mismo criterio y usa el doble determinista (§7).

La canalización editorial usa Embabel directamente desde `elearning.ai`, con su configuración de modelo propia; no
depende de `wasichai-agent` ni reutiliza las herramientas del asistente, que queda fuera de F5 (ADR-0033 §8). Nombre: `wasichai.agent.AgentRun` ya existe como clase interna de una
pregunta; la entidad de este proyecto lleva otro nombre técnico en Kotlin (se fija en
[`domain-model.md`](../architecture/domain-model.md)) para no confundirlas.

## 4. Registro `AgentRun`

Una fila por ejecución de un paso (o por pregunta del asistente). Campos exigidos por la especificación:

| Campo                                   | Contenido                                                                                               |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `id`                                    | UUID estable                                                                                            |
| `kind`                                  | Paso (`SOURCE_ANALYSIS`, `COURSE_DESIGN`, `LESSON_DRAFT`, `SCRIPT`, `CONSISTENCY_REVIEW`, `ASSISTANT`…) |
| `requested_by`                          | Usuario que lo pidió (el trabajo corre como plataforma, así que se guarda aquí)                         |
| `course_id`, `unit_id`, `version_id`    | Curso, unidad y versión afectados                                                                       |
| `objective`, `inputs`                   | Objetivo declarado y entradas exactas (brief, ids de fuentes)                                           |
| `sources`                               | Referencias `repo@sha:ruta#ancla` y hashes de su contenido                                              |
| `provider`, `model`, `model_params`     | Proveedor y modelo (sin claves ni cabeceras)                                                            |
| `prompt_version`, `template_version`    | Versiones de instrucciones y plantillas                                                                 |
| `output`                                | Resultado estructurado (JSON del esquema del paso)                                                      |
| `validations`                           | Informe de validación (esquema, referencias, reglas)                                                    |
| `status`, `error`                       | `PENDING`, `RUNNING`, `SUCCEEDED`, `INVALID_OUTPUT`, `FAILED`, `CANCELLED`; error legible               |
| `input_tokens`, `output_tokens`, `cost` | Consumo y coste estimado con la tarifa configurada y su fecha                                           |
| `started_at`, `finished_at`             | Duración                                                                                                |
| `human_interventions`                   | Aceptaciones, ediciones y rechazos posteriores, con usuario y hora                                      |
| `idempotency_key`, `attempt`            | Clave del trabajo y número de reintento                                                                 |
| `generator`                             | `MODEL` o `DETERMINISTIC` (§7)                                                                          |

Almacenamiento ([ADR-0033](../adr/0033-fase-5-ia.md) §7): tabla fija **`lms.ejecucion_agente`** (`ModuleMigration`
orden 200), porque `inputs`, `output` y `validations` son JSON (`jsonb`) y el tipo `JSON` de Wasichai aún no existe
([ADR-0004](../adr/0004-modelo-hibrido-custom-objects-y-tablas-fijas.md)). Cada fila apunta a su trabajo de `lms.trabajo`.
Lectura por API propia bajo `/api/lms/ai/**` con el mismo alcance que el curso. Las filas no se editan (salvo el cierre
de la ejecución y las intervenciones, que se añaden) ni se purgan con los trabajos; la retención se define en F6.
Implementado en E32.4 ([#275](https://github.com/wasichai/elearning-backend/issues/275)): `AgentExecutionLog` (`elearning.ai`)
abre, cierra y añade intervenciones; el coste sale de `lms.ai.rates` y la lectura es `GET /api/lms/ai/runs`
([docs/api](../api/lms.md#registro-de-ejecuciones-de-ia-f5-adr-0033-7)).

## 5. Esquemas tipados y trabajos

**Salida tipada.** Cada paso declara su tipo Kotlin y su esquema JSON. La salida del modelo se deserializa y valida
(esquema + reglas de dominio: horas positivas, cada evaluación alineada con un resultado, cada afirmación con
procedencia). Inválida → `INVALID_OUTPUT`, nunca se guarda como borrador; se permite un número limitado de
reintentos de corrección (configurable) y después queda para revisión humana.

**Trabajos persistentes, idempotentes y cancelables** ([ADR-0033](../adr/0033-fase-5-ia.md) §5, §6 y §9). La cola
de `wasichai-automation` solo ejecuta las acciones de sus reglas y no ofrece un SPI para acciones propias
(`wasichai/wasichai:docs/modules/automation.md`). Cada paso es un `JobHandler` de la cola propia **`lms.trabajo`**
([ADR-0018](../adr/0018-fase-4-estudio-revision-e-importador.md) §4, [ADR-0022](../adr/0022-limites-y-ciclo-de-vida-de-los-trabajos.md)),
no una tabla nueva:

- El permiso (`GENERAR`, `VALIDAR`) se comprueba **al encolar** y se vuelve a mirar al ejecutar (ADR-0022 §4); el
  trabajo escribe como plataforma, así que el `audit_log` lo etiqueta y `requested_by` queda en `AgentRun`.
- **Idempotencia**: clave = hash de (paso, entradas, hashes de fuentes, versión de instrucciones y plantilla, modelo).
  Un reencolado con la misma clave mientras el trabajo no termina devuelve el existente: no se paga dos veces ni se
  crea un segundo borrador.
- **Reintentos controlados**: un solo nivel. Embabel reintenta 1–2 veces; la cola reintenta 429 y 5xx con espera
  creciente y tope; 400, 401, esquema inválido y presupuesto agotado son fallo permanente (`PermanentJobFailure`).
- **Cancelación**: la cola gana el estado `CANCELLED`, comprobado entre llamadas al modelo; no deja borrador.
- **Límites**: `max_tokens` por paso, concurrencia por organización, presupuesto por curso y presupuesto de la
  organización por período (`organization-budget`, mensual o diario en UTC, entre todos sus cursos, #368: sin él, quien
  crea cursos estrena presupuesto con cada uno); superar cualquiera de los tres últimos al encolar es `429` con
  `Retry-After` (el de la organización, hasta que empieza el período siguiente) y, ya en cola, el paso falla sin reintento.
  Una ejecución cuenta en el período en que empezó; lo ya resuelto por su clave no cuenta ni gasta. Cada presupuesto es una
  consulta (`spent`, `spentSince`, con el índice de V31), ninguna sin presupuesto. Un paso es una llamada, más los
  reintentos de corrección acotados. Con presupuesto, el modelo de cada paso necesita su tarifa vigente en la moneda del
  presupuesto (`lms.ai.rates`) o la aplicación no arranca: sin tarifa su coste sería `null` y no sumaría (#302).
- Corre en un dispatcher acotado, nunca en el event loop de WebFlux.

Implementado en E33.2 ([#279](https://github.com/wasichai/elearning-backend/issues/279)): `AiStepRunner` (`elearning.ai`) es la
política común que llama el `JobHandler` de cada paso con su generador (`AiModelCall`, una llamada por intento) y su borrador;
`AiJobs` encola con la clave y los límites (`lms.ai.limits`) y lee y cancela (`GET /api/lms/ai/jobs/{id}`,
`POST /api/lms/ai/jobs/{id}/cancel`, [docs/api](../api/lms.md#trabajos-de-ia-f5-adr-0033-5-6-y-9)). La cola gana `CANCELLED`
(`V21`). Con una sola llamada por intento, «entre llamadas» es antes de llamar y al volver; los reintentos de corrección
acotados llegarán con los pasos que los usen.

**El patrón de un paso tipado** (E33.4, [#281](https://github.com/wasichai/elearning-backend/issues/281); lo siguen
`CourseDesigner` en E33.5, `LessonDrafter` en E33.6 y `ScriptWriter` en E36.1). El análisis de fuentes es la referencia, todo
en `elearning.ai`:

1. **Tipos.** La salida es una `data class` Kotlin (`SourceAnalysis`): Embabel saca de ella el esquema JSON. Lo que el
   código debe poder descartar uno a uno (la procedencia) es opcional en el tipo; lo que la hace inservible, no, y entonces
   Embabel no la deserializa. Las entradas del paso también son un tipo (`SourceAnalysisInputs`), que guardan el trabajo y
   la ejecución tal cual.
2. **Una interfaz pequeña con dos implementaciones** (`SourceAnalyzer`): la de Embabel (`ModelSourceAnalyzer`) es una
   llamada a `ModelCalls.createObject` con el modelo y `max_tokens` del trabajo, las instrucciones fijas como sistema
   (`SourceAnalysisPrompt.SYSTEM`) y el texto no confiable delimitado en el mensaje de usuario; la determinista
   (`DeterministicSourceAnalyzer`) saca una salida válida de las mismas entradas, sin red. Ninguna valida.
3. **Versiones.** Instrucciones y plantilla llevan versión (`source-analysis@1`); cambiar su texto es subirla, y entra en
   `AgentRun` y en la clave de idempotencia.
4. **Lectura antes de llamar.** El `JobHandler` del paso (`SourceAnalysisJobHandler`, tipo `ai.source_analysis`) llama a
   `AiStepRunner.run(job, read, generator)`: `read` corre tras la recomprobación de `GENERAR`, la idempotencia, la
   cancelación y el presupuesto, y devuelve el material del generador y las fuentes leídas con su hash (`AiStepInput`), que
   son las `sources` de la ejecución. Un fallo al leer no abre ejecución (no se llamó a nada): pasajero, la cola reintenta;
   si no, `PermanentJobFailure`.
5. **Comprobación por código, igual para los dos generadores** (`SourceAnalysisCheck`): un elemento que no la pasa se
   descarta y queda en `validations` con su motivo; si la salida entera no sirve, `AiInvalidOutput`, que el runner deja en
   `INVALID_OUTPUT`, sin salida, sin `executionId` en el trabajo y sin borrador.
6. **Generador.** Al encolar, `AiStepModels.choice(<clave>)` da el modelo del proveedor activo para la clave del paso en
   `lms.ai.models` y su `max_tokens` de `lms.ai.max-tokens`; sin proveedor, `null`, y el handler usa el doble. `generator`
   (`MODEL` o `DETERMINISTIC`) queda en `AgentRun` y se lee en `GET /api/lms/ai/runs`.
7. **Borrador.** Un paso que escribe registros los escribe en el `draft` del runner, en la transacción que cierra el
   trabajo. El análisis no escribe ninguno: su borrador es la salida de su ejecución `SUCCEEDED`, que el paso siguiente lee
   por el `executionId` del trabajo. Un paso cuya salida tiene que nombrar lo que crea (el borrador de lecciones, §13) usa
   `AiStepRunner.runWriting` con un `AiDraftWriter`: `prepare` corre fuera de la transacción (generar y subir archivos) y
   `write` dentro de la misma que cierra la ejecución `SUCCEEDED` (con la salida que devuelve) y el trabajo, así que no hay
   ejecución `SUCCEEDED` sin su borrador ni borrador sin ella; si escribir falla, la ejecución queda `FAILED` con lo que costó.
8. **Pruebas.** Unitarias sin Spring con el `FakeOperationContext` de Embabel (§6); de API sin claves con el doble
   determinista y contra el doble HTTP de Anthropic y de OpenAI (`SourceAnalysisApiTest`, `SourceAnalysisAnthropicApiTest`,
   `SourceAnalysisOpenAiApiTest`).

**Análisis de fuentes.** Lee cada fuente del brief (`owner/repo@sha:ruta`) y su grafo `include::` con el puerto del
importador, que solo lee la lista permitida en un commit exacto (`SourceMaterialReader`); cada sección con ancla es
`owner/repo@sha:ruta#ancla` (la traza del importador) con su texto, de la línea tras su título a la anterior al siguiente.
Una fuente de un paquete importado que no está en `lms.importer.allowed-sources` no se puede leer y el paso falla. Cada
concepto (`name`, `summary`) y procedimiento (`name`, `steps`) lleva `source`, la referencia de su sección, y `quote`, un
fragmento literal de ella (12 a 500 caracteres; los espacios no cuentan). Se descarta, con su motivo en `validations`, lo que
no tiene nombre o contenido (`BLANK`), no tiene `source` o `quote` (`NO_PROVENANCE`), cita un archivo que el paso no leyó,
otro commit incluido (`SOURCE_NOT_READ`), un ancla que no existe en ese archivo (`ANCHOR_NOT_FOUND`) o una cita que no está
en esa sección (`QUOTE_NOT_IN_SOURCE`). Más de 300 elementos o ninguno que quede es `INVALID_OUTPUT`. El doble hace un
procedimiento de cada sección con lista ordenada y un concepto de cada sección con prosa, citando su texto.

**Diseño pedagógico** (E33.5, [#282](https://github.com/wasichai/elearning-backend/issues/282)). Parte de la ejecución
`SUCCEEDED` de un análisis (su `executionId`), cuyo brief sigue `LISTO`: lee su salida antes de llamar y sus fuentes, con su
hash, son las de la ejecución. `CourseDesigner` tiene dos implementaciones, `ModelCourseDesigner` (modelo `design` y
`lms.ai.max-tokens.design`) y `DeterministicCourseDesigner`; instrucciones y plantilla `course-design@1`. El brief y el análisis
(con sus citas, texto no confiable) van delimitados en el mensaje de usuario. `CourseDesign` trae resultados medibles (`id`,
`statement`, `evidence`), unidades en orden (el temario y su secuencia, cada una una semana) con temas, resultados, actividades
(`THEORY`, `PRACTICE`, `SUPERVISED`, `AUTONOMOUS`) y lecturas, evaluaciones con los resultados que miden y criterios de
aprobación. **Horas y créditos no son del modelo**: el tipo no los tiene y lo que traiga de más se ignora; `CourseDesignCheck`
reparte las horas del brief entre las semanas y, en cada una, entre los tipos de actividad según cuántas tiene el diseño de
cada tipo (`Workload` de `workload`), y los créditos son la propuesta (`PROPUESTO`) de la política de referencia de cada
sistema. Se descarta, con su motivo en `validations`, una lectura sin `source` (`NO_PROVENANCE`) o con uno que no es de un
elemento del análisis ni de un archivo que leyó (`SOURCE_NOT_READ`) y una referencia a un resultado que no existe
(`UNKNOWN_OUTCOME`). Es `INVALID_OUTPUT` un diseño sin resultados (o con uno sin id, enunciado o evidencia, o repetido), sin
unidades o con una sin actividades, sin evaluaciones, con **una evaluación que no se alinea con ningún resultado que exista**
(`ASSESSMENT_NOT_ALIGNED`), sin criterios de aprobación, con más elementos de los que caben (50 resultados, 104 unidades, 50
evaluaciones) o con más carga por semana de la que tiene una semana. Un resultado que ninguna evaluación mide es un aviso
(`unassessedOutcomes`). El doble hace un resultado por objetivo del brief, una unidad por concepto (teoría y lectura) y por
procedimiento (práctica y ejercicio) con la lectura de su sección, y un cuestionario por resultado.

**Borrador con plantillas versionadas** (E33.6, [#283](https://github.com/wasichai/elearning-backend/issues/283)). Parte de la
ejecución `SUCCEEDED` de un diseño cuyo brief sigue `LISTO` y cuyas semanas son una duración válida (de 1 a 8, o un semestre
de 12 a 26): lee su salida y vuelve a leer las fuentes del brief en el mismo commit exacto (`SourceMaterialReader`), que son
las `sources` de la ejecución. `LessonDrafter` tiene dos implementaciones, `ModelLessonDrafter` (modelo `draft` y
`lms.ai.max-tokens.draft`, 16000) y `DeterministicLessonDrafter`; instrucciones `lesson-draft@2`. El diseño y las secciones de
las fuentes (texto no confiable) van delimitados en el mensaje de usuario. `LessonDraft` trae, por cada unidad del diseño y en
su orden, sus lecciones: título, resumen, objetivo, duración, la sección de la que sale (`source`), el cuerpo en AsciiDoc y las
verificaciones. **El modelo no escribe el documento**: `LessonTemplate` (`lesson-draft@1`, la `version_plantilla` de la
ejecución) junta título, resumen, _Objetivo_, cuerpo, _Verificación_ y fuente, en el idioma del brief, y baja a sección un
título de documento colado en el cuerpo. `LessonDraftCheck` deja `INVALID_OUTPUT`, sin versión, un borrador con otro número de
unidades que el diseño (`UNITS_MISMATCH`), una unidad sin lecciones, una lección sin título, objetivo o cuerpo (`BLANK_LESSON`)
o sin verificaciones, más de 20 lecciones por unidad o una lección de más de 100 000 caracteres; descarta, con su motivo en
`validations`, una fuente que no es la referencia de una sección leída (`NO_PROVENANCE`, `SOURCE_NOT_READ`) y una duración
fuera de 1–600 minutos (`INVALID_DURATION`). **Los enlaces nuevos se marcan para revisión**, no se quitan: cada enlace del
texto generado que no está en ninguna de las secciones leídas (`NOT_IN_SOURCES`) o que no pasa `SafeLink` (`UNSAFE`) queda
en `validations.linksToReview` y en `referencias` de la versión del recurso de su lección (`REVISAR (…): url`).

`LessonDraftWriter` escribe, como la plataforma y a nombre de quien lo pidió (`autor_id`), como la importación: una
`version_curso` **`BORRADOR`** con el número siguiente del curso, sus semanas y horas por semana (`workload`, del diseño), sus
resultados, su sistema de evaluación, sus fuentes y la plantilla con su versión (`plantilla_ia`); una unidad por unidad del
diseño y, por lección, su `leccion` (tipo `LECTURA`, con su procedencia) y un `recurso` HTML: la fuente canónica AsciiDoc en
`fuente_recurso` (RF-CON-03) y una `version_recurso` `BORRADOR` con el HTML que genera `DocumentRenderer` (Asciidoctor en modo
`secure`), **saneado con la lista blanca de ADR-0022** (`GeneratedHtml`) antes de guardarse, enlazada por `recurso_leccion`.
Nada de eso cambia de estado por sí mismo: enviarlo a revisión, aprobarlo y publicarlo son de personas, y la versión es
**generada** (§13), así que no entra en revisión sin un informe de validación al día. Tampoco entra en revisión, ni se
aprueba ni se publica, mientras alguna de esas versiones de recurso no esté `PUBLICADA` (`409` con las lecciones, #366,
[ADR-0041](../adr/0041-los-recursos-de-un-curso-se-publican-antes-que-el-curso.md)): la autora envía a revisión cada
versión de recurso, otra persona la aprueba y quien publica la publica, y después se envía el curso. Pedir otra vez el mismo borrador
devuelve su ejecución, sin otra versión. El doble hace una lección por lectura de cada unidad con el texto de su sección (o
una con el resumen y los temas si no tiene ninguna), el primer resultado de la unidad como objetivo y una verificación por
actividad.

**Las evaluaciones del diseño** ([#367](https://github.com/wasichai/elearning-backend/issues/367), instrucciones
`lesson-draft@2`). `LessonDraft` trae además una entrada por evaluación del diseño, en su orden: instrucciones, duración,
la sección de la que salen sus preguntas (`source`) y las preguntas, cada una con su tipo de la plataforma, enunciado,
puntos, opciones con `correct` y respuestas aceptadas. Qué evaluación es (título, tipo), con qué resultados se alinea y en
qué unidad va lo pone el **código** con el diseño, no el modelo: la última unidad que trabaja alguno de sus resultados (si
ninguna, la última), detrás de sus lecturas. `LessonDraftCheck` deja `INVALID_OUTPUT` otro número de evaluaciones que el
diseño (`ASSESSMENTS_MISMATCH`), una evaluación del diseño sin ningún resultado que exista (`UNALIGNED_ASSESSMENT`) o sin
preguntas, más de 30 preguntas o de 10 opciones, una pregunta sin enunciado o con una opción vacía (`BLANK_QUESTION`), de un
tipo que no se genera (`INVALID_QUESTION_TYPE`: se generan `UNICA`, `MULTIPLE`, `VERDADERO_FALSO`, `CORTA` y `ABIERTA`; no
`ARCHIVO`), con puntos fuera de (0, 100] (`INVALID_POINTS`), con opciones que no son las de su tipo (`INVALID_OPTIONS`: dos o
más y una correcta en `UNICA`, alguna en `MULTIPLE`, dos y una correcta en `VERDADERO_FALSO`, ninguna en `CORTA` ni en
`ABIERTA`, sin repetir) o una `CORTA` sin respuestas aceptadas (`MISSING_ACCEPTED_ANSWERS`). La fuente y la duración se
descartan como las de una lección y los enlaces nuevos de sus textos quedan en `linksToReview`. `LessonDraftWriter` escribe,
en la misma transacción y como la plataforma, su `leccion` `EVALUACION` (con los enunciados de sus resultados como
`objetivo`, que la alinea para `DraftValidator`, y su procedencia), su `evaluacion` (un intento, 60 %, `mostrar_soluciones`
`NUNCA`: lo ajusta quien prepara el curso), sus `pregunta` y sus `opcion`, todo en la versión `BORRADOR`. **La clave no sale
de ahí** (ADR-0019): la opción correcta y las respuestas aceptadas no se serializan en la salida ni en el informe de la
ejecución, que lee quien ve `AgentRun`. El doble hace, por resultado alineado, una `UNICA` cuya opción correcta es su
enunciado y una `ABIERTA` con su evidencia. Así un borrador generado de punta a punta ya no da `NO_ASSESSMENT` en la
validación (§13) y, sin otros errores bloqueantes, entra en revisión sin que nadie lo toque.

**La unidad de demostración** (E38.1, [#300](https://github.com/wasichai/elearning-backend/issues/300)) recorre todo esto por la
API hasta el aula: `DemoUnitFlow`, con `DemoUnitApiTest` (doble determinista) y `DemoUnitAnthropicApiTest`/`DemoUnitOpenAiApiTest`
(doble HTTP). Deja a la vista que el borrador ya trae sus evaluaciones (#367), así que su primera validación no bloquea y entra en
revisión sin que nadie lo toque, y que las versiones de recurso de las lecturas nacen en `BORRADOR` (las escribe la
plataforma, que no pasa por «una lección solo usa versiones de recurso publicadas»): se envían, aprueban y publican por la API
de registros antes de publicar la versión del curso, y hoy nada lo exige al publicarla. La ejecución con un proveedor real se
documenta en [`evaluations/demo-unit/`](evaluations/demo-unit/README.md).

## 6. Protección contra prompt injection

- El texto de manuales, fuentes y borradores es **dato no confiable**: va delimitado en el mensaje de usuario, nunca en
  las instrucciones de sistema, y las instrucciones dicen explícitamente que el contenido no da órdenes. El marcador de
  cada bloque sale del hash de su contenido, así que una fuente no puede cerrar el suyo ni abrir otro. Lo prueba
  `SourceAnalysisTest` con el `FakeOperationContext`/`FakePromptRunner` de Embabel 1.5.4: están en el propio
  `embabel-agent-api` (`com.embabel.agent.test.unit`), no en `embabel-agent-test` (que en 1.5.4 solo trae
  `EmbabelMockitoIntegrationTest`), y guardan los mensajes y las instrucciones de cada llamada; y las pruebas contra el doble
  HTTP miran `system` (Anthropic) e `instructions` (OpenAI).
- Los agentes **no** tienen acceso arbitrario a archivos, red ni ejecución de código; solo reciben las entradas de su
  ejecución. Las herramientas, si alguna, son de solo lectura y acotadas a esas entradas.
- Los agentes **no** publican, no aprueban y no escriben registros: devuelven un objeto tipado que el código valida y
  guarda como borrador.
- **Ninguna herramienta** (RF-IA-06): cada paso es una `createObject` sin herramientas ni MCP, y la petición al proveedor
  no lleva `tools` ni `tool_choice`. Si el modelo pide una, el bucle de Embabel falla en la primera
  (`embabel.agent.platform.toolloop.tool-not-found.max-retries: 0`; con el valor por defecto le contesta hasta tres veces y
  el contador es compartido entre llamadas) y `ModelCalls` la deja en `INVALID_OUTPUT`. Lo prueba
  `PromptInjectionProviderApiTest` con una fuente inyectada y respuestas obedientes (`docs/security` §4.5).
- Las referencias se verifican por código contra la fuente (la cita debe existir en `ruta#ancla` de la revisión
  indicada); los enlaces los comprueba código con la regla anti-SSRF del importador.
- La salida se trata también como no confiable: HTML saneado, sin enlaces nuevos no presentes en las fuentes salvo
  marcados para revisión.
- Ni claves ni datos de estudiantes en el contexto del modelo.
- **Claves fuera de registros y persistencia** (RNF-SEG-09, ADR-0033 §12): solo el entorno del backend las tiene. Un fallo
  del proveedor sale de `ModelCalls` con un mensaje propio que solo dice el estado (`the AI provider rejected the request
(401)`, `the AI provider failed (429)`); el del SDK, que repite el cuerpo de la respuesta (el 401 de OpenAI cita parte
  de la clave), queda en la causa y solo va al registro de la aplicación. `AgentExecutionLog` tapa además una clave
  configurada y el valor de una cabecera `Authorization`, `x-api-key` o `api-key` en cualquier texto. Actuator no publica
  `env`, `configprops` ni `heapdump` y los valores salen tapados si alguien los expusiera. Los SDK solo escriben
  cabeceras con `ANTHROPIC_LOG=debug` u `OPENAI_LOG=debug`, en `System.err` y con las de credenciales tapadas; no se
  activan en producción. `ProviderKeysApiTest` lo comprueba con los registros en DEBUG.

## 7. Doble determinista sin credenciales

Sin clave de proveedor (desarrollo, CI, colaboradores), cada interfaz del §2 usa su implementación determinista:
plantillas fijas que producen salidas válidas según el esquema a partir de las entradas (p. ej., una lección por
procedimiento con su _Objetivo_ y _Verificación_). Reglas:

- `generator = DETERMINISTIC` en `AgentRun` y un distintivo visible en la UI («generado sin IA, ilustrativo»).
- No cuenta en los indicadores (§10) ni en la comparación de modelos.
- Las pruebas de la canalización (idempotencia, cancelación, rechazo de salida inválida, permisos) usan el doble;
  ninguna prueba de CI llama a un proveedor real.
- No se presenta como «Embabel simulado»: es una implementación distinta de la misma interfaz.
- Aparte está el **doble HTTP** (WireMock) de las API de Anthropic y OpenAI, solo en pruebas de integración: prueba
  Embabel, la deserialización y los errores del proveedor sin salir a la red. No sustituye al doble determinista
  ([ADR-0033](../adr/0033-fase-5-ia.md) §11). Cómo se usa: [docs/testing](../testing/README.md#doble-http-de-los-proveedores-de-ia).

**Lo publicado funciona sin el proveedor** (RF-MED-04). Lo generado entra en el temario como registros que una persona
acepta y publica; el aula lee esos registros, nunca la IA. Sin ninguna clave, `AiProviderGate` saca los proveedores y la
plataforma de Embabel, y una versión publicada cuyas lecciones salieron de una ejecución de agente se lee entera y se
completa desde el aula (`ClassroomWithoutAiApiTest`, que además recorre el grafo de beans: ninguno del aula alcanza uno
de `elearning.ai`). `ArchitectureTest` impide que el aula, las evaluaciones, la matrícula, el catálogo o las valoraciones
usen `elearning.ai`, ni directamente ni a través de otra capacidad. El e2e de la interfaz con el proveedor desactivado es
[front#171](https://github.com/wasichai/elearning-frontend/issues/171).

## 8. Permisos separados

Generar, validar, aprobar y publicar son permisos distintos (acciones declaradas de Wasichai, ADR-042):

| Acción                                              | Quién                                     | Objeto                                     | Estado                        |
| --------------------------------------------------- | ----------------------------------------- | ------------------------------------------ | ----------------------------- |
| Generar (encolar un paso)                           | CREADOR con acceso al curso               | `GENERAR` sobre `version_curso`            | Implementado (E33.3)          |
| Validar (lanzar y aceptar el informe de validación) | REVISOR, nunca quien genera               | `VALIDAR` sobre `version_curso`            | Implementado (E33.3)          |
| Aprobar                                             | Persona con `APROBAR`, distinta del autor | `version_curso`                            | Planificado (F1 en el modelo) |
| Publicar                                            | Persona con `PUBLICAR`                    | `version_curso`                            | Planificado (F1 en el modelo) |
| Ver los indicadores de proceso (§10)                | Solo ADMIN (ningún rol del LMS)           | `VER_INDICADORES_IA` sobre `version_curso` | Implementado (E35.2)          |

Los trabajos IA no tienen ninguna de las dos últimas.

`elearning.ai.AiPermissions` las comprueba ([#280](https://github.com/wasichai/elearning-backend/issues/280)):
`requireToEnqueue` exige la acción a quien llama con `CurrentUser.requirePermission` (`403`) y `requireStillAllowed`
la vuelve a mirar al ejecutar con los roles guardados de quien pidió el trabajo (`Requesters`, ADR-0022 §4). Cada paso
de E33.4–E33.6 llama a las dos. Validar es del REVISOR y no del CREADOR, como aprobar: ningún rol da por bueno lo que
él mismo genera (separación de deberes, [seguridad §3](../security/README.md#3-separación-de-deberes)), y el
issue lo pide así («quien solo puede generar recibe `403` al validar»). Aprobar sigue sin autoaprobación
(`EditorialWorkflowGuard`).

## 9. Comparación reproducible de modelos

Tareas a comparar (especificación §11): extracción de temarios, diseño de unidades, cuestionarios, revisión de
consistencia, guiones, transformación de formato.

- **Conjunto fijo** por tarea: entradas sintéticas y fragmentos referenciados de los manuales SRTM/SGSPE (por
  referencia, sin copiar texto mientras no haya licencia, ver [licencias](../legal/licensing.md)).
- **Criterios** escritos antes de ejecutar: comprobaciones automáticas (esquema, procedencia, alineación) y rúbrica
  humana para lo pedagógico.
- **Ejecución** con una tarea de Gradle fuera de `build` (cuesta dinero y necesita clave): misma entrada, mismas
  instrucciones, varios modelos y los parámetros efectivos registrados.
- **Se conserva**: entradas, versiones de instrucciones y plantillas, modelo y parámetros, salidas, puntuaciones e
  informe. Sin cifras hasta que existan ejecuciones reales.

Implementado en E35.1 ([#290](https://github.com/wasichai/elearning-backend/issues/290)) para los pasos que hay en `main`:
el análisis de fuentes (E33.4) y la opinión de consistencia (E34.2); el diseño (E33.5), el borrador (E33.6) y los guiones
(E36.1) se suman con su carpeta cuando lleguen.

- **Conjuntos y criterios** en [`docs/ai/evaluations/<tarea>/`](evaluations/README.md): `set.json` (casos y lo esperado de
  cada uno) y `criteria.md` (su versión, `source-analysis-criteria@1`…, las puntuaciones y la rúbrica humana). Contra
  proveedores reales la tarea se niega a correr si esa carpeta tiene cambios sin confirmar, y guarda el commit y el sha256
  de los dos archivos. Una prueba unitaria comprueba que lo esperado de cada caso existe en el caso y que cada puntuación
  está escrita en los criterios.
- **Participantes**: por cada proveedor con clave, el modelo del paso (`extraction`, `consistency`) y el de referencia
  (`reference`: `claude-opus-5-5`, `gpt-6-astra`; ADR-0033 §3), sin repetir, todos con el `max_tokens` del paso. El doble
  determinista del análisis corre como línea base y **nunca entra en la comparación** (sale en «Fuera de la comparación»);
  la opinión no tiene doble.
- **Mismos pasos que producción**: cada caso pasa por el generador del paso (`ModelSourceAnalyzer`, `ConsistencyReviewer`),
  con sus instrucciones y su objetivo (`SourceAnalysisPrompt.objective`, `ConsistencyReviewPrompt.objective`), y por su
  comprobación por código (`SourceAnalysisCheck`, `ConsistencyReviewCheck`).
- **Puntuaciones por código, nunca por un modelo**: `schema_valid` (se deserializó), `accepted` (la comprobación la
  aceptó), `provenance_valid` u `origins_valid` (lo que quedó entre lo devuelto), `coverage` (elementos esperados con
  procedencia en su sección), `verdict` y `origin_recall` (registros esperados señalados). Un fallo del proveedor (`FAILED`)
  no puntúa. La rúbrica humana queda fuera del informe automático.
- **Parámetros efectivos**: Claude 5 no admite temperatura (Embabel 1.5.4 no la envía y lo avisa en el registro), así que
  lo que se fija y se registra es el **esfuerzo de razonamiento** (`ModelSettings.effort`: `LOW`, `MEDIUM`, `HIGH`).
  `ModelCalls` lo pasa a Embabel como presupuesto de pensamiento, que en Claude ≥ 4.7 es pensamiento adaptativo con
  `output_config.effort`, y como `reasoning.effort` de la Responses API de OpenAI; cada convertidor lee solo el suyo. Sin
  esfuerzo no se envía nada y vale el del proveedor: así siguen los pasos de producción, cuya clave de idempotencia no
  cambia. La comparación pide `medium` si no se dice otra cosa; la temperatura queda `null`.
- **Resultados** en `build/ai-eval/<inicio>/`: `run.json` (modo, commit, conjuntos con su versión de criterios y sus
  hashes, participantes con proveedor, modelo, papel y parámetros, y la comparación con las versiones de instrucciones y
  plantilla), `<tarea>/<participante>/<caso>.json` (salida, informe de validación, puntuaciones, tokens, duración, fuentes
  con su hash y error) y `report.md` (una tabla por tarea con la media de cada puntuación, aceptados, fallidos y tokens, y
  el estado de cada caso). Nada lleva claves.
- **A mano, contra los proveedores reales** (con Docker, como `integrationTest`):

  ```bash
  export GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token)
  ANTHROPIC_API_KEY=… OPENAI_API_KEY=… ./gradlew aiEval
  # opcional: LMS_AI_EVAL_EFFORTS=low,high (o default, el del proveedor) y LMS_AI_EVAL_TASKS=source-analysis
  ```

  `aiEval` no depende de `build` ni de `check` y su prueba (`ModelEvaluationProvidersTest`, etiqueta `ai-eval`) queda
  fuera de `test` e `integrationTest`. Con una sola clave compara los modelos de ese proveedor.

- **En CI**, `ModelEvaluationSmokeTest` (en `integrationTest`) corre lo mismo contra el doble HTTP: los cuatro modelos de
  `application.yml`, el esfuerzo en el cuerpo de cada petición, sin temperatura, y todo lo que se guarda. Es una prueba de
  humo: las respuestas son fijas y su informe no lleva cifras.

## 10. Indicadores (sección 10)

Se calculan solo sobre ejecuciones `generator = MODEL`. La corrección técnica no es calidad pedagógica: los
indicadores 1–8 miden el proceso; solo el 9 y la revisión humana dicen algo de la calidad.

| #   | Indicador                                      | Definición                                                                                                                      |
| --- | ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Borradores aceptados sin cambios estructurales | Borradores aprobados sin cambio estructural ÷ borradores revisados                                                              |
| 2   | Tiempo brief → borrador validado               | Mediana entre la creación del brief y el primer `ValidationReport` sin errores bloqueantes                                      |
| 3   | Referencias verificadas                        | Referencias cuya cita existe en la fuente y revisión indicadas ÷ referencias generadas                                          |
| 4   | Incidencias antes de revisión                  | Errores del `ValidationReport` por borrador al entrar en revisión                                                               |
| 5   | Tiempo de revisión humana                      | Mediana entre «en revisión» y la decisión del revisor                                                                           |
| 6   | Coste por unidad y por curso                   | Suma de `cost` de las ejecuciones ligadas, reintentos incluidos, con la tarifa y su fecha                                       |
| 7   | Publicados sin errores de generación           | Versiones publicadas sin incidencias atribuidas a la generación ÷ versiones publicadas con contenido generado                   |
| 8   | Incidencias tras publicar                      | Feedback contextual resuelto como error de contenido en las versiones generadas, por versión publicada                          |
| 9   | Calidad percibida                              | Valoración de recursos (claridad, utilidad, actualidad, calidad) de estudiantes matriculados, con muestra mínima publicada (F6) |

Cambio estructural: alta, baja o reordenación de unidades, lecciones o evaluaciones respecto del borrador.

**Cálculo** (E35.2, [#291](https://github.com/wasichai/elearning-backend/issues/291)). `GET /api/lms/ai/indicators`
([docs/api](../api/lms.md#indicadores-de-la-canalización-f5-rf-ia-10)) calcula a petición los indicadores 1–8 desde
`lms.ejecucion_agente` (con sus intervenciones, §14), `lms.transicion_version` (el historial de estados de
[ADR-0032](../adr/0032-historial-de-estados-de-la-version-en-tabla-propia.md)) y `lms.decision_revision`, con una consulta por indicador
(`PipelineIndicatorStore`, `elearning.ai`). Son **indicadores de proceso**: la respuesta y cada indicador lo dicen
(`kind = PROCESS`, y no existe otro tipo) y ninguno se presenta ni se resume como calidad pedagógica. El 9 es de las
valoraciones (F6) y no se sirve aquí.

- **Qué es MODEL.** Una versión cuenta como **generada** solo si la nombra una ejecución `generator = MODEL` de un paso que
  crea borradores (`DraftValidationStore.GENERATING_STEPS`, en `version_curso_id` o en `output.versionId`, §13): lo que hizo
  el doble determinista no cuenta en ningún indicador. Los informes de validación (`VALIDATION`) son siempre `DETERMINISTIC`,
  porque son código: son la medida de esas versiones, no una generación.
- **Permiso.** `VER_INDICADORES_IA` sobre `version_curso`, una acción declarada que no tiene ningún rol del LMS (solo ADMIN,
  que tiene todas): los indicadores son de toda la organización y llevan el gasto. Con `courseId`, de ese curso, que se lee
  como quien llama (`404` si no lo ve).

| #   | Cálculo exacto                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| --- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Revisadas: versiones generadas con alguna fila en `lms.decision_revision`. Aceptadas: las revisadas cuya última decisión es `APROBAR` y sin cambio estructural. Cambio estructural: una intervención `EDITED` en una ejecución que la generó cuya nota (§14) da de baja una `unidad`, `leccion` o `evaluacion` o cambia su `orden` o su padre (`STRUCTURAL_CHANGE`), o un número distinto de unidades, lecciones o evaluaciones entre el primer informe de validación de la versión y el último (las altas no dejan intervención, §14) |
| 2   | Mediana, en segundos, entre la creación del brief (`createdAt` del registro de Wasichai, leído como quien llama) que nombra en `inputs.briefId` la ejecución generadora más antigua de la versión y el fin del primer informe `SUCCEEDED` con `blocking = false`. Quedan fuera (`excluded`) las validadas cuya ejecución no nombra un brief, o cuyo brief ya no existe o quien llama no ve. El paso que cree borradores ([#283](https://github.com/wasichai/elearning-backend/issues/283)) tiene que llevar `briefId` en sus entradas  |
| 3   | En los análisis de fuentes `MODEL` cerrados con informe (`SUCCEEDED` o `INVALID_OUTPUT`): verificadas = conceptos + procedimientos que quedaron (cita comprobada en la sección y el commit indicados, §5); generadas = verificadas + descartadas por su procedencia (`NO_PROVENANCE`, `SOURCE_NOT_READ`, `ANCHOR_NOT_FOUND`, `QUOTE_NOT_IN_SOURCE`). `BLANK` no es una referencia y no cuenta                                                                                                                                          |
| 4   | Por versión generada que entró en revisión, el último informe terminado antes de su **primera** transición a `EN_REVISION`: `problemCount` y sus totales `BLOCKING` y `WARNING`. `perDraft` = problemas ÷ borradores con informe; `withoutReport`, los que entraron sin ninguno. Con `GeneratedDraftGuard`, `blocking` es 0: lo que llega son avisos                                                                                                                                                                                   |
| 5   | Mediana (`percentile_cont(0.5)`), en segundos, entre cada decisión de `lms.decision_revision` sobre una versión generada y la última transición a `EN_REVISION` anterior a ella. Una decisión sin esa transición (historial anterior a V16) queda fuera (`excluded`)                                                                                                                                                                                                                                                                   |
| 6   | Suma de `cost` de las ejecuciones `MODEL`, todos los intentos y estados, por curso y por unidad (`unit_id`) y moneda, con las tarifas usadas y su fecha (`rates`). Las ejecuciones sin tarifa van en su línea con `currency` y `amount` nulos: el importe no se inventa                                                                                                                                                                                                                                                                |
| 7–8 | `UNAVAILABLE`: necesitan el feedback contextual resuelto como error de contenido (RF-REP-03, F6), que aún no existe. Se calcularán cuando exista; hasta entonces no se rellenan con un valor que parecería bueno                                                                                                                                                                                                                                                                                                                       |

## 11. Configuración prevista

`ANTHROPIC_API_KEY` y `OPENAI_API_KEY` (ambas opcionales), sus URL base (`ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`,
que tienen precedencia sobre las propiedades), proveedor por defecto, modelo por paso, `max_tokens` por paso,
concurrencia por organización, presupuesto por curso, presupuesto de la organización por período y tarifas para estimar coste. Lista y valores en [operación](../operations/README.md#variables-de-entorno).

## 12. Remotion (sección 12)

**Precedente**: SRTM ya tiene guiones Remotion (`srtm-backend/docs/presentaciones/src/videos/*.ts`) con `objetivo`,
audiencia, `segundos` por escena y trazas `{ doc, ancla }` al manual ([referencias](../research/reference-repositories.md#5-manuales-srtm-y-sgspe)).

- **Licencia**: Remotion no es software libre OSI; usa su propia licencia. Con 4.x es gratuita para particulares,
  organizaciones con ánimo de lucro de **hasta 3 empleados**, organizaciones sin ánimo de lucro y evaluación; con 5.0
  (anunciado) el umbral es de **3 personas contando contratistas**, y centros educativos y organismos públicos necesitan
  permiso escrito. En los demás casos, Company License (para un trabajador como el nuestro, «Automators»: desde 100 USD/mes).
  El usuario puede cambiar parámetros o datos de nuestras composiciones (el guion lo puede escribir la IA), **nunca aportar
  código Remotion**. La licencia es de quien despliega: el titular usa la Free License; el render es un componente
  **opcional y desactivado por defecto**, en un trabajador separado, y cada despliegue responde de la suya
  ([ADR-0039](../adr/0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md),
  [licencias](../legal/licensing.md#remotion)).
- **Decisión técnica**: [ADR-0040](../adr/0040-renderizador-de-video-en-un-trabajador-node.md) (E37.1). Resumen:
- **Entrada del render**: el guion de RF-MED-01 como un documento JSON validado por los dos lados contra
  `render-worker/schema/guion.schema.json`: objetivos, secciones con duración, diapositiva de una lista cerrada, narración
  (transcripción), subtítulos y trazas; resolución (720p o 1080p), fps (25 o 30), fuente OFL de una lista cerrada que va
  en el paquete e imágenes dentro de la petición. Nunca código, URL ni HTML.
- **Ejecución**: trabajador Node aparte (`render-worker/`, su propio `package.json` y su imagen), al que el `JobHandler`
  `VIDEO_RENDER` de la cola (§5) llama por HTTP interno con un secreto compartido; la cola limita los renders en curso
  al reclamar y el render tiene su propio tiempo límite. Nunca en el proceso WebFlux; el trabajador no toca la base ni el
  almacén.
- **Validación del artefacto**: `ffprobe` en el trabajador; el backend compara su informe con el guion (contenedor,
  códec, resolución, fps, duración, pistas, tamaño, sha256) y analiza el VTT. Sin validar no es «vídeo terminado» y no
  se crea la versión.
- **Almacenamiento**: en `wasichai-files`, una sola copia, en un campo que solo escribe la plataforma con tope de 256 MB
  (el techo `wasichai.files.max-bytes` sube a 256 MB y los demás campos conservan su tope explícito). Wasichai junta la
  subida entera en memoria y no atiende `Range`: se le pide subida en streaming, `Range` y detección de MP4.
- **Previsualización** antes de la revisión, con el propio MP4 del borrador (sin el Player de Remotion); la versión publicada referencia el artefacto por hash y sigue
  funcionando sin el proveedor de IA ni el renderizador.

### 12.1 El guion de vídeo (esquema 1)

Implementado en E37.2 ([#297](https://github.com/wasichai/elearning-backend/issues/297)). El guion de RF-MED-01 es un
documento JSON con la forma de [`render-worker/schema/guion.schema.json`](../../render-worker/schema/guion.schema.json)
(JSON Schema 2020-12, `additionalProperties: false` en **todos** los objetos: un campo de más es un error, nunca se
ignora). Es el contrato de [#293](https://github.com/wasichai/elearning-backend/issues/293): el paso de IA que escriba el
guion produce exactamente esto y lo valida con `ScriptSchema.validate` antes de guardarlo.

| Campo               | Forma                                                                                    |
| ------------------- | ---------------------------------------------------------------------------------------- |
| `version`           | `1` (obligatorio)                                                                        |
| `idioma`            | `es`, `en`, `es-PE`… (`^[a-z]{2}(-[A-Z]{2})?$`, obligatorio)                             |
| `titulo`            | texto de 1 a 200 (obligatorio)                                                           |
| `objetivos`         | hasta 20 textos de 1 a 300                                                               |
| `tipografia`        | `inter` (por defecto) o `source-serif-4`: fuentes OFL que van en `render-worker/fonts/`  |
| `salida.resolucion` | `1280x720` (por defecto) o `1920x1080`                                                   |
| `salida.fps`        | `30` (por defecto) o `25`                                                                |
| `secciones`         | de 1 a 100 (obligatorio), en orden                                                       |
| `secciones[].id`    | `^[a-z0-9][a-z0-9-]{0,63}$`, único en el guion                                           |
| `.titulo`           | texto de 1 a 200                                                                         |
| `.segundos`         | entero de 1 a 600; la duración del vídeo es la suma                                      |
| `.diapositiva`      | una de la lista cerrada de abajo, por `tipo`                                             |
| `.narracion`        | texto (transcripción), hasta 10 000                                                      |
| `.subtitulos`       | hasta 200 `{ desde, hasta, texto }`: segundos **desde el principio de la sección**       |
| `.trazas`           | hasta 20 `{ doc, ancla? }` a la fuente (`doc` es texto libre, p. ej. `owner/repo@sha:r`) |

Diapositivas (`diapositiva.tipo`):

| `tipo`    | Campos                                                                                           |
| --------- | ------------------------------------------------------------------------------------------------ |
| `portada` | `titulo`, `subtitulo?`                                                                           |
| `vinetas` | `titulo`, `vinetas` (de 1 a 8 textos de 1 a 300)                                                 |
| `imagen`  | `imagen` (sha256 en hexadecimal minúsculo), `alt` (obligatorio), `pie?`                          |
| `codigo`  | `titulo?`, `lenguaje?` (`^[a-z0-9+#-]{1,32}$`), `codigo` (hasta 4000): se **muestra** como texto |
| `cierre`  | `titulo`, `mensaje?`                                                                             |

Reglas que el esquema no expresa y que aplican los dos lados en código (`ScriptSchema.rules` en el backend, `rules` de
`render-worker/src/guion.mjs`): ids de sección únicos; cada subtítulo con `desde < hasta`, `hasta` dentro de su sección y en
orden sin pisar al anterior; la duración total dentro de `lms.render.max-seconds` (900 s).

**Solo datos.** Ningún campo es un componente, una expresión, un `import`, una ruta, una URL ni HTML. Todo texto se pinta
como nodo de texto de React (escapado) y los subtítulos se escapan en el VTT; una imagen se nombra por su sha256 y viaja
dentro de la petición al trabajador (`{ guion, recursos: { <sha256>: <base64> } }`, solo PNG, JPEG o WebP por sus bytes,
20 MB en total), nunca por URL. Hasta que el guion de #293 tome sus imágenes de los archivos del curso, el backend rechaza
(`400`) un guion con diapositivas `imagen`; el trabajador ya las admite.

Los ejemplos de [`render-worker/schema/ejemplos/`](../../render-worker/schema/ejemplos) (válidos e inválidos; los
`regla-*` cumplen el esquema y solo los rechazan las reglas) los prueban el trabajador (zod y ajv sobre el mismo esquema) y
el backend (`ScriptSchemaTest`). `ScriptSchema` interpreta el subconjunto de JSON Schema que usa el archivo y no carga uno
con otra palabra clave: cambiar el esquema obliga a cambiar los dos lados en el mismo PR.

### 12.2 El trabajador y la cola

- `POST /api/lms/studio/resources/{id}/video-renders` con `{ "guion": … }` encola `VIDEO_RENDER` en `lms.trabajo`
  ([API](../api/lms.md#render-de-vídeo-f5-rf-med-02)); con el render apagado es `409`.
- `VideoRenderJobHandler` corre en el dispatcher de la cola, con su propio tiempo límite (`lms.render.timeout`) y como
  mucho `lms.render.max-concurrent` renders en curso entre todas las réplicas: `JobQueue.claimCapped` no reclama uno más
  (los demás esperan en `QUEUED` sin gastar intentos). Llama al trabajador con `PUT /renders/{trabajo}` (idempotente),
  valida el informe, recoge `video.mp4` y `subtitulos.vtt` comprobando sha256 y tamaño contra el informe, y los borra con
  `DELETE`.
- En el trabajo quedan el guion (`datos`), el informe (`resultado`: sha256 y tamaños, `ffprobe`, versiones de Remotion y del
  trabajador) y el error. Un trabajador inexistente o inalcanzable, un secreto que no coincide o un guion rechazado son
  fallo **permanente** con un mensaje que dice qué mirar; ocupado (`503`) o un corte a mitad, se reintenta.
- **Validación y guardado** (E37.3, [#298](https://github.com/wasichai/elearning-backend/issues/298), RF-MED-03):
  `RenderedVideoCheck` compara el informe de `ffprobe` y el VTT con el guion (duración, resolución, fps, pistas y
  subtítulos); lo que no cuadra falla sin crear versión. Lo validado queda en una `version_recurso` en `BORRADOR` (MP4 en su
  campo `video`, una sola copia) con sus `artefacto_generado` `MP4` y `VTT`; el revisor lo previsualiza con
  `GET /api/lms/studio/resource-versions/{id}/video` ([API](../api/lms.md#render-de-vídeo-f5-rf-med-02)).

## 13. Informe de validación (RF-IA-05)

Implementado en E34.1 ([#286](https://github.com/wasichai/elearning-backend/issues/286)). `DraftValidator`
(`elearning.ai`) valida **por código** una versión en borrador: determinista, sin red, sin modelo. No visita enlaces ni lee
fuentes; solo mide lo que el borrador dice, y su texto es dato no confiable. Cada problema lleva la forma del informe del
importador (`kind`, `origin` = `objeto:id`, `field`, `line`, `detail`) más su `category` y su `severity`:

| Categoría       | Problema (`kind`)                                                                                               | Gravedad   |
| --------------- | --------------------------------------------------------------------------------------------------------------- | ---------- |
| `STRUCTURE`     | `EMPTY_VERSION`, `EMPTY_UNIT`, `LESSON_WITHOUT_EVALUATION`, `EMPTY_EVALUATION`, `QUESTION_WITHOUT_ANSWER`       | bloqueante |
| `STRUCTURE`     | `REPEATED_ORDER` (dos hermanas con el mismo orden)                                                              | aviso      |
| `REFERENCES`    | `INVALID_SOURCE_REF` (la traza no es `repo@sha:ruta#ancla`)                                                     | bloqueante |
| `REFERENCES`    | `MISSING_SOURCE_REF`, `UNDECLARED_SOURCE` (repositorio y commit fuera de `fuentes` de la versión)               | aviso      |
| `ALIGNMENT`     | `MISSING_OUTCOMES` (ni objetivos ni resultados de aprendizaje), `NO_ASSESSMENT` (ninguna evaluación)            | bloqueante |
| `ALIGNMENT`     | `UNASSESSED_OBJECTIVES` (unidad con objetivos sin evaluación), `UNALIGNED_EVALUATION` (evaluación sin objetivo) | aviso      |
| `LINKS`         | `UNSAFE_LINK`: un enlace de un texto o de un entorno de laboratorio que no pasa `SafeLink` (https público)      | bloqueante |
| `DUPLICATES`    | `DUPLICATE_CONTENT`: el mismo resumen de lección o enunciado de pregunta, normalizado, por SHA-256              | aviso      |
| `ACCESSIBILITY` | `IMAGE_WITHOUT_ALT` (AsciiDoc `image::x[]`, Markdown `![](x)`, HTML `<img>` sin `alt`)                          | bloqueante |
| `ACCESSIBILITY` | `HEADING_LEVEL_SKIPPED` (de `=` a `===`, de `#` a `###`)                                                        | aviso      |
| `METADATA`      | `MISSING_DURATION` (lección), `MISSING_HOURS` (unidad), `MISSING_ASSESSMENT_SYSTEM` (versión)                   | aviso      |

El informe (`ValidationReport`) lleva además `contentHash`, el SHA-256 de lo validado (`DraftSnapshots`: la versión y su
temario, ordenados), `blocking`, los totales por gravedad y categoría y la lista recortada a 500.

**Lanzar.** `POST /api/lms/ai/validations` exige `VALIDAR` (`AiPermissions`, `403`), ver la versión (`404`) y que sea un
borrador (`409`); su autor no la valida (`403`, separación de deberes). Corre en la cola (`ai-validation`), que vuelve a
mirar `VALIDAR` con los roles guardados de quien la pidió, lee el borrador como la plataforma y deja el informe en una
ejecución `AgentRun`: paso `VALIDATION`, `generator = DETERMINISTIC`, la versión en `version_curso_id`, el informe en
`validations` y `draft-validator/1` como versión de instrucciones y de plantilla. Un informe con problemas bloqueantes es
una ejecución `SUCCEEDED`: lo que falla es el borrador. Lo leen (`GET /api/lms/ai/validations?versionId=`), si ven la
versión, su autor, su revisor asignado y quien tiene `VALIDAR`; el resto, `403`. Contrato en
[docs/api](../api/lms.md#validación-de-borradores-f5-rf-ia-05).

**Borrador generado.** Una versión es **generada** si alguna ejecución de un paso que crea borradores
(`DraftValidationStore.GENERATING_STEPS`, hoy `LESSON_DRAFT`) la nombra: en `version_curso_id` si la versión existía al
empezar, o en `output.versionId` si la creó la propia ejecución. El paso de borrador
([#283](https://github.com/wasichai/elearning-backend/issues/283), §5) lo respeta: crea la versión y cierra su ejecución con
`output = { "versionId": "<id>", … }` en la misma transacción (`AiStepRunner.runWriting`). El vínculo vive en `lms.ejecucion_agente`, que solo escribe la
plataforma y no se edita, así que el autor no puede quitárselo a su borrador. Un paso nuevo que cree versiones entra en
`GENERATING_STEPS`.

**Enviar a revisión.** `GeneratedDraftGuard` (`RecordWriteGuard`, toda escritura: el envío del estudio y la API de
registros) responde `409` al llevar un borrador generado de `BORRADOR` a `EN_REVISION` si no tiene informe, si el último no
corresponde al contenido actual (otro `contentHash`: se editó después de validarlo) o si tiene problemas bloqueantes. Los
avisos no bloquean. Un borrador escrito a mano no pasa por aquí. Índices de la búsqueda por versión: `V23`.

**Opinión de consistencia** (E34.2, [#287](https://github.com/wasichai/elearning-backend/issues/287)). Al terminar su
informe, la validación pide la opinión de un modelo sobre la coherencia contenido-evaluación: si cada pregunta se responde
con lo que enseñan su unidad y sus lecciones, si las opciones correctas lo son según ese contenido y si las evaluaciones
comprueban sus objetivos. Es el paso `CONSISTENCY_REVIEW` (trabajo `ai.consistency_review`, `ConsistencyReviewJobHandler`
sobre `AiStepRunner`), con el patrón de §5:

- **Modelo barato y configurable**: la clave `consistency` de `lms.ai.models` (por defecto el de extracción,
  `claude-haiku-5-5` · `gpt-6-luna`, en lugar del de la tabla propuesta en ADR-0033 §3: lo pide el issue) y
  `lms.ai.max-tokens.consistency` (2000). Instrucciones y plantilla `consistency-review@1`.
- **Una opinión, nunca un resultado**: su salida (`ConsistencyOpinion`) lleva `kind = OPINION` y `blocking = false`, y
  `GeneratedDraftGuard` solo mira el informe de `DraftValidator`. Un «APPROVED» sobre un borrador con errores bloqueantes no
  lo deja entrar en revisión; un «CONCERNS» sobre uno limpio no se lo impide.
- **Independiente**: otro trabajo y otra ejecución `AgentRun` (`MODEL`, con la versión en `version_curso_id`), que cuenta en
  el gasto del curso y de la organización y en sus presupuestos (`lms.ai.limits.course-budget` y `organization-budget`). Su fallo o una salida inválida (`INVALID_OUTPUT`)
  no cambian el informe. La pide quien validó: `AiStepRunner` recomprueba `VALIDAR`, no `GENERAR`.
- **Se omite y el informe lo dice** (`opinion.status = SKIPPED` con su `reason`): sin proveedor (`NO_PROVIDER`; no hay doble
  determinista, una opinión sin modelo no aporta nada), con el presupuesto del curso o el de la organización en su período gastado (`BUDGET_SPENT`, #368) o con la cola
  de quien validó llena o la organización en su tope de pasos de IA a la vez (`QUEUE_FULL`, #302: validar no elude
  `lms.ai.limits.max-active-per-organization`). Lo que pidió la validación queda en `output.consistencyReview` de su ejecución.
- **Idempotente por contenido**: la clave lleva el `contentHash` del informe; validar otra vez lo mismo reutiliza la opinión
  sin llamar al proveedor. El trabajo lee el borrador como la plataforma y, si cambió desde que se validó (otro hash), falla
  sin llamar.
- **Dato no confiable**: el borrador va como JSON, delimitado por un marcador que sale de su hash, en el mensaje de usuario y
  nunca en las instrucciones de sistema. Cada registro lleva su `origin` (`objeto:id`); `ConsistencyReviewCheck` descarta,
  con su motivo en `validations`, el hallazgo vacío (`BLANK`) o que cita un registro que no está en el borrador
  (`UNKNOWN_ORIGIN`). Sin resumen o con más de 50 hallazgos, `INVALID_OUTPUT`.

## 14. Intervenciones humanas (RF-IA-03)

Implementado en E34.3 ([#288](https://github.com/wasichai/elearning-backend/issues/288)). Cada decisión de una persona
sobre un borrador **generado** (§13) se añade a `intervenciones` de la ejecución que lo generó; si varias lo nombran, a la más
reciente. Lo hace `DraftInterventionListener` (`elearning.ai`, un `RecordChangeListener`: authoring no puede usar ai), que
Wasichai llama justo después de escribir y en la transacción de quien escribe:

| Intervención | Cuándo                                                                                                                                     | Nota                                  |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------- |
| `ACCEPTED`   | el revisor aprueba en la bandeja (`EN_REVISION` → `APROBADA`)                                                                              | la decisión y su motivo, si lo dio    |
| `REJECTED`   | el revisor la rechaza (`EN_REVISION` → `RETIRADA`), o una persona borra el borrador                                                        | la decisión y su motivo               |
| `EDITED`     | una persona guarda un cambio en el contenido de la versión o de su temario (unidad, lección, evaluación…), o borra un registro del temario | el registro y los campos, no su texto |

- **Misma transacción.** La decisión de la bandeja, su fila de `lms.decision_revision`, el cambio de estado y la intervención
  se confirman o se deshacen juntos (lo prueba un disparador que hace fallar la intervención). La API de registros de
  Wasichai no abre transacción (ADR-0032): una edición por ella y su intervención se escriben en la misma llamada, cada
  sentencia con su propia confirmación, como su `audit_log`.
- **No intervienen**: crear un registro del temario (copiar una versión crea todo el suyo y no se gasta una sentencia por
  registro; lo creado se nota al editarlo o borrarlo), validar, enviar a revisión, pedir cambios (lo que venga después lo dejan las ediciones), asignar el
  revisor ni publicar; tampoco la plataforma ni un trabajo de IA. Un borrador escrito a mano no tiene ejecución y no deja nada.
- **Solo se añaden**: ninguna ruta las escribe ni las borra (la API de `AgentRun` es de lectura y la tabla no es un objeto de
  Wasichai) y el disparador de V17 rechaza cambiarlas o quitarlas, también con SQL directo. La supresión de datos personales
  solo cambia su `userId` (V18).
- **El doble, a la vista.** `StudioVersion` lleva `generator` (`MODEL`, `DETERMINISTIC` o `null` si es manual) en el estudio y
  la bandeja, por el puerto `DraftGenerators` de authoring que implementa `DraftValidationStore`; la interfaz marca
  `DETERMINISTIC` como «generado sin IA, ilustrativo» ([wasichai/elearning-frontend#176](https://github.com/wasichai/elearning-frontend/issues/176)).
- **La IA no aprueba ni publica.** Aprobar sigue siendo de la bandeja, con una persona distinta del autor
  (`EditorialWorkflowGuard`). Un trabajo de IA corre como la plataforma, que esa guarda deja pasar: `AiStepRunner.run` y la
  validación corren con la marca `AiWork`, y con ella `AiWorkGuard` responde `403` a crear o llevar una versión de curso o de
  recurso a `APROBADA` o `PUBLICADA`, o a ponerle `publicada_en`.
