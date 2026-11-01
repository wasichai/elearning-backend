# Historial de cambios

Lo más nuevo primero. El razonamiento de arquitectura vive en `docs/adr/`; este archivo registra lo que se entregó.

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
