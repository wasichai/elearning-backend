# IA y multimedia (Fase 5)

**Estado: planificado (F5). Nada de esto está implementado.** Este documento fija el diseño para que F5 empiece con
decisiones tomadas y riesgos conocidos. Requisitos de origen: secciones 10, 11 y 12 de la especificación, resumidas en
[`docs/product/requirements.md`](../product/requirements.md); fase y criterio de aceptación en
[`docs/product/roadmap.md`](../product/roadmap.md). Amenazas en [`docs/security/README.md`](../security/README.md#45-agentes-ia-f5).

Principio: la IA prepara borradores y comprobaciones; **personas** aprueban y publican. Una canalización con pasos
explícitos, no agentes sueltos. Lo publicado no depende del proveedor de IA.

## 1. Canalización editorial

Diez pasos de la especificación. «Embabel» significa que el paso llama a un modelo a través de Embabel; «código»,
que es lógica determinista del módulo `elearning.ai` o de `authoring`; «persona», una acción humana con permiso propio.

1. **Brief** — temática, público, objetivos, duración, idioma, nivel, expertos y fuentes autorizadas. _Persona_
   (CREADOR) con formulario validado → `Brief`. Fuentes solo de la lista permitida o de paquetes importados
   ([importador](../security/README.md#46-importador-asciidoc-f4)).
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

- **Versión**: Embabel Agent 1.5.3 (Apache-2.0). Verificado en `embabel-agent-api-1.5.3.pom`: declara
  `spring-boot-starter:4.1.1` y `spring-ai-client-chat:2.0.1`, compatible con Boot 4.1 y Spring AI 2.0
  ([referencias](../research/reference-repositories.md#1-versiones-reales)). Es la misma versión que fija Wasichai 1.6.0.
- **Uso selectivo**: Embabel solo en los pasos 2–5 (y la opinión del 6). Sin abstracción multi-proveedor propia: una
  interfaz interna pequeña por paso (`SourceAnalyzer`, `CourseDesigner`, `LessonDrafter`, `ScriptWriter`) que tiene dos
  implementaciones, Embabel y el doble determinista (§7). Sirve para probar y para comparar modelos (§9).
- **APIs exactas de Embabel** (acciones, objetivos, salida tipada): a verificar contra el código de 1.5.3 al
  implementar; no se simulan. Si algo no fuera compatible, se documenta aquí y se entrega la alternativa con Spring AI
  `ChatClient` directo.
- **Llamadas bloqueantes**: Embabel ejecuta acciones y herramientas en hilos propios (el `AgentRun` de `wasichai-agent`
  usa `runBlocking` para volver al contexto del llamante). La canalización corre siempre en un trabajo (§5), en un
  dispatcher acotado, nunca en el event loop de WebFlux.
- **Perfiles**: `wasichai-test` advierte que un perfil llamado `test` puede desactivar los agentes de Embabel; las
  pruebas no lo usan.
- **Catálogo de modelos**: `wasichai-agent` documenta que el catálogo de Embabel 1.5.2 no conocía `claude-opus-5` y
  por eso su modelo por defecto es `claude-haiku-4-5`. Qué modelos reconoce 1.5.3: a verificar. El modelo es
  configuración, no código.

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
reutiliza las herramientas del asistente. Nombre: `wasichai.agent.AgentRun` ya existe como clase interna de una
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

Almacenamiento: `inputs`, `output` y `validations` son JSON y el tipo `JSON` de Wasichai aún no existe, así que el
registro va en una tabla fija del esquema `lms` (`ModuleMigration` orden 200), con lectura por API propia bajo
`/api/lms/**` y el mismo alcance que el curso. Encaja con [ADR-0004](../adr/0004-modelo-hibrido-custom-objects-y-tablas-fijas.md);
se confirma con un ADR en F5. Las filas no se borran; la retención se define en F6.

## 5. Esquemas tipados y trabajos

**Salida tipada.** Cada paso declara su tipo Kotlin y su esquema JSON. La salida del modelo se deserializa y valida
(esquema + reglas de dominio: horas positivas, cada evaluación alineada con un resultado, cada afirmación con
procedencia). Inválida → `INVALID_OUTPUT`, nunca se guarda como borrador; se permite un número limitado de
reintentos de corrección (configurable) y después queda para revisión humana.

**Trabajos persistentes, idempotentes y cancelables.** Verificado en `wasichai/wasichai:docs/modules/automation.md` y
`Automation.kt`: la cola de `wasichai-automation` (`automation_runs`, reclamada con `FOR UPDATE SKIP LOCKED` por
`AutomationDrain`) solo ejecuta las acciones de sus reglas (`UPDATE_FIELD`, `CREATE_RECORD`, `WEBHOOK`,
`GENERATE_DOCUMENT`, `NOTIFY`) y no ofrece un SPI para acciones propias. Por eso no puede llevar directamente un paso de
IA. Diseño previsto, a confirmar con un ADR en F5:

- Tabla fija `lms.ai_job` con el mismo patrón que `automation_runs`: estado, intentos, `FOR UPDATE SKIP LOCKED`,
  sondeo por temporizador; reclamación con `ClusterLock` donde haga falta (ADR-039 de Wasichai).
- El permiso se comprueba **al encolar**, como el usuario; el trabajo escribe con `RecordService.asPlatform(org,
"ai:<job>")`, así que el `audit_log` lo etiqueta y `requested_by` queda en `AgentRun`.
- **Idempotencia**: clave = hash de (paso, entradas, hashes de fuentes, versión de instrucciones y plantilla, modelo).
  Un reencolado con la misma clave devuelve el resultado existente: no se paga dos veces ni se crea un segundo
  borrador.
- **Reintentos controlados**: solo errores transitorios del proveedor, con espera creciente y tope; un error de
  esquema no se reintenta igual.
- **Cancelación**: marca `CANCELLED` comprobada entre pasos y entre llamadas al modelo.
- **Límites**: concurrencia por organización, tokens e iteraciones por ejecución, presupuesto por curso.
- Si en F5 Wasichai ofrece un SPI de trabajos genérico, se usa en lugar de la tabla propia.

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

## 8. Permisos separados

Generar, validar, aprobar y publicar son permisos distintos (acciones declaradas de Wasichai, ADR-042):

| Acción                                              | Quién                                     | Objeto                                             | Estado                        |
| --------------------------------------------------- | ----------------------------------------- | -------------------------------------------------- | ----------------------------- |
| Generar (encolar un paso)                           | CREADOR con acceso al curso               | Trabajo IA o versión; nombre a decidir (`GENERAR`) | A decidir en F5               |
| Validar (lanzar y aceptar el informe de validación) | CREADOR o REVISOR asignado                | Versión; nombre a decidir (`VALIDAR`)              | A decidir en F5               |
| Aprobar                                             | Persona con `APROBAR`, distinta del autor | `version_curso`                                    | Planificado (F1 en el modelo) |
| Publicar                                            | Persona con `PUBLICAR`                    | `version_curso`                                    | Planificado (F1 en el modelo) |

Los trabajos IA no tienen ninguna de las dos últimas.

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

`ANTHROPIC_API_KEY` (o el proveedor elegido), modelo por paso, límites de tokens, iteraciones y concurrencia,
presupuesto, y tarifas para estimar coste. Lista y valores en [operación](../operations/README.md#variables-de-entorno).

## 12. Remotion (sección 12)

**Precedente**: SRTM ya tiene guiones Remotion (`srtm-backend/docs/presentaciones/src/videos/*.ts`) con `objetivo`,
audiencia, `segundos` por escena y trazas `{ doc, ancla }` al manual ([referencias](../research/reference-repositories.md#5-manuales-srtm-y-sgspe)).

- **Licencia**: Remotion no es software libre OSI; usa su propia licencia (gratuita para particulares, organizaciones
  con ánimo de lucro de hasta 3 empleados y sin ánimo de lucro; licencia de empresa en los demás casos). Por eso el
  render es un componente **opcional**; ver [licencias](../legal/licensing.md#dependencias-con-condiciones-especiales).
- **Entrada del render**: objetivos, guion con secciones y duración, estructura de diapositivas, tipografía (fuentes
  OFL) y recursos, datos de entrada, subtítulos y transcripción, resolución, duración, fps.
- **Ejecución**: proceso Node separado (contenedor), invocado por un trabajo (§5) con límite de concurrencia; nunca en
  el proceso WebFlux. Registro del trabajo con parámetros, salida y errores.
- **Validación del artefacto**: duración, resolución, pistas y subtítulos comprobados sobre el archivo resultante
  (p. ej. `ffprobe`); sin validar no es «vídeo terminado».
- **Almacenamiento**: binarios fuera de PostgreSQL, en almacenamiento de objetos (S3/MinIO). `wasichai-files` lee la
  subida entera en memoria y su tope por defecto es 10 MB, así que los vídeos necesitan otra vía hacia el `FileStore`
  (a verificar en F5).
- **Previsualización** antes de la revisión; la versión publicada referencia el artefacto por hash y sigue
  funcionando sin el proveedor de IA ni el renderizador.
