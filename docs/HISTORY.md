# Historial de cambios

Lo más nuevo primero. El razonamiento de arquitectura vive en `docs/adr/`; este archivo registra lo que se entregó.

## 2026-11-03 — Fase 3 sin pendientes de requisitos ([#231](https://github.com/wasichai/elearning-backend/issues/231))

- Todos los requisitos F3 de [requirements.md](product/requirements.md) están `implementado`. El último, RF-AUL-08, se
  comprobó en el código: `ClassroomReader` deriva `achievements` (`UNIT_COMPLETED`, `ASSESSMENT_PASSED`,
  `CERTIFICATE_ISSUED`), `AchievementsApiTest` los prueba (incluida la autorización negativa: ni otro estudiante ni el
  docente asignado los ven) y la interfaz los muestra con su e2e real (front#166). Los hallazgos de la revisión de la Fase 3
  ([#183](https://github.com/wasichai/elearning-backend/issues/183)) ya estaban cerrados; la fila F3 del
  [roadmap](product/roadmap.md) queda sin pendientes.

## 2026-11-03 — ADR del renderizador de vídeo: E37.1 ([#296](https://github.com/wasichai/elearning-backend/issues/296))

Primer paso de la épica E37 ([#295](https://github.com/wasichai/elearning-backend/issues/295)), sobre la decisión de
licencia de [ADR-0039](adr/0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md).

- [ADR-0040](adr/0040-renderizador-de-video-en-un-trabajador-node.md): Remotion en un paquete Node aparte,
  `render-worker/`, con su propio `package.json` y su imagen, que construye quien despliega; ni la raíz ni Gradle lo
  instalan. Entrada: el guion de RF-MED-01 como JSON validado por los dos lados contra un esquema compartido (secciones,
  diapositivas de una lista cerrada, subtítulos, fuentes OFL del paquete, 720p/1080p, 25/30 fps, imágenes dentro de la
  petición), nunca código.
- El backend encola `VIDEO_RENDER` en `lms.trabajo` y su manejador llama al trabajador por HTTP interno (`PUT` idempotente
  por trabajo, descarga y borrado) con un secreto compartido; la cola gana límite de concurrencia por tipo al reclamar y
  tiempo límite por manejador. El trabajador no toca la base ni el almacén.
- El vídeo se guarda en `wasichai-files`, una sola copia, en un campo que solo escribe la plataforma con tope de 256 MB
  (el techo sube y los demás campos conservan su tope); se piden a Wasichai subida en streaming, `Range` y detección de
  MP4. `ffprobe` en el trabajador y validación del informe en el backend (RF-MED-03); render desactivado por defecto.
- `docs/legal/licensing.md`, `docs/ai/README.md` §12 y la guía de operación enlazan la decisión.

## 2026-11-03 — Licencia de Remotion decidida ([#267](https://github.com/wasichai/elearning-backend/issues/267))

- [ADR-0039](adr/0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md): el titular elige Remotion como motor de
  render de la Fase 5 y, con 3 personas o menos (contratistas incluidos), usa la Free License sin pagar. El render es
  opcional y desactivado por defecto, en un trabajador Node separado con Remotion solo como dependencia npm; cada
  despliegue responde de su propia licencia. Sin excepción de enlace en la LGPL mientras el trabajador sea un programa
  aparte que recibe datos JSON; se revisa al subir a Remotion 5.0.
- `docs/legal/licensing.md` recoge los términos de 4.x y 5.0 con fuente y fecha (umbral de 3 personas, contratistas,
  centros educativos y organismos públicos, usuarios sin código, precios y telemetría); `docs/ai/README.md` §12 se
  corrige y la guía de operación avisa de la licencia a quien active el render.
- Los criterios de #267 que piden código (build sin Remotion comprobada en CI, informe de licencias, rechazo de
  peticiones con código, licencia del `ffprobe`) quedan para el trabajador, [#297](https://github.com/wasichai/elearning-backend/issues/297).

## 2026-11-03 — Trabajos de IA en la cola: idempotencia, reintentos, cancelación y límites ([#279](https://github.com/wasichai/elearning-backend/issues/279))

Segundo paso de la épica E33 ([#277](https://github.com/wasichai/elearning-backend/issues/277)), según
[ADR-0033](adr/0033-fase-5-ia.md) §5, §6 y §9 sobre la cola de ADR-0018/0022.

- `lms.trabajo` gana el estado `CANCELLED` (`V21`): `JobQueue.cancel` lo pone a un trabajo pendiente o en curso, que nadie
  vuelve a reclamar y cuyo cierre ya no escribe; `JobQueue.cancelled` lo mira el manejador y `JobCancelled` lo detiene sin
  cerrarlo otra vez. Libera su clave y la retención lo borra como a uno terminado.
- `AiStepRunner` (`elearning.ai`): la política común de los pasos, que cada `JobHandler` llama con su generador
  (`AiModelCall`) y su borrador. Reutiliza la ejecución `SUCCEEDED` con la misma clave sin llamar al proveedor; `408`, `429`
  y `5xx` (`AiProviderFailure`) se reintentan con la espera y el tope de la cola; otro `4xx`, `AiInvalidOutput`, el
  presupuesto agotado o la pérdida de `GENERAR` son `PermanentJobFailure`. Cancelación antes de llamar y al volver; el
  borrador y el cierre del trabajo en una transacción.
- `AiJobs`: clave de idempotencia (hash de paso, ámbito, objetivo, entradas, fuentes con su hash, versiones de instrucciones
  y plantilla, modelo y parámetros, en JSON canónico), límites `lms.ai.limits` (concurrencia por organización y presupuesto
  por curso, `429` con `Retry-After`) y `GET /api/lms/ai/jobs/{id}` y `POST /api/lms/ai/jobs/{id}/cancel`, solo de quien lo
  pidió y con `GENERAR`.
- Pruebas: `AiStepKeyTest` (unitaria) y `AiJobsApiTest` con un paso de prueba y un proveedor falso que cuenta sus llamadas.
  La prueba equivalente contra el doble WireMock de los proveedores llega con E32.3
  ([#274](https://github.com/wasichai/elearning-backend/issues/274)).

## 2026-11-03 — `pendingAttempts` del panel docente sin los intentos propios ([#338](https://github.com/wasichai/elearning-backend/issues/338))

- `pendingAttempts` de `GET /api/lms/teaching/panel` y `GET /api/lms/teaching/offerings` cuenta lo que quien llama puede
  corregir: `AttemptStore.countPending` excluye, como `oldestPending` y la cola, los intentos de quien además es estudiante
  de su edición. El total coincide con el de `GET …/attempts` y la interfaz ya no promete «Ver N más» de intentos que no
  puede abrir ([front#168](https://github.com/wasichai/elearning-frontend/issues/168)). Sigue siendo una lectura para todas
  las ediciones (#261). Documentado en `docs/api/lms.md`.

## 2026-11-03 — Cierre de la Fase 4: estado del criterio ([#179](https://github.com/wasichai/elearning-backend/issues/179))

El criterio de F4 ([roadmap](product/roadmap.md)) todavía no se da por cumplido. Lo que hay de cada parte:

- **Recorridos e2e 8–12** contra el backend real: cumplido
  ([elearning-frontend#60](https://github.com/wasichai/elearning-frontend/issues/60), cerrado por
  [front#75](https://github.com/wasichai/elearning-frontend/pull/75)). La revisión de seguridad E29.2
  ([#180](https://github.com/wasichai/elearning-backend/issues/180), #217) también está cerrada.
- **Publicar v2 no altera v1 ni el progreso sobre v1**: `NewVersionApiTest` copiaba y editaba la v2 sin publicarla, y
  `PublicationNoticesApiTest` la publicaba sin mirar la v1. La prueba nueva
  `publishing v2 leaves v1, its resources and the progress on v1 as they were` lleva la v2 por el estudio y la bandeja
  (copiar, editar, enviar, aprobar, publicar) y comprueba que el registro de la v1, su lección, el recurso enlazado y el aula
  de quien la cursa (versión, progreso y temario) quedan igual. No se pudo ejecutar aquí (el proxy del entorno niega
  GitHub Packages y Gradle no resuelve Wasichai): la valida el CI.
- **Importación de un capítulo de SRTM y uno de SGSPE con informe y trazas `repositorio@sha:ruta#ancla`**: pendiente. El
  importador solo lee por la API de contenidos de GitHub (`GitHubSourceFetcher`), y desde este entorno esa API responde
  `403` para `wasichai/srtm-backend` y `wasichai/sgspe`. `ImportApiTest` prueba las trazas y el informe con un origen
  simulado (`acme/manuales`), no con estos manuales. Commits de referencia para la importación (`main` el 2026-11-03):
  `wasichai/srtm-backend@1225b4f5501b4e0526b3824976299c9372691e17` (`docs/manuales/comun/acceso.adoc`) y
  `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c` (`docs/manuals/user/manual-usuario.adoc`, con
  `02-primeros-pasos.adoc`). Falta ejecutarla con `LMS_IMPORTER_ALLOWED_SOURCES` y `LMS_IMPORTER_GITHUB_TOKEN` en un
  entorno con acceso a la API de GitHub, y dejar aquí el informe y las trazas.

## 2026-11-03 — Logros en la interfaz del aula: RF-AUL-08 implementado ([front#166](https://github.com/wasichai/elearning-frontend/pull/166))

- La interfaz muestra `achievements` junto al avance, con su fecha y enlace a su unidad, evaluación o certificado. El
  recorrido real del frontend comprueba que el estudiante demo ve en `CORTO-101-A` el logro `ASSESSMENT_PASSED` de su intento
  calificado, sin crear datos. Con eso RF-AUL-08 cumple su aceptación y pasa a `implementado`.

## 2026-11-02 — Demo poblada para probar la plataforma a mano

- `model/demo/poblada.json` suma a `data.json` 24 estudiantes, 2 docentes con perfil, una categoría y dos cursos publicados
  (`DATOS-101` y `HOJAS-101`) con ediciones abiertas y programadas, una de ellas con el cupo agotado; matrículas, avance de
  lecciones, doce intentos (aprobados, desaprobados y por corregir) y tres sesiones. Todo ficticio.
- `model/demo/demo.sh` la levanta en un entorno propio, como la base de demostración de srtm-backend: base `elearning_demo`
  en 5446 y buzón en 1046/8046 (`compose.demo.yml`), backend en 8096 con `demo.env.example` (que `bootRun` lee por
  `LMS_ENV_FILE`, nunca el `.env` de desarrollo), y `sembrar`, `cuentas` y `abajo`. Se niega a tocar otra base u otro
  backend.
- `apply.py`: `--demo-data` se repite para sumar archivos; la clave `progress` completa lecciones por el evento del aula,
  como el estudiante, con un identificador de evento estable; las matrículas no se repiten ni superan la capacidad.
  `test_demo.py` prueba el contenido, la validación y la aplicación contra el Wasichai simulado.

## 2026-11-03 — Embabel 1.5.4 con Anthropic y OpenAI opcionales ([#273](https://github.com/wasichai/elearning-backend/issues/273))

Segundo paso de la épica E32 ([#271](https://github.com/wasichai/elearning-backend/issues/271)), según
[ADR-0033](adr/0033-fase-5-ia.md) §1–§6.

- `embabel-agent-starter`, `-starter-anthropic` y `-starter-openai` 1.5.4 en `gradle/libs.versions.toml` (el BOM de
  Wasichai no gestiona Embabel). Sin `-webmvc`, `-a2a` ni `-jdbc`; la tarea `noServletStack`, parte de `check`, hace fallar
  el build si Spring MVC o Tomcat llegan a `runtimeClasspath`.
- `elearning.ai.AiProviderGate` (`EnvironmentPostProcessor` en `META-INF/spring.factories`): deja fuera la
  autoconfiguración de cada proveedor sin clave y, sin ninguno, la de la plataforma de Embabel; pone como
  `embabel.models.default-llm` el modelo por defecto del proveedor activo (`LMS_AI_PROVIDER`, Anthropic con las dos claves).
- `AiStatus`: al arrancar dice qué proveedor quedó (sin claves, ninguno: la canalización usará el doble determinista) e
  impide arrancar, con un mensaje que nombra la propiedad y los modelos que hay, si un modelo por defecto o de paso
  (`lms.ai.models.{anthropic,openai}.*`) no existe en su proveedor o si `max-attempts` de Embabel pasa de 2 (queda en 2 para
  los dos).
- Cliente MCP de Spring AI apagado. `ArchitectureTest` impide `com.embabel` fuera de `elearning.ai` (regla `EMBABEL`).
- Pruebas: `AiProviderGateTest` y `AiStatusTest` (unitarias); `NoAiProviderApiTest`, `AnthropicOnlyApiTest`,
  `OpenAiOnlyApiTest` y `AiStartupFailureTest` arrancan la aplicación entera con cada combinación de claves de mentira y
  URL base a un puerto cerrado: ninguna llama a un proveedor.
- `docs/operations` (variables `LMS_AI_*` y arranque sin claves), `docs/ai` §2 y `.env.example`.

## 2026-11-03 — Valoraciones en la supresión de datos personales y en la ficha pública del docente ([#325](https://github.com/wasichai/elearning-backend/issues/325), paso 4 de [#268](https://github.com/wasichai/elearning-backend/issues/268))

- `PersonalDataErasure` (ADR-0037) suprime las valoraciones de la persona con sus puntajes, comentarios y apelaciones, y
  su cuota diaria; borra sus denuncias resueltas y deja las abiertas sin ella; encola `reputation.aggregate` para que los
  indicadores de recurso se recalculen. Lo congelado, anónimo y con n ≥ k, se conserva.
- `perfil_docente.valoraciones_publicas` (falso por omisión): con él y `perfil_publico`, la ficha pública del docente en
  el catálogo trae el agregado de sus ediciones liberadas (cifras con n ≥ k, media ponderada por n) con el enlace a su
  criterio. El catálogo lo lee de `lms.indicador_valoracion` sin usar `reputation`. Vaciar un perfil al suprimirlo
  retira también ese consentimiento.
- Pruebas: `RatingErasureApiTest` y `CatalogBuilderTest`.

## 2026-11-03 — Moderación, denuncias y apelación de las valoraciones ([#324](https://github.com/wasichai/elearning-backend/issues/324), paso 3 de [#268](https://github.com/wasichai/elearning-backend/issues/268))

- Cola y decisión de moderación con `MODERAR`: publicar u ocultar con un motivo tasado (`400` sin él); nadie modera lo
  que escribió ni lo que lo tiene como destino (`403`, también ADMIN), y decidir cierra las denuncias abiertas.
- El valorado (`DENUNCIAR`) denuncia por la ficha opaca de un comentario de su resumen (`denuncia_valoracion`, `apiOnly`),
  sin ocultarlo; pasado `lms.reputation.max-reports-per-day` (10), `429`.
- El autor apela una vez un comentario oculto, también con la ventana cerrada; lo decide otro moderador, una vez.
- `RatingWriteGuard` lo exige para toda escritura; un moderador delegado no ve quién escribió (`fieldPermissions` sobre
  `estudiante_id` y `matricula`), probado con un rol creado en la prueba. Pruebas: `RatingModerationApiTest`.

## 2026-11-03 — Resúmenes de valoraciones con muestra mínima y liberación tras las notas ([#323](https://github.com/wasichai/elearning-backend/issues/323), paso 2 de [#268](https://github.com/wasichai/elearning-backend/issues/268))

- `GET /api/lms/teaching/offerings/{code}/ratings` (el docente asignado) y `GET /api/lms/ratings/resources` (el autor),
  con `VER_RESUMEN`: media y n por dimensión solo con n ≥ `lms.reputation.min-sample` (5, piso 3), sin nadie dentro;
  comentarios publicados en orden aleatorio solo con muestra suficiente.
- El docente ve los de una edición cuando la ventana cerró y todas las matrículas tienen resultado, o pasados
  `lms.reputation.release-after-end` (60 días) del fin; entonces se congelan en `lms.indicador_valoracion` (V20). Los de
  recurso los rehace cada día el trabajo `reputation.aggregate`, que también borra la cuota de días pasados.
- Criterio publicado y versionado en `GET /api/lms/public/ratings/criterion` (`media-simple@1`).
- Pruebas: `RatingSummaryApiTest`.

## 2026-11-03 — Valorar docentes y recursos desde la matrícula ([#322](https://github.com/wasichai/elearning-backend/issues/322), paso 1 de [#268](https://github.com/wasichai/elearning-backend/issues/268))

- [ADR-0038](adr/0038-politica-de-valoraciones.md): la política de valoraciones de #268 (quién valora, anonimato para el
  valorado, liberación tras las notas, muestra mínima, moderación tasada, límites y conservación según los términos de
  uso), con lo abierto (purga del `audit_log` de Wasichai, delegar `MODERAR`) pendiente del titular.
- Modelo: `dimension_valoracion` (siete dimensiones en la demo), `valoracion` y `puntaje_valoracion` (`apiOnly`), con
  las acciones `VALORAR` (ESTUDIANTE), `VER_RESUMEN` y `DENUNCIAR` (DOCENTE y CREADOR, sin `READ`) y `MODERAR` (ADMIN).
- `elearning.reputation`: elegibilidad, crear (con `Idempotency-Key`), editar, retirar y listar las propias desde
  `/api/lms/classroom/enrollments/{id}/ratings` y `/api/lms/ratings/**`; progreso mínimo, lección completada, conflicto
  de interés, ventana tras `fecha_fin` y `429` pasado el límite diario (`lms.cuota_valoracion`, V19). `RatingWriteGuard`
  e `RatingReadScope` para toda escritura y lectura; `lms.reputation.*`. `ArchitectureTest`: nadie usa `reputation`.
- Pruebas: `RatingApiTest` (sujetos de un solo rol, releyendo tras cada negativa), `RatingWindowTest`, `test_model.py`.

## 2026-11-03 — Permisos para generar y validar con IA ([#280](https://github.com/wasichai/elearning-backend/issues/280), RF-IA-07)

- Acciones declaradas `GENERAR` y `VALIDAR` sobre `version_curso` ([ADR-0033](adr/0033-fase-5-ia.md) §10): genera CREADOR
  y valida REVISOR; quien genera no valida, aprueba ni publica. `APROBAR` y `PUBLICAR` siguen siendo las de F4.
- `AiPermissions` (`elearning.ai`): `requireToEnqueue` exige la acción a quien llama con `CurrentUser.requirePermission`
  (`403`) y `requireStillAllowed` la vuelve a mirar al ejecutar con los roles guardados de quien lo pidió (`Requesters`,
  ADR-0022 §4). Aún no hay pasos que encolar: los de E33.4–E33.6 llamarán a las dos. Ningún código mira nombres de rol.
- `ai` puede usar `jobs` ([overview §2.3](architecture/overview.md#23-dependencias-permitidas-y-prohibidas)): sus pasos son
  trabajos de la cola (ADR-0033 §5).
- `AiPermissionsApiTest` llama al servicio con el token real de cada persona de la demo; `test_model.py` fija el reparto.

## 2026-11-03 — Brief estructurado del curso ([#278](https://github.com/wasichai/elearning-backend/issues/278), RF-IA-01)

- Custom Object `brief` (relación `brief_curso`): temática, público, objetivos, duración en horas, idioma, nivel, expertos y
  fuentes autorizadas, con estado `BORRADOR` → `LISTO` (enum `estado_brief`). CREADOR lo lee y escribe.
- `BriefGuard` (`elearning.ai`), para toda escritura: el brief se guarda a medias en borrador y pasar a `LISTO` (o seguir en
  él) incompleto es `400` con un error por campo que falta; horas > 0. Cada fuente es `owner/repo@sha:ruta` de
  `lms.importer.allowed-sources` o del repositorio y commit de un paquete ya importado al curso (`400` si no, también en
  borrador). Lo escribe solo quien tiene `CREATE` sobre `version_curso`: darle el brief a otro rol no basta (`403`).
- `ai` puede usar `course` e `importer` ([overview §2.3](architecture/overview.md#23-dependencias-permitidas-y-prohibidas)).
  `BriefGuardApiTest` y `test_model.py`.

## 2026-11-03 — La supresión de datos personales cubre `lms.ejecucion_agente` ([#326](https://github.com/wasichai/elearning-backend/issues/326), RNF-SEG-11)

- `V18__supresion_ejecucion_agente.sql`: el disparador de `lms.ejecucion_agente` deja pasar, solo dentro de la transacción
  de una supresión (`lms.supresion`, como V15), el cambio de `solicitante_id` y del `userId` de las intervenciones; nada
  más cambia y borrar sigue prohibido.
- `PersonalDataErasure` disocia las ejecuciones que la persona pidió o en las que intervino con el mismo seudónimo: la
  ejecución queda como evidencia del proceso editorial y la constancia las cuenta en `disociados.ejecucion_agente`.
  `PersonalDataErasureApiTest` lo prueba con una ejecución sembrada con `AgentExecutionLog`; repetirla no cambia nada.

## 2026-11-03 — Registro `AgentRun` de las ejecuciones de IA ([#275](https://github.com/wasichai/elearning-backend/issues/275), RF-IA-03)

- `lms.ejecucion_agente` (`V17__ejecucion_agente.sql`, [ADR-0033](adr/0033-fase-5-ia.md) §7) con los campos de `docs/ai` §4:
  quién lo pidió, curso, unidad y versión, objetivo, entradas, fuentes con hash, proveedor, modelo y parámetros efectivos,
  versiones de instrucciones y plantilla, salida, validaciones, estado, error, tokens, coste con su tarifa y fecha, inicio y
  fin, intervenciones, clave, intento y `generator`. Un disparador impide editar lo pedido, cerrar dos veces, borrar y tocar
  las intervenciones ya escritas: solo se añaden.
- `AgentExecutionLog` (`elearning.ai`) es la escritura para los pasos de F5: abre, cierra con el consumo del proveedor y
  calcula el coste con la tarifa de `lms.ai.rates` que regía el día en que empezó; tapa como `[REDACTED]` una clave
  configurada que aparezca en cualquier campo. Los parámetros del modelo son tipados (`maxTokens`, `temperature`).
- `GET /api/lms/ai/runs?courseId=` y `GET /api/lms/ai/runs/{id}` con `UPDATE` sobre `version_curso` y el alcance del curso
  (`403`/`404`). `AgentExecutionApiTest` y `AiRatesTest` lo prueban con ejecuciones construidas en la prueba; la de un paso
  contra el doble WireMock queda para [#274](https://github.com/wasichai/elearning-backend/issues/274).

## 2026-11-03 — Políticas de crédito versionadas por jurisdicción, institución y programa ([#318](https://github.com/wasichai/elearning-backend/issues/318), RF-CRE-07)

- [ADR-0036](adr/0036-politicas-de-credito-versionadas-en-datos.md): método en código, política en datos.
  `politica_credito` (clave y número, borrador → publicada → retirada, sistema, método, parámetros, jurisdicción ISO 3166-1
  o `EHEA`, programa opcional, fuente y vigencia). `CreditPolicyGuard`: publicada no cambia ni se borra (`409`);
  `CreditMethods.of` valida los parámetros contra la norma (`400`).
- Método nuevo `PE_LEY30220_HORAS_LECTIVAS` (Ley 30220 art. 39: 16 h de teoría o 32 de práctica por crédito, sin estudio
  autónomo) y sistema `PE_CREDITO`; sin conversión entre sistemas.
- `/api/lms/credits/policies`: listado por jurisdicción, sistema, estado y programa; publicar, retirar y `new-version`.
- `version_curso.politica_credito` liga una versión de curso a una versión de política: propone con ella en el catálogo y
  en la aprobación, que la exige publicada y del mismo sistema y guarda la relación y su instantánea. Publicar otra
  versión de la política no cambia lo ya ligado. `CreditPolicyApiTest`, `CreditPolicyTest` y `CatalogBuilderTest`.

## 2026-11-03 — Historial de estados de una versión ([#259](https://github.com/wasichai/elearning-backend/issues/259))

- [ADR-0032](adr/0032-historial-de-estados-de-la-version-en-tabla-propia.md): cada cambio de `estado` de una
  `version_curso`, y su creación, deja una fila en la tabla nueva `lms.transicion_version` (V16) con el estado anterior,
  el nuevo, quién y cuándo. Es de solo inserción (un disparador impide cambiarla o borrarla) y la escribe un
  `RecordChangeListener` en la transacción de quien escribe, sea por el estudio, la bandeja, la API de registros o la
  plataforma. No se lee el `audit_log` de Wasichai.
- El detalle del estudio y de la bandeja (`StudioVersionDetail`) gana `history: [{ from, to, by, byName, at }]`, con las
  mismas reglas de lectura; `byName` se lee junto con los demás nombres de la versión (ADR-0034). Enviar a revisión y
  crear la primera versión vacía pasan a escribir en una transacción. `VersionHistoryApiTest` recorre BORRADOR →
  EN_REVISION → BORRADOR → EN_REVISION → APROBADA → PUBLICADA, la API de registros, la plataforma y el disparador.

## 2026-11-03 — Aprobación de créditos por una autoridad académica ([#315](https://github.com/wasichai/elearning-backend/issues/315), RF-CRE-08)

- **Corrige un fallo** que encontró la investigación de [#269](https://github.com/wasichai/elearning-backend/issues/269):
  `EditorialWorkflowGuard` dejaba escribir `version_curso.creditos_aprobados` a quien tenía `APROBAR`, la acción editorial
  del revisor. Ahora ese campo y `politica_creditos` solo los escribe el comando de aprobación (`403` por la API de
  registros para todos, ADMIN incluido) y una versión nueva no los hereda.
- [ADR-0035](adr/0035-aprobacion-de-creditos-por-una-autoridad-academica.md): acción `APROBAR_CREDITOS` sobre
  `version_curso` y rol `AUTORIDAD_ACADEMICA` (demo: `autoridad@demo.wasichai-learning.test`). `aprobacion_creditos`
  (`appendOnly`, `apiOnly`) guarda cada acto con la instantánea de la política de referencia (id@versión, método,
  parámetros, fuente), horas, créditos calculados, propuestos y aprobados, motivo, órgano, documento, persona y hora.
- `/api/lms/credits/**`: propuesta y aprobación vigente, historial, aprobar (versión `APROBADA` o `PUBLICADA` con sistema
  de crédito, recálculo en el servidor, motivo si difiere) y revocar (otra fila, con motivo). Separación de funciones por
  datos, también para ADMIN: ni el autor, ni el revisor asignado, ni quien aprobó en la bandeja, ni un docente del curso.
- El catálogo dice «aprobados» solo con una aprobación vigente, con su órgano, documento y política (`credits.approval`).
  `CreditApprovalApiTest`, `CatalogBuilderTest`, `EditorialWorkflowGuardApiTest`, `ModelProvisioningApiTest` y las pruebas
  de `model/` lo comprueban.

## 2026-11-03 — ADR de la Fase 5: Embabel 1.5.4, proveedores, límites y `AgentRun` ([#272](https://github.com/wasichai/elearning-backend/issues/272))

Solo documentación, primer paso de la épica E32 ([#271](https://github.com/wasichai/elearning-backend/issues/271)).

- [ADR-0033](adr/0033-fase-5-ia.md): Embabel 1.5.4 (no la 1.5.3, que falla con Claude 5) con Anthropic y OpenAI (Responses
  API) opcionales y Anthropic por defecto; modelo por paso como configuración; una llamada tipada por paso, sin
  planificador, herramientas ni MCP; los trabajos de IA en `lms.trabajo` con el estado nuevo `CANCELLED` y el reintento
  interno de Embabel a 1–2; `AgentRun` en la tabla fija `lms.ejecucion_agente`; `max_tokens` por paso, concurrencia por
  organización y presupuesto por curso; permisos `GENERAR` y `VALIDAR`; ninguna prueba de CI llama a un proveedor real
  (doble determinista y doble HTTP con WireMock).
- `docs/ai`, RF-IA-08, `domain-model.md`, `docs/operations` (claves, URL base y modelos por paso) y el roadmap (épicas
  E32–E38) se alinean con él.

## 2026-11-03 — Nombres de autor, revisor y de quien decide ([#258](https://github.com/wasichai/elearning-backend/issues/258))

- `StudioVersion` (estudio y bandeja), `ReviewComment` y `ReviewDecision` traen `authorName` / `reviewerName` junto a su id:
  el nombre para mostrar de la persona, nunca su correo; `null` si está deshabilitada o fue borrada. Solo van donde ya iba el
  id y a quien ya puede leer la versión.
- Wasichai 1.8.0 no da nombres en `UserDirectory`: `DisplayNames` los lee de su tabla de personas en una sentencia por
  respuesta, con las condiciones de `UserDirectory.existing` ([ADR-0034](adr/0034-nombres-para-mostrar-desde-la-tabla-de-personas-de-wasichai.md),
  provisional). `AuthoringNamesApiTest` prueba los nombres, el `null` de quien se deshabilita y que lo ajeno sigue siendo
  `404`/`403`; `AuthoringCostApiTest`, que la bandeja y el detalle no suman sentencias por persona.

## 2026-11-03 — Generación de HTML y PDF aceptada con una fuente propia ([#264](https://github.com/wasichai/elearning-backend/issues/264), RF-CON-07)

- [ADR-0031](adr/0031-aceptacion-de-la-generacion-con-una-fuente-propia.md): la aceptación de RF-CON-07 deja de pedir una
  lección importada (la importación no copia el texto de los manuales, ADR-0018 §6) y pide una `fuente_recurso` propia con
  `include::`, una imagen y una tabla que produzca en la cola HTML y PDF validados, sin generar en la petición.
- `RenderApiTest` lo prueba con el doble y con Asciidoctor real: HTML saneado con título, texto, tabla e imagen por su
  texto alternativo, el `include::` (también `/etc/passwd`) como enlace sin leer nada, y un PDF bien formado con la imagen
  incrustada y el generador real en cada artefacto. CI instala `asciidoctor` 2.0.26 y `asciidoctor-pdf` 2.3.27 con
  `gem install` y exige la prueba (`LMS_TEST_ASCIIDOCTOR=required`). RF-CON-07 pasa a implementado; el binario en la
  imagen de despliegue sigue en F6 (RNF-OPS-04).

## 2026-11-03 — Términos de uso con plazo y supresión de datos personales ([#304](https://github.com/wasichai/elearning-backend/issues/304), RNF-SEG-11)

- [ADR-0037](adr/0037-terminos-de-uso-y-supresion-de-datos-personales.md) implementa ADR-0030: términos de uso
  versionados e inmutables (`lms.terminos_uso`, V15) que publica quien tiene `MANAGE_ORGANIZATION`, y una aceptación por
  persona con la versión y el plazo que aprobó (`lms.aceptacion_terminos`). `GET`/`POST /api/lms/terms` y
  `POST`/`DELETE /api/lms/terms/acceptance`. Publicar otra versión no altera las aceptaciones previas.
- **Sin aceptación vigente, matricularse y registrar progreso dan `403`** con el motivo; el catálogo público sigue
  abierto. Sin términos publicados nadie se matricula: una organización existente debe publicar los suyos.
- Al retirar la aceptación o vencer su plazo (barrido cada `lms.privacy.sweep-every`, 1 h), el trabajo `privacy.erase`
  de la cola suprime eventos, progreso, asistencias, entregas, intentos sin calificar, trabajos y la aceptación, y
  disocia matrículas, intentos calificados y certificados (seudónimo aleatorio, sin correo, respuestas ni nombre; el
  código del certificado sigue verificando) y vacía el perfil docente. Una transacción, idempotente, con constancia sin
  datos personales en `lms.supresion`. Los disparadores de solo inserción ceden solo dentro de esa transacción.
- `Certificate.holderName` puede ser `null` (titular suprimido). La semilla publica unos términos ficticios y el
  estudiante demo los acepta antes de matricularse (`terms` en `model/demo/data.json`, validado por `apply.py`).
- `TermsApiTest` (puerta `403`, catálogo abierto, versiones y permisos) y `PersonalDataErasureApiTest` (supresión por
  retirada y por vencimiento, lo disociado, la constancia y la idempotencia) lo prueban; `test_demo`, la semilla.

## 2026-11-03 — Paneles del estudiante y del docente en una lectura ([#261](https://github.com/wasichai/elearning-backend/issues/261))

- `GET /api/lms/classroom/panel` y `GET /api/lms/teaching/panel`, paginados con `page` y `size`, devuelven por elemento
  lo que la interfaz armaba con hasta dos peticiones por matrícula o edición: «Mis cursos» con sus próximas sesiones y sus
  evaluaciones pendientes; «Mis ediciones» con sus próximas sesiones y los 5 intentos más antiguos de su cola. Sin el
  tope de 8 ediciones: la interfaz los usará en [front#158](https://github.com/wasichai/elearning-frontend/issues/158).
- Se leen por lotes: temarios, progreso, intentos, sesiones, asistencia y certificados (estudiante), y versiones, cursos,
  matrículas, conteo y principio de la cola (docente), en una lectura cada uno. `GET /teaching/offerings` usa el mismo
  armado y deja de leer cuatro veces por edición. `ClassroomPanelApiTest` y `TeachingPanelApiTest` prueban el contenido,
  la paginación, el alcance (solo lo propio; solo lo asignado) y que el coste no crece con la página (`StatementCount`).

## 2026-11-03 — Lección de repaso de una pregunta ([#260](https://github.com/wasichai/elearning-backend/issues/260))

- `pregunta` gana `leccion_repaso`, una relación opcional a la lección que la repasa. `EditorialWorkflowGuard` exige que sea
  de la misma versión (`400` si no) y la congela con lo publicado; la copia de versión la re-apunta a la lección copiada.
- La copia de las evaluaciones (`lms.contenido_evaluacion`) y la instantánea del intento la guardan: el resultado trae
  `reviewLessonId` por pregunta, una vez enviado y sin depender de las soluciones (ADR-0019); el intento abierto no la trae.
  Las instantáneas anteriores la dan como `null`. Las diferencias de versión no comparan evaluaciones: no aplica.
- `ReviewLessonApiTest` (guarda, inmutabilidad y copia) y `AttemptApiTest` lo comprueban; `test_model`, el campo.

## 2026-11-03 — Logros derivados en el aula ([#265](https://github.com/wasichai/elearning-backend/issues/265), RF-AUL-08)

- El aula de una matrícula devuelve `achievements: [{ kind, at, ref }]`: `UNIT_COMPLETED` (unidad con todas sus
  lecciones contables completadas), `ASSESSMENT_PASSED` (evaluación aprobada por un intento calificado) y
  `CERTIFICATE_ISSUED` (certificado emitido). Se derivan al leer de lo que el aula ya calcula, sin modelo, tabla ni
  escritura nuevos y sin otra lectura: el certificado se lee una vez para `certificateCode` y para su logro. Solo los ve
  el estudiante de la matrícula (`404` a otro y al docente). `AchievementsApiTest` y la semilla (`CORTO-101-A`) lo
  prueban; la interfaz los mostrará en front#154.

## 2026-11-03 — La administración, una sección del menú ([front#157](https://github.com/wasichai/elearning-frontend/pull/157))

- RNF-UX-06: «Datos» y «Administración» solo los ve quien tiene `admin` o `MANAGE_ORGANIZATION`, con `shellNav` de
  `@wasichai/core`. Una sola aplicación y ninguna URL nueva (ADR-0005). `docs/operations/administracion.md` lo recoge, junto
  con lo que queda: el mensaje de core ante un `403` y que la interfaz aún no ofrece crear el primer borrador de un curso nuevo sin importar.

## 2026-11-03 — LGPL definitiva y conservación según los términos ([#303](https://github.com/wasichai/elearning-backend/issues/303))

- [ADR-0030](adr/0030-lgpl-definitiva-y-conservacion-segun-los-terminos.md) reemplaza a ADR-0015: la licencia
  LGPL-3.0-or-later deja de ser provisional (RNF-OSS-07 implementado) y los datos personales se conservan el tiempo que la
  persona aprueba en los términos de uso. La aceptación con plazo y la supresión se implementan en
  [#304](https://github.com/wasichai/elearning-backend/issues/304).

## 2026-11-03 — Primer borrador vacío de un curso sin versiones ([#257](https://github.com/wasichai/elearning-backend/issues/257))

- `POST /api/lms/studio/courses/{courseId}/versions` sobre un curso sin versiones ya no responde `400`: crea la v1
  `BORRADOR` vacía a nombre de quien llama, con la modalidad, la duración y el crédito mínimos válidos (el curso no los
  tiene), y aparece en «Mis versiones». Con versiones, sigue copiando la última o la de `fromVersionId`.
- La unicidad de `[curso, numero]` impide dos v1: de dos peticiones a la vez, una crea y la otra responde `409`.
  `NewVersionApiTest` prueba el borrador vacío, la concurrencia y la autorización negativa.

## 2026-11-03 — Listado de mis importaciones ([#255](https://github.com/wasichai/elearning-backend/issues/255))

- `GET /api/lms/studio/import` devuelve los trabajos de importación de quien llama, el más reciente primero, con la fuente,
  el curso y las fechas (`ImportJob` gana `source`, `courseId`, `createdAt` y `finishedAt`): al recargar, la interfaz no
  pierde de vista la importación en curso (ADR-0029). Nunca lista las de otra persona; los terminados duran lo que la
  retención de la cola. Índice nuevo `trabajo_por_solicitante` (V14).

## 2026-11-03 — Traza de origen en la unidad ([#263](https://github.com/wasichai/elearning-backend/issues/263), RF-IMP-03)

- `unidad` gana `referencia_fuente` (`repo@sha:ruta#ancla`), como la lección: la importación la llena con la traza del
  capítulo que propuso la unidad. Es contenido de la versión: `EditorialWorkflowGuard` la congela con lo demás, la copia de
  versión la lleva y las diferencias la comparan.
- El detalle del estudio devuelve `sourceRef` también en cada unidad. `ImportApiTest` comprueba la traza de cada unidad y
  que la copia la conserva; `VersionDiffApiTest`, que cambiarla cambia la unidad. Con esto RF-IMP-03 queda implementado.

## 2026-11-03 — Gravedad en los problemas de importación ([#254](https://github.com/wasichai/elearning-backend/issues/254))

- Cada problema del informe de importación trae `severity` (`GAP`: esa parte del manual no se leyó; `CHECK`: se leyó y queda
  algo que revisar), decidida por el servidor según su tipo, y el informe trae `problemsBySeverity` con todos los problemas,
  también los que pasan de los 500 que muestra. La interfaz deja de mantener su propia tabla (ADR-0029). Los informes
  guardados antes la reciben al leerlos.

## 2026-11-02 — Rediseño UX/UI de la interfaz ([front#126](https://github.com/wasichai/elearning-frontend/issues/126))

Solo en elearning-frontend ([#251](https://github.com/wasichai/elearning-backend/issues/251) registra aquí la decisión): ninguna API ni contrato de
este repositorio cambió. La decisión está en [ADR-0029](adr/0029-sistema-de-diseno-quipu-aplicado.md), con lo que el rediseño pide al backend y no se
hizo.

- **Cimientos**: auditoría y dirección ([front#127](https://github.com/wasichai/elearning-frontend/pull/127)); piezas compartidas de `src/shared` y
  recorrido visual ([front#128](https://github.com/wasichai/elearning-frontend/pull/128),
  [front#132](https://github.com/wasichai/elearning-frontend/pull/132)); pestañas de sección y avisos con icono
  ([front#137](https://github.com/wasichai/elearning-frontend/pull/137)); comparación de versiones con leyenda, cifras y antes y ahora
  ([front#138](https://github.com/wasichai/elearning-frontend/pull/138)).
- **Aprendizaje**: visor de lecciones ([front#129](https://github.com/wasichai/elearning-frontend/pull/129)), panel del estudiante con «Continuar
  aprendiendo» ([front#130](https://github.com/wasichai/elearning-frontend/pull/130)), detalle del curso y navegación del aula
  ([front#133](https://github.com/wasichai/elearning-frontend/pull/133)), evaluaciones
  ([front#134](https://github.com/wasichai/elearning-frontend/pull/134)) y su resultado
  ([front#135](https://github.com/wasichai/elearning-frontend/pull/135)).
- **Docencia**: panel con bandeja y agenda ([front#139](https://github.com/wasichai/elearning-frontend/pull/139)), matriculados y marco común de la
  edición ([front#145](https://github.com/wasichai/elearning-frontend/pull/145)), corrección en lote con rúbrica
  ([front#149](https://github.com/wasichai/elearning-frontend/pull/149)), sesiones por fecha y asistencia
  ([front#153](https://github.com/wasichai/elearning-frontend/pull/153)).
- **Gestión editorial**: versiones por curso y detalle con línea de estados ([front#144](https://github.com/wasichai/elearning-frontend/pull/144)),
  importación por pasos con informe por gravedad ([front#147](https://github.com/wasichai/elearning-frontend/pull/147)), bandeja y panel de decisión
  ([front#151](https://github.com/wasichai/elearning-frontend/pull/151)).
- **Descubrimiento**: catálogo con filtros progresivos ([front#143](https://github.com/wasichai/elearning-frontend/pull/143)), ficha de curso y
  matrícula ([front#146](https://github.com/wasichai/elearning-frontend/pull/146)), finalización y certificado
  ([front#148](https://github.com/wasichai/elearning-frontend/pull/148), [front#152](https://github.com/wasichai/elearning-frontend/pull/152)).
- Pendiente de la épica: la sección de administración en una sola `WasichaiApp`
  ([front#140](https://github.com/wasichai/elearning-frontend/issues/140) a [front#142](https://github.com/wasichai/elearning-frontend/issues/142)) y
  el cierre ([front#125](https://github.com/wasichai/elearning-frontend/issues/125)). Lo que Wasichai no ofrece se pidió en
  [wasichai-ui#122](https://github.com/wasichai/wasichai-ui/issues/122) a [wasichai-ui#130](https://github.com/wasichai/wasichai-ui/issues/130).

## 2026-11-01 — El laboratorio completo y sus entornos externos validados ([#250](https://github.com/wasichai/elearning-backend/pull/250), [front#106](https://github.com/wasichai/elearning-frontend/pull/106))

- RF-LAB-01 ([ADR-0027](adr/0027-laboratorio-en-la-leccion-y-entornos-por-matricula.md)): `leccion` gana `objetivo`,
  `requisitos`, `hitos`, `criterios_aceptacion` y `entregables`; los enlaces a entornos son un Custom Object nuevo,
  `entorno_laboratorio`, que solo leen los matriculados (`ContentReadScope`), congelado con la versión
  (`EditorialWorkflowGuard`), copiado a la versión siguiente y comparado en las diferencias. El aula devuelve `lab` en cada
  lección `LABORATORIO`, y la interfaz lo muestra con los entornos en otra pestaña (`rel="noopener noreferrer"`).
- RF-LAB-03 y RNF-SEG-08: `LabEnvironmentGuard` valida cada enlace con `SafeLink`, el mismo validador que las sesiones,
  para toda escritura, la plataforma incluida (`400` en `enlace`). `LabApiTest` prueba rechazo y aceptación, autorización
  negativa, inmutabilidad de lo publicado y el alcance de lectura.

## 2026-11-01 — Filtro del catálogo por conocimientos previos ([#248](https://github.com/wasichai/elearning-backend/issues/248), RF-CAT-08)

- [ADR-0028](adr/0028-conocimientos-previos-como-claves-de-la-version.md): los conocimientos previos se declaran como
  claves del vocabulario nuevo `conocimiento_previo` (lo escribe ADMIN, lo leen todos los roles) en
  `version_curso.conocimientos_previos_claves`, una por línea; se congelan con la versión publicada. El texto libre de
  `curso.conocimientos_previos` sigue para la ficha. `PrerequisiteKnowledgeGuard` exige claves bien formadas y
  declaradas, y que una clave del vocabulario no cambie ni se borre.
- Catálogo público: la ficha lleva `prerequisiteKnowledgeTags`, el listado acepta `?prerequisiteKnowledge=<clave>|none`
  y devuelve `facets.prerequisiteKnowledge`, todo sobre la proyección (sin migración nueva). `CatalogListApiTest` prueba
  una clave, `none` y una clave sin cursos; `PrerequisiteKnowledgeGuardApiTest`, el vocabulario y la guarda.
- La semilla declara cuatro claves (una sin cursos) y las pone en las versiones publicadas de SRTM e IA. Con esto
  RF-CAT-08 queda implementado.

## 2026-11-01 — ADR-0026 (propuesta): sistema de diseño Quipu ([#245](https://github.com/wasichai/elearning-backend/issues/245))

- [ADR-0026](adr/0026-sistema-de-diseno-quipu.md) propone lo que la interfaz ya integró del sistema de diseño Quipu:
  - un tema de app sobre el contrato de `@wasichai/ui` (`quipu`, `quipu-dark`);
  - una hoja de controles de 44px por `data-slot` públicos;
  - el modo lectura como subárbol (`quipu-lectura`);
  - Figtree y Literata servidas por la app.

  La CSP de la lectura HTML no cambia; incrustar Literata en ella (`font-src data:`) queda para un ADR propio. Pedidos a
  Wasichai: [wasichai/wasichai-ui#115](https://github.com/wasichai/wasichai-ui/issues/115) y
  [#116](https://github.com/wasichai/wasichai-ui/issues/116).

## 2026-11-01 — Wasichai 1.8.0: el servidor comprueba el hash de cada descarga ([#216](https://github.com/wasichai/elearning-backend/issues/216), L6)

- `wasichai` pasa de 1.6.0 a 1.8.0 y, alineados con él, Spring Boot a 4.1.2 y el AWS SDK a 2.57.5. 1.7.0 trae Embabel
  1.5.4 y `claude-haiku-5-5` como modelo por defecto del agente; 1.8.0, la comprobación del sha256 en cada descarga de
  `wasichai-files` ([wasichai#145](https://github.com/wasichai/wasichai/pull/145), su ADR-074).
- L6 de #216: la descarga de un `FILE` lleva `Repr-Digest` (RFC 9530) y se comprueba contra el tamaño y el sha256
  registrados mientras se transmite. Un objeto alterado en el almacén nunca se sirve entero (`500`, o un cuerpo más
  corto que su `Content-Length`) y queda en el rastro `admin:stored-file`; devueltos sus bytes, se sirve otra vez.
  `S3ResourceFilesApiTest` lo prueba contra MinIO, con un archivo pequeño y uno grande.

## 2026-11-01 — La cola de corrección por páginas ([#183](https://github.com/wasichai/elearning-backend/issues/183), M4)

- `GET /api/lms/teaching/offerings/{code}/attempts` (también con `?status=GRADED`) admite `?page` y `?size` opcionales,
  con la misma semántica que el roster: la página se corta en SQL sobre `lms.intento` (`LIMIT`/`OFFSET`, en el orden de
  envío de siempre y sin los intentos de quien mira) y los correos se leen solo por las matrículas de esa página. Sin
  ellos devuelve la cola entera, como antes (`QueuePageApiTest`). Con esto M4 queda resuelto.

## 2026-11-01 — ADR-0025: alcances con subconsultas sobre las tablas de Wasichai ([#236](https://github.com/wasichai/elearning-backend/pull/236))

- [ADR-0025](adr/0025-alcances-de-lectura-con-subconsultas-sobre-tablas-de-wasichai.md) registra, como provisional, lo
  que #236 integró: `ContentReadScope` y `EnrollmentReadScope` leen las tablas de datos de Wasichai con subconsultas y
  repiten a mano su organización, `VER_TODAS` y `READ`, no `ownRecordsOnly`. Qué revisar al subir de versión de Wasichai,
  y la petición de una vía propia: [wasichai/wasichai#147](https://github.com/wasichai/wasichai/issues/147).

## 2026-11-01 — El roster por páginas ([#183](https://github.com/wasichai/elearning-backend/issues/183), M4)

- `GET /api/lms/teaching/offerings/{code}/students` admite `?page` y `?size` opcionales, como la API de registros: una
  página de las matrículas no retiradas en el orden de siempre, con su progreso y su asistencia leídos por esas matrículas
  (`listAllIn`). Sin ellos devuelve la edición entera, como antes (`RosterPageApiTest`).

## 2026-11-01 — Intentos y calificaciones en la semilla ([#231](https://github.com/wasichai/elearning-backend/issues/231), RNF-OSS-06)

- `model/demo/data.json` gana `attempts`: el estudiante demo, matriculado también en `CORTO-101-A`, responde y envía su
  evaluación por el aula, y el docente demo asignado corrige la abierta (8 de 10, aprobada). `apply.py` lo hace por los
  comandos, como cada persona, lo valida sin red y es idempotente; un intento que quedó a medias se termina en la
  siguiente ejecución. Los recorridos e2e crean sus propios estudiantes: sus conteos no cambian.

## 2026-11-01 — El alcance con subconsultas SQL ([#183](https://github.com/wasichai/elearning-backend/issues/183), M4, y #104, M1)

- `ContentReadScope` ya no lista versiones, unidades y lecciones para ligarlas como un arreglo de ids (`ANY(?)`) que crecía
  con el catálogo publicado: resuelve la cadena matrícula → versión → unidad → lección → enlace → recurso con subconsultas
  SQL sobre las tablas de Wasichai (`WasichaiSchemas`, `MetadataService.loadDefinition`), con valores ligados. Cada nivel
  repite lo que leerlo como quien llama aplicaba: su `organization_id`, su `VER_TODAS` y, sin `READ` sobre él, nada.
- `EnrollmentReadScope` lee el progreso, las entregas y la asistencia del docente por la subconsulta de las matrículas de
  sus ediciones, no por la lista de sus ids, que crecía con los estudiantes.
- `StatementCount` guarda el arreglo ligado más largo de una petición (`ScopeCostApiTest`): el del aula pasaba de 16 a 19
  con tres cursos publicados más y el del roster de 3 a 8 con seis estudiantes más; ya no crecen.

## 2026-11-01 — Registro consultable de cada operación sensible ([#231](https://github.com/wasichai/elearning-backend/issues/231), RF-ADM-03)

- `SensitiveOperationsAuditApiTest` prueba que contenidos (versión y lección), decisiones y publicaciones, y roles
  quedan en el `audit_log` de Wasichai con su autor, y que ADMIN los consulta; quien no es ADMIN recibe `403` en
  `/api/audit`, nada del rastro `admin:*` y `404` en el historial de lo ajeno.
- Las calificaciones siguen en `lms.calificacion`: quien tiene `MANAGE_ORGANIZATION` lee la cola de corrección y el
  intento con su `history` de cualquier edición, sin asignación y sin poder calificar.

## 2026-11-01 — Prerrequisitos al matricular ([#239](https://github.com/wasichai/elearning-backend/pull/239), RF-ACA-08)

- `version_curso.cursos_previos` (`model/model.json`): los códigos de los cursos que la versión exige haber aprobado, uno
  por línea; se congela con la versión publicada ([ADR-0024](adr/0024-prerrequisitos-como-campo-de-la-version.md)).
- `POST /api/lms/enrollments` responde `409` con un `detail` que nombra cada curso que falta si el estudiante no tiene en
  él una matrícula propia `FINALIZADA` y `APROBADO`. Un código desconocido nunca se cumple.

## 2026-11-01 — Ediciones completas en el catálogo ([#237](https://github.com/wasichai/elearning-backend/pull/237), RF-CAT-09)

- La ficha pública dice el estado de la matrícula de cada edición, `enrollmentStatus` `OPEN`, `CLOSED` o `FULL`, y `full`
  (cupo agotado), también en `nextOffering`. La proyección lee `lms.ocupacion_edicion` en cada refresco y publica solo un
  sí o un no, nunca cuántos ni quiénes; una matrícula que no agota el cupo no reescribe la fila
  ([ADR-0023](adr/0023-cupo-agotado-en-la-proyeccion-del-catalogo.md)).
- `enrollmentOpen`, en la ficha y en el filtro de RF-CAT-08, deja fuera las ediciones completas.

## 2026-11-01 — El aula lee sus recursos por lotes ([#104](https://github.com/wasichai/elearning-backend/issues/104), M1)

- `ClassroomReader.resources()` lee los enlaces de las lecciones del aula, sus versiones de recurso y sus recursos en una
  lectura cada uno (`listAllIn`, `listByIds`), no un `get` por versión y por recurso: el aula pasa de unas 2 sentencias SQL
  por recurso distinto a ninguna (`ClassroomCostApiTest`). Un enlace cuya versión o recurso no se puede leer no se muestra.

## 2026-11-01 — Aviso de publicación de contenido ([#231](https://github.com/wasichai/elearning-backend/issues/231), RF-AUL-09)

- Publicar una versión de curso, por la bandeja, la API de registros o la plataforma, avisa por `wasichai-notifications`
  (bandeja y correo, Mailpit en desarrollo) a quien cursa **otra versión de ese curso** con su matrícula `ACTIVA`: lo
  publicado no cambia y solo se matricula en una versión publicada, así que el contenido nuevo para alguien ya matriculado
  es una versión nueva de su curso. Nunca a una matrícula retirada ni a otro curso; cada estudiante es destinatario
  propio y nadie ve quién más lo recibió. Una `version_recurso` publicada no avisa: una lección solo enlaza recursos ya
  publicados y en un temario en borrador.
- `PublicationNoticeListener` (un `RecordChangeListener`) encola el trabajo `aviso-publicacion` en la transacción de la
  publicación, con clave por versión; el trabajo lee las matrículas como la plataforma (quien publica no las lee,
  ADR-0014) y publica el aviso con clave `version/<id>`: repetirlo no avisa dos veces. La primera versión de un curso no
  encola nada. Publicar en la bandeja pasa a ser una transacción; `learning` puede usar `jobs` (ArchitectureTest).

## 2026-11-01 — Barrido de intentos vencidos ([#183](https://github.com/wasichai/elearning-backend/issues/183), L4)

- Un intento vencido se cierra con `enviado_en` = su plazo (`min(ahora, plazo)`), no el momento en que alguien lo toca: el
  aprobado de la lección (`passedAt`) ya no depende de cuándo se abrió el aula.
- `ExpiredAttemptSweep` encola cada `lms.assessment.sweep-every` (1 min), en la cola persistente, un barrido por
  organización con intentos abiertos y vencidos (clave fija: uno a la vez). El trabajo los cierra como la plataforma, con
  lo guardado, en lotes de 100 con el mismo cerrojo de fila que guardar y enviar (`FOR UPDATE SKIP LOCKED`): el que tiene
  bloqueado un estudiante lo cierra él. El abandonado llega así a la cola del docente y al roster. Índice parcial
  `intento_abierto_vence` (V13). Cierra lo que ADR-0020 dejó pendiente de la cola.

## 2026-11-01 — Curso de demostración del recorrido editorial ([#224](https://github.com/wasichai/elearning-backend/issues/224))

- `model/demo` incluye `TRAMITE-101`, con su v1 publicada y la edición `TRAMITE-101-A` con la inscripción abierta, solo
  para el e2e editorial del frontend: publica una versión nueva en cada ejecución sin sacar del catálogo las ediciones de
  los cursos que usan otros recorridos ni crear su edición por la API. `test_demo.py` lo comprueba; el catálogo de la
  demo pasa a tener cinco cursos publicados.

## 2026-10-31 — Quien publica abre en la bandeja lo aprobado ([#223](https://github.com/wasichai/elearning-backend/issues/223))

- Con revisor asignado, quien tiene `PUBLICAR` lee en la bandeja el detalle y las diferencias de una versión ajena para
  publicarla; decidir sigue siendo del revisor asignado y nadie abre ahí lo suyo.

## 2026-10-31 — Hallazgos de la revisión de la Fase 4 ([#216](https://github.com/wasichai/elearning-backend/issues/216))

- **L4**: el detalle del estudio y de la bandeja, las diferencias, la bandeja y «mis versiones» leen por niveles o de una vez,
  no por lección ni por versión (`listAllIn`, `listByIds`); su coste ya no crece con el temario ni con la bandeja. La copia
  lee cada nivel de una vez y lo escribe con `createAll`: ≈23 sentencias por unidad de dos lecciones, antes ≈40
  (`AuthoringCostApiTest`).
- **L5, comentarios**: `comentario_revision` tiene alcance de lectura (`ReviewCommentReadScope`); un CREADOR ya no lee por la
  API de registros los comentarios de versiones ajenas. `ScopeMemo` pasa a `elearning.common`.
- **M1**: la revisión de un curso se decide solo en la bandeja, que deja la decisión y su motivo en `lms.decision_revision`;
  por la API de registros es `403` y una versión de curso no nace aprobada. La de un recurso exige `X-Change-Reason` salvo
  al aprobar ([ADR-0021](adr/0021-la-revision-de-un-curso-se-decide-en-la-bandeja.md)).
- **M2**: importar y generar lo mismo mientras el trabajo anterior espera devuelve ese trabajo, sin otro borrador ni otra
  versión; cada persona tiene a lo sumo `lms.jobs.max-active-per-requester` (5) trabajos pendientes o en curso, y uno más es
  `429` con `Retry-After` ([ADR-0022](adr/0022-limites-y-ciclo-de-vida-de-los-trabajos.md)).
- **L1**: el borrador importado y el cierre de su trabajo van en una transacción; si la réplica muere tras el commit, no hay
  reintento que haga otro borrador, y la que perdió el reclamo deshace el suyo.
- **L7**: al ejecutarse, la importación y la generación vuelven a comprobar como plataforma que quien las pidió sigue
  habilitado y que sus roles guardados le dan lo que se le exigió al encolar; si no, el trabajo termina `FALLIDO` sin
  reintentos ni escritura (`PermanentJobFailure`).
- **L2**: la generación no reintenta lo que no cambia (tipo, hash, falta del binario, fallo o tiempo límite de Asciidoctor,
  salida vacía o enorme): termina `FALLIDO` en ese intento. Lo pasajero (almacenamiento, interrupción) se sigue reintentando.
- **L3**: el HTML generado se sanea con lista blanca (jsoup) antes de guardarse: quedan el contenido, sus clases y anclas,
  URL `http`, `https` o `mailto`, el título y el estilo de Asciidoctor; se van scripts, manejadores de eventos, `iframe`,
  formularios y hojas externas. Su `sha256` es el de lo saneado.
- **L5, trabajos**: cada réplica que ejecuta trabajos borra cada `lms.jobs.purge-every` (1 h) los terminados hace más de
  `lms.jobs.retention` (30 días); lo pendiente y lo reciente no se toca.

## 2026-10-31 — Fase 4: estudio editorial, revisión e importador ([#179](https://github.com/wasichai/elearning-backend/issues/179))

Fase 4 integrada: épicas E24–E28 ([#162](https://github.com/wasichai/elearning-backend/issues/162) …
[#178](https://github.com/wasichai/elearning-backend/issues/178)); diseño en
[ADR-0018](adr/0018-fase-4-estudio-revision-e-importador.md). Su criterio ([roadmap](product/roadmap.md)) aún no se da por
cumplido: faltan los recorridos e2e 8–12 contra el backend real
([elearning-frontend#60](https://github.com/wasichai/elearning-frontend/issues/60)) y la comprobación manual de la
importación con un capítulo de SRTM y uno de SGSPE.

- **Estudio** (`/api/lms/studio`): mis versiones y su detalle, solo las propias (`404` lo ajeno); enviar a revisión; versión
  nueva por copia de la última o de la indicada, con temario, evaluaciones y enlaces a recursos, que no toca la anterior ni
  a quien la cursa (#182, #188, #215).
- **Bandeja del revisor** (`/api/lms/review`): revisor asignado en `version_curso.revisor_id`, que pone solo quien publica,
  nunca al autor ni a sí mismo; comentarios por sección (`comentario_revision`, `apiOnly`); decisiones con motivo salvo
  aprobar en `lms.decision_revision`, de solo inserción y serializadas por versión; rechazar retira la versión; publicar
  exige `PUBLICAR` (#208). Diferencias calculadas contra la versión anterior, por orden de unidad y lección (#211).
- **Cola propia** `lms.trabajo` con `FOR UPDATE SKIP LOCKED`, reintentos con espera, reclamo que caduca y cierre solo por
  quien sigue siendo su dueño; la API responde mientras un trabajo bloquea (RNF-ARQ-09, #209).
- **Importador**: analizador puro del grafo `include::` con `leveloffset`, secciones, anclas, imágenes, referencias y
  defectos (#207); lectura por la API de GitHub de una lista de orígenes, en un commit exacto, de un host fijo y sin
  redirecciones: ninguna URL viene del usuario (#210); un borrador con una unidad por capítulo y una lección por sección,
  trazadas a `repo@sha:ruta#ancla`, sin el texto del manual y con su informe; nunca publica (#213).
- **Generación**: `fuente_recurso` de solo inserción produce HTML y PDF con Asciidoctor en modo seguro, en una carpeta
  propia, sin el entorno del servidor y con tiempo límite; cada artefacto guarda su fuente, los hashes y el generador, y
  cada generación es una `version_recurso` nueva en borrador (#212).
- **Archivos**: tipos admitidos por tipo de recurso y en las entregas, sobre lo que detecta `wasichai-files` (RNF-SEG-07,
  #206); MinIO en Compose, perfil `minio` y prueba S3 con el hash comprobado al descargar (RF-CON-02, #205).
- **Revisión de seguridad** del cierre (E29.2, #180): sin IDOR en el estudio, la bandeja ni la importación, sin
  autoaprobación ni decisiones de un revisor no asignado, y sin SSRF. Se corrigió que cualquier CREADOR leyera el trabajo de
  generación de otro, con su error y su versión (ahora `404`, como la importación). El resto queda en
  [#216](https://github.com/wasichai/elearning-backend/issues/216): decisiones por la API de registros sin
  `lms.decision_revision` (M1), trabajos sin clave ni tope (M2) y siete bajos.
- **Frontend**: `studioModule` (mis versiones, detalle, enviar, copiar e importar con seguimiento e informe) y
  `reviewModule` (bandeja, comentarios por sección, decisiones y publicar), ambos con diferencias
  ([front#69](https://github.com/wasichai/elearning-frontend/pull/69), [front#70](https://github.com/wasichai/elearning-frontend/pull/70),
  [front#73](https://github.com/wasichai/elearning-frontend/pull/73), [front#74](https://github.com/wasichai/elearning-frontend/pull/74)).

REST change: nuevo `/api/lms/studio/versions` (lista, detalle, `diff`, `submit`), `POST /api/lms/studio/courses/{id}/versions`,
`/api/lms/review/versions` (lista, detalle, `diff`, `comments`, `decision`, `publish`) y `POST /api/lms/review/comments/{id}/resolve`,
`/api/lms/studio/import` (`sources`, encolar, estado) y `POST /api/lms/studio/sources/{id}/render` con
`GET /api/lms/studio/renders/{id}`. Contrato en [docs/api/lms.md](api/lms.md).
New tests: backend 112 métodos `@Test` nuevos (estudio, bandeja, diferencias, cola, importador, generación, tipos de
archivo y S3), `model/` 2; frontend, las unitarias y e2e simulados de cada PR.

## 2026-10-31 — Rendimiento, filtros del catálogo y confirmaciones

Lo que no es de la Fase 4 y se entregó el mismo día; cierra pendientes de [#103](https://github.com/wasichai/elearning-backend/issues/103)
y parte de M1 de [#104](https://github.com/wasichai/elearning-backend/issues/104) y M4 de
[#183](https://github.com/wasichai/elearning-backend/issues/183).

- **Rendimiento**: el alcance de lectura (`ContentReadScope`, `EnrollmentReadScope`) se calcula una vez por petición, en
  el contexto de Reactor, con un tope de sentencias por unidad del aula medido en la prueba (#198). El roster y la cola del
  docente leen por lotes, sin instantáneas en la cola: menos de media sentencia por estudiante y por intento (#202).
- **Catálogo** (RF-CAT-08): filtros de duración, inscripción abierta y horas por tipo, en la API y en la interfaz (#201,
  [front#68](https://github.com/wasichai/elearning-frontend/pull/68)); «conocimientos previos» sigue pendiente porque es
  texto libre.
- **Confirmaciones y errores** (RNF-UX-08): empezar un intento y emitir el certificado se confirman, y los 403 y los
  errores de red se explican ([front#66](https://github.com/wasichai/elearning-frontend/pull/66)).
- **Requisitos** de las Fases 2 y 3 al día con lo integrado (#199).

REST change: `GET /api/lms/public/catalog/courses` admite `minWeeks`, `maxWeeks`, `enrollmentOpen`, `minTheoryHours`,
`minPracticeHours` y `minAutonomousHours`. Contrato en [docs/api/lms.md](api/lms.md).
New tests: backend 6 métodos `@Test` y 2 pruebas dinámicas de coste (`StatementCount`).

## 2026-10-31 — Hallazgos de las revisiones de las Fases 2 y 3 ([#104](https://github.com/wasichai/elearning-backend/issues/104), [#183](https://github.com/wasichai/elearning-backend/issues/183))

- **Matrícula**: estado, ventana, versión y cupo se comprueban con el cerrojo del cupo tomado (#189). Una `RETIRADA` no abre
  su aula, sus sesiones ni su finalización (#190), ni escribe en su intento abierto, igual que una `FINALIZADA` (#194).
- **Temario**: el de un borrador solo lo cambia su autor o quien publica (#187).
- **Eventos**: un `clientEventId` reutilizado con otro contenido es `409` (#186); el conteo de pruebas, por organización (#185).
- **Calificación**: corregir el cero automático de una pregunta sin responder exige motivo (#191).
- **Sesiones**: reprogramar, cancelar y pasar lista leen la sesión con su cerrojo; una cancelada no vuelve a reprogramarse
  (#192). El enlace rechaza IPv4 privadas escritas de otra forma (#193).
- **Permisos**: cada persona lee solo sus asignaciones docentes (#196).
- **`apply.py`**: el nombre de archivo se escapa en la subida (#185).
- **Decisiones** (ADR-0020): el certificado lleva el nombre tal como se escribe, asistir completa la lección y los eventos se
  guardan mientras el estudiante acepte los términos.

## 2026-10-31 — Fase 3: evaluaciones y operación docente ([#130](https://github.com/wasichai/elearning-backend/issues/130))

Criterio de la Fase 3 cumplido ([roadmap](product/roadmap.md)): el curso corto `CORTO-101` se completa de principio a
fin con su certificado emitido y verificado, por la API y en el navegador contra el backend real, con los recorridos 5,
6, 7 y 13, en local y en CI. Épicas E18–E23 ([#112](https://github.com/wasichai/elearning-backend/issues/112) …
[#130](https://github.com/wasichai/elearning-backend/issues/130)); diseño en
[ADR-0016](adr/0016-fase-3-evaluaciones-docente-sesiones-certificados.md) y
[ADR-0017](adr/0017-copia-del-contenido-de-evaluaciones.md). Los requisitos F3 que aún no cumplen su aceptación se
siguen en [#231](https://github.com/wasichai/elearning-backend/issues/231); lo corregido después de la revisión está en
«Hallazgos de las revisiones de las Fases 2 y 3» y en «Rendimiento, filtros del catálogo y confirmaciones», más arriba.

- **Docente por asignación**: `docente_edicion.usuario_id`; el docente lee las matrículas y el progreso solo de sus
  ediciones (#137) y su espacio lista sus ediciones y sus matriculados (#142).
- **Evaluaciones**: `evaluacion`, `pregunta` (seis tipos), `opcion` y `criterio_rubrica` se congelan con su versión y
  el estudiante no los lee (#143). Cada intento vive en `lms.intento` sobre una instantánea inmutable; el servidor
  impone intentos y plazo y corrige lo cerrado (#147). La corrección manual por pregunta deja `lms.calificacion`, de
  solo inserción, con motivo al cambiar una nota (#151); las entregas de archivo se congelan al enviar (#152). Una
  lección con evaluación se aprueba al aprobar un intento, calculado y no guardado (#153).
- **Sesiones**: series con hora local en la zona de la edición, también al cruzar el horario de verano, y enlace por el
  puerto `MeetingProvider` validado por `SafeLink` (#154); reprogramar y cancelar con motivo avisan a los matriculados
  de esa edición por `wasichai-notifications` y correo, con Mailpit en Compose (#155). La asistencia la pasa el docente
  asignado, no toca el progreso de materiales y completa la lección de su sesión (#158).
- **Finalización y certificado**: reglas declaradas por versión que se citan una a una (#159); el certificado se emite
  una vez al cumplirlas, con su instantánea en `lms.certificado`, y se verifica sin sesión (#160).
- **Demo**: curso corto `CORTO-101` con lectura, evaluación con rúbrica, sesión enlazada y docente asignado; la sesión
  se crea por el comando como ese docente (#161).
- **Revisión de seguridad** del cierre (E23.3): se corrigieron que cualquier docente leyera las soluciones de todos los
  cursos (M1), que un docente matriculado en su edición viera sus soluciones y se acreditara asistencia (M2), y que con
  varios intentos se aprobara enviando el primero en blanco (M3), además de tres bajos (#184,
  [ADR-0019](adr/0019-docente-sin-claves-y-soluciones-al-terminar.md)); y en el frontend, que una subida en curso pisara
  las respuestas (M5, [front#63](https://github.com/wasichai/elearning-frontend/pull/63)). El resto quedó en
  [#183](https://github.com/wasichai/elearning-backend/issues/183).
- **Frontend**: evaluaciones en el aula con cada tipo de pregunta, plazo y resultado
  ([front#42](https://github.com/wasichai/elearning-frontend/pull/42)); sesiones, finalización y certificado imprimible
  con verificación pública ([front#45](https://github.com/wasichai/elearning-frontend/pull/45)); `teachingModule` con
  sus ediciones y matriculados ([front#50](https://github.com/wasichai/elearning-frontend/pull/50)), corrección,
  sesiones y asistencia ([front#52](https://github.com/wasichai/elearning-frontend/pull/52), que entró en `main` con
  [front#61](https://github.com/wasichai/elearning-frontend/pull/61)); y el curso corto de punta a punta contra el
  backend real, con el aviso de reprogramar comprobado en Mailpit en el trabajo `e2e-real`
  ([front#61](https://github.com/wasichai/elearning-frontend/pull/61)).

REST change: nuevo `/api/lms/classroom/enrollments/{id}/lessons/{lessonId}/attempts`, `/api/lms/classroom/attempts/{id}`
(`answers`, `submit`, `questions/{questionId}/submission`), `GET /api/lms/classroom/enrollments/{id}/sessions` y
`/completion`, `POST /api/lms/classroom/enrollments/{id}/certificate`, `GET /api/lms/public/certificates/{code}` (sin
sesión) y `/api/lms/teaching` (`offerings`, `students`, `attempts`, `grades`, `sessions`, `reschedule`, `cancel`,
`attendance`). Contrato en [docs/api/lms.md](api/lms.md).
New tests: backend 216 al integrar la revisión (#184), `model/` 79 (antes 75); frontend 303 unitarias, 42 e2e con API
simulada y 11 contra el backend real (front#61), también en CI.

## 2026-10-31 — Pendientes de la Fase 2 ([#103](https://github.com/wasichai/elearning-backend/issues/103))

- **Roles**: una cuenta ESTUDIANTE + CREADOR tiene la unión exacta de permisos (RF-ROL-03, #111). Nadie se da privilegios
  a sí mismo: lo impide `MANAGE_ORGANIZATION`, solo de ADMIN, sin regla propia (RF-ROL-05, #135).
- **Arquitectura**: `ArchitectureTest` falla ante un SDK o un host concreto de IA, almacenamiento o videoconferencia fuera
  de su adaptador (RNF-ARQ-08, #138).
- **Recursos**: `recurso.descripcion` y una misma versión publicada usada en lecciones de dos cursos (RF-CON-01, #140).
- **Aula**: el umbral del vídeo `VITE_VIDEO_COMPLETION`, sobre lo reproducido y con `VIDEO_ENDED` una sola vez (RF-AUL-04,
  front#44); e2e que recorre el curso solo con «Siguiente» (RF-AUL-02, front#47); el catálogo admite la modalidad presencial
  (front#49).

## 2026-10-28 — Fase 2: el aula del estudiante

Criterio de la Fase 2 cumplido ([roadmap](product/roadmap.md)): un estudiante inicia sesión, se matricula desde la ficha,
recorre su curso por unidades, abre lecturas, PDF y vídeos, y conserva su progreso en el servidor. Épicas E12–E16
([#78](https://github.com/wasichai/elearning-backend/issues/78) … [#90](https://github.com/wasichai/elearning-backend/issues/90));
diseño en [ADR-0012](adr/0012-aula-del-estudiante.md) y [ADR-0013](adr/0013-estructura-publicada-visible-recursos-por-matricula.md). Los
requisitos F2 que aún no cumplen su aceptación se siguen en [#103](https://github.com/wasichai/elearning-backend/issues/103).

- **Matrícula** (`matricula`, `apiOnly`) solo por el comando `POST /api/lms/enrollments`, como la persona: ventana en la
  zona de la edición, versión publicada, cupo en `lms.ocupacion_edicion` bloqueado con `FOR UPDATE` en la misma
  transacción (probado con una carrera determinista que falla sin el cerrojo), idempotente.
- **Alcance por matrícula, no por rol**: el estudiante lee sus matrículas y su progreso; la estructura de lo publicado
  (ADR-0013, porque una relación escrita debe apuntar a algo legible); los recursos y sus archivos, solo de lo que cursa.
- **Recursos versionados** (`recurso`, `version_recurso`, `recurso_leccion`) con su archivo en `wasichai-files` (local en
  desarrollo, S3 por configuración), el mismo flujo editorial que el curso e inmutables con su archivo al publicarse.
- **Progreso con evidencia** (`progreso_leccion`, `apiOnly`): solo avanza; lectura, presentación y laboratorio al
  marcarlas, vídeo al terminar, evaluación y sesión en F3; eventos crudos en `lms.evento_aprendizaje`, solo inserción
  (disparadores) e idempotentes por `clientEventId`, serializados por lección con un cerrojo consultivo.
- **API del aula**: mis cursos y el aula de una matrícula propia (404 también para un docente), con progreso por unidad.
- **Demo del aula**: lectura HTML propia, guía PDF y vídeo sintéticos, una lección de vídeo, el estudiante demo
  matriculado por el comando y fechas relativas al día de aplicación (#96); `apply.py` sube archivos y matricula.
- **Revisión de seguridad** del cierre (E16.3): sin IDOR ni saltos de la guarda; se corrigieron dos hallazgos medios
  ([ADR-0014](adr/0014-docente-sin-ver-todas-y-recurso-congelado.md)): el docente ya no ve matrículas ni progreso de
  todos (hasta el alcance por asignación de F3) y un recurso que salió del borrador no cambia de título ni de tipo. El
  resto queda registrado: coste del alcance por petición y otros bajos en
  [#104](https://github.com/wasichai/elearning-backend/issues/104); endurecimiento de blobs y del HTML en
  [elearning-frontend#34](https://github.com/wasichai/elearning-frontend/issues/34).
- **Frontend**: `classroomModule` («Mis cursos», aula por unidades con HTML en `iframe` con `sandbox=""`, progreso,
  matrícula desde la ficha) y recorridos e2e contra el backend real en CI (trabajo `e2e-real`).

REST change: nuevo `POST /api/lms/enrollments`, `GET /api/lms/classroom/enrollments`,
`GET /api/lms/classroom/enrollments/{id}` y `POST /api/lms/classroom/enrollments/{id}/lessons/{lessonId}/events` (con
sesión). Contrato en [docs/api/lms.md](api/lms.md).
New tests: backend 133 (antes 93), `model/` 75 (antes 58), frontend 146 unitarias (antes 68), 22 e2e con API simulada
y 8 contra el backend real (5 del catálogo, 3 del aula), también en CI.

## 2026-10-28 — Primer incremento: catálogo público de punta a punta

Fase 1 completa ([roadmap](product/roadmap.md)): un visitante anónimo busca, filtra y ordena los cursos publicados y abre
su ficha con temario, ediciones en su zona horaria, docentes, carga formativa y créditos **propuestos**. Épicas E2–E10
([#11](https://github.com/wasichai/elearning-backend/issues/11) … [#33](https://github.com/wasichai/elearning-backend/issues/33)),
un PR por paso en este repositorio y en [elearning-frontend](https://github.com/wasichai/elearning-frontend).

- **Plataforma**: aplicación Spring Boot 4.1.1 WebFlux sobre Wasichai 1.6.0 (GitHub Packages), PostgreSQL 18 en Compose,
  prueba de arquitectura (sin JPA, sin internos de Wasichai, sin literales de rol, DAG de capacidades).
- **Modelo** como Custom Objects (`model/model.json`, 8 objetos) aplicado por `model/apply.py`, idempotente y contrastado
  con el servidor real; roles ESTUDIANTE, DOCENTE, CREADOR y REVISOR; datos demo A (SRTM), B (SGSPE) y C (IA aplicada),
  marcados como ilustrativos y con referencias verificadas a los manuales.
- **Carga y créditos**: `workload` (dominio puro) con políticas ECTS (25–30 h) y SCH (34 CFR 600.2); siempre propuesta.
- **Catálogo**: cadena de seguridad pública acotada (ADR-0006) y proyección `lms.catalogo_publicado` mantenida en segundo
  plano (ADR-0011, porque `RecordService.asPlatform` lanza dentro de una petición); contrato OpenAPI con springdoc 3.2.0.
- **Flujo editorial** (`EditorialWorkflowGuard`): transiciones con su acción, sin autoaprobación, contenido y temario
  solo en borrador y por su autor. Lo destapó la revisión de seguridad: antes, un CREADOR podía publicar por la API de
  registros.
- **Frontend**: `catalogModule` registrado en `<WasichaiApp>` (rutas públicas), Zod para el contrato, es/en, e2e con
  Playwright (API simulada y humo contra el backend real), CI en verde en ambos repositorios.

REST change: nuevo `GET /api/lms/public/catalog/courses`, `GET /api/lms/public/catalog/courses/{slug}` y
`GET /api/lms/public/openapi` (anónimos, solo lectura). Contrato en [docs/api/lms.md](api/lms.md).
New tests: backend 93 (unitarias, arquitectura e integración con Testcontainers), `model/` 58, frontend 68 unitarias,
10 e2e y 5 de humo contra el backend real.

## 2026-10-27 — Fase 0: investigación y gobierno del repositorio

Arranque del proyecto sobre Wasichai 1.6.0 ([épica E0](https://github.com/wasichai/elearning-backend/issues/1),
[épica E1](https://github.com/wasichai/elearning-backend/issues/4)). Wrapper de Gradle 9.8.2 con sumas verificadas;
análisis verificado de los repositorios de referencia (`docs/research/reference-repositories.md`); reglas del proyecto
(`CLAUDE.md`), `.editorconfig`, commitlint, `.env.example`, política de seguridad y de contribución. Cambio REST:
ninguno. Pruebas nuevas: ninguna (aún no hay código).
