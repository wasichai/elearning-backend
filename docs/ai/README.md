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
   no tiene procedencia se descarta.
3. **Diseño pedagógico** — resultados medibles, temario, secuencia, distribución horaria, actividades, evaluaciones
   alineadas, lecturas y criterios de aprobación. _Embabel_ → `CourseDesign`. Horas y créditos los calcula el dominio
   `workload` (código), no el modelo.
4. **Borrador con plantillas versionadas** — _Embabel_ → `LessonDraft` en la fuente canónica (AsciiDoc). Plantilla y
   versión quedan en `AgentRun`.
5. **Multimedia** — guiones, diapositivas, narración, subtítulos y Remotion. _Embabel_ (guion y estructura) + _código_
   (render) → `VideoScript`, `SlideDeck`. Un vídeo solo está «terminado» si se renderizó y validó (§12).
6. **Validaciones automáticas** — _código_: estructura, referencias, enlaces, recursos, duplicidad (hashes),
   accesibilidad y metadatos → `ValidationReport`. _Embabel_ solo aporta una **opinión** sobre la coherencia
   contenido-evaluación, que nunca aprueba ni bloquea por sí sola.
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
  (`PromptRunner.createObject`, nombre exacto a verificar contra 1.5.4), sin planificador, sin herramientas y con el
  cliente MCP desactivado. Sin abstracción multi-proveedor propia: una interfaz interna pequeña por paso
  (`SourceAnalyzer`, `CourseDesigner`, `LessonDrafter`, `ScriptWriter`) que tiene dos implementaciones, Embabel y el
  doble determinista (§7). Sirve para probar y para comparar modelos (§9). Si algo no fuera compatible, se documenta
  aquí y se entrega la alternativa con Spring AI `ChatClient` directo.
- **Llamadas bloqueantes**: la canalización corre siempre en un trabajo (§5), en un dispatcher acotado, nunca en el
  event loop de WebFlux.
- **Reintentos**: el interno de Embabel (10 por defecto) baja a 1–2; la política de reintentos es la de la cola (§5).
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
- **Límites**: `max_tokens` por paso, concurrencia por organización y presupuesto por curso; superar los dos últimos al
  encolar es `429` con `Retry-After`. Un paso es una llamada, más los reintentos de corrección acotados.
- Corre en un dispatcher acotado, nunca en el event loop de WebFlux.

Implementado en E33.2 ([#279](https://github.com/wasichai/elearning-backend/issues/279)): `AiStepRunner` (`elearning.ai`) es la
política común que llama el `JobHandler` de cada paso con su generador (`AiModelCall`, una llamada por intento) y su borrador;
`AiJobs` encola con la clave y los límites (`lms.ai.limits`) y lee y cancela (`GET /api/lms/ai/jobs/{id}`,
`POST /api/lms/ai/jobs/{id}/cancel`, [docs/api](../api/lms.md#trabajos-de-ia-f5-adr-0033-5-6-y-9)). La cola gana `CANCELLED`
(`V21`). Con una sola llamada por intento, «entre llamadas» es antes de llamar y al volver; los reintentos de corrección
acotados llegarán con los pasos que los usen.

## 6. Protección contra prompt injection

- El texto de manuales, fuentes y borradores es **dato no confiable**: va delimitado en el mensaje de usuario, nunca en
  las instrucciones de sistema, y las instrucciones dicen explícitamente que el contenido no da órdenes.
- Los agentes **no** tienen acceso arbitrario a archivos, red ni ejecución de código; solo reciben las entradas de su
  ejecución. Las herramientas, si alguna, son de solo lectura y acotadas a esas entradas.
- Los agentes **no** publican, no aprueban y no escriben registros: devuelven un objeto tipado que el código valida y
  guarda como borrador.
- Las referencias se verifican por código contra la fuente (la cita debe existir en `ruta#ancla` de la revisión
  indicada); los enlaces los comprueba código con la regla anti-SSRF del importador.
- La salida se trata también como no confiable: HTML saneado, sin enlaces nuevos no presentes en las fuentes salvo
  marcados para revisión.
- Ni claves ni datos de estudiantes en el contexto del modelo.

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
  ([ADR-0033](../adr/0033-fase-5-ia.md) §11).

## 8. Permisos separados

Generar, validar, aprobar y publicar son permisos distintos (acciones declaradas de Wasichai, ADR-042):

| Acción                                              | Quién                                     | Objeto                          | Estado                        |
| --------------------------------------------------- | ----------------------------------------- | ------------------------------- | ----------------------------- |
| Generar (encolar un paso)                           | CREADOR con acceso al curso               | `GENERAR` sobre `version_curso` | Implementado (E33.3)          |
| Validar (lanzar y aceptar el informe de validación) | REVISOR, nunca quien genera               | `VALIDAR` sobre `version_curso` | Implementado (E33.3)          |
| Aprobar                                             | Persona con `APROBAR`, distinta del autor | `version_curso`                 | Planificado (F1 en el modelo) |
| Publicar                                            | Persona con `PUBLICAR`                    | `version_curso`                 | Planificado (F1 en el modelo) |

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
- **Ejecución** con una tarea de Gradle o prueba etiquetada fuera de `build` (cuesta dinero y necesita clave): misma
  entrada, mismas instrucciones, varios modelos, temperatura y semilla registradas cuando el proveedor las admita.
- **Se conserva**: entradas, versiones de instrucciones y plantillas, modelo y parámetros, salidas, puntuaciones e
  informe (ubicación prevista `docs/ai/evaluations/`). Sin cifras hasta que existan ejecuciones reales.

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

## 11. Configuración prevista

`ANTHROPIC_API_KEY` y `OPENAI_API_KEY` (ambas opcionales), sus URL base (`ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`,
que tienen precedencia sobre las propiedades), proveedor por defecto, modelo por paso, `max_tokens` por paso,
concurrencia por organización, presupuesto por curso y tarifas para estimar coste. Lista y valores en [operación](../operations/README.md#variables-de-entorno).

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
