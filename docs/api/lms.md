# API del LMS

Contrato HTTP propio de wasichai-learning bajo `/api/lms/**`. La API de la plataforma (`/api/objects`, `/api/auth`, …) es la de
[Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/api/rest.md). El frontend valida estas respuestas con esquemas Zod.

La ruta pública se lee de la proyección `lms.catalogo_publicado` ([ADR-0011](../adr/0011-catalogo-publico-leido-de-una-proyeccion.md)): un cambio
publicado aparece en, como mucho, `lms.catalog.refresh` (30 s por defecto).

Anónimo, solo `GET`, `application/json`. Errores en RFC 7807 `application/problem+json`
(`{ type, title, status, detail, errors?: [{ field, message }] }`). Fechas `YYYY-MM-DD` (fecha civil en la zona de la
edición), instantes ISO-8601 UTC, decimales como número JSON. Nombres técnicos en inglés; valores de enumeraciones tal
como el modelo (`INICIAL`, `ASINCRONA`, …).

### GET /api/lms/public/catalog/courses

Parámetros (todos opcionales):

| nombre                  | tipo                                                   | regla                                                              |
| ----------------------- | ------------------------------------------------------ | ------------------------------------------------------------------ |
| `q`                     | texto ≤ 100                                            | búsqueda sin distinguir mayúsculas en título y resumen             |
| `category`              | slug                                                   | slug de la categoría                                               |
| `level`                 | `INICIAL` \| `INTERMEDIO` \| `AVANZADO`                |                                                                    |
| `modality`              | `ASINCRONA` \| `SINCRONA` \| `HIBRIDA` \| `PRESENCIAL` |                                                                    |
| `startsFrom`            | `YYYY-MM-DD`                                           | con una edición abierta o programada que empieza ese día o después |
| `minWeeks`              | entero 1–26                                            | duración (`weeks`) mayor o igual                                   |
| `maxWeeks`              | entero 1–26, ≥ `minWeeks`                              | duración (`weeks`) menor o igual; un semestral dura de 12 a 26     |
| `enrollmentOpen`        | `true` \| `false`                                      | `true`: alguna edición con `enrollmentOpen` hoy; `false`: ninguna  |
| `minTheoryHours`        | número 0–10000, ≤ 2 decimales                          | `workload.theoryHours` (horas totales de teoría) mayor o igual     |
| `minPracticeHours`      | número 0–10000, ≤ 2 decimales                          | `workload.practiceHours` (práctica o laboratorio) mayor o igual    |
| `minAutonomousHours`    | número 0–10000, ≤ 2 decimales                          | `workload.autonomousHours` (trabajo autónomo) mayor o igual        |
| `prerequisiteKnowledge` | clave (minúsculas, dígitos, guiones) \| `none`         | la clave en `prerequisiteKnowledgeTags`; `none`: ninguna clave     |
| `sort`                  | `upcoming` (defecto) \| `title` \| `duration`          | `upcoming`: próxima edición primero, sin edición al final          |
| `page`                  | entero ≥ 0 (defecto 0)                                 |                                                                    |
| `size`                  | 1–50 (defecto 12)                                      | fuera de rango → **400** con `errors[{field:"size"}]`              |

Los filtros se combinan (intersección). Un valor inválido (enum desconocido, fecha mal formada, `size` fuera de rango,
`q` > 100, semanas fuera de 1–26 o `minWeeks` > `maxWeeks`, `enrollmentOpen` distinto de `true`/`false`, horas negativas,
con más de dos decimales o en notación científica, `prerequisiteKnowledge` que no es una clave ni `none`) → **400** con
`errors`; cada error nombra su parámetro. Una clave bien formada que ningún curso declara no es un error: lista vacía.

Filtros de RF-CAT-08: `enrollmentOpen` usa la misma regla que el campo `enrollmentOpen` de la ficha (ver más abajo), en la
zona horaria de cada edición; una edición completa (`full`, RF-CAT-09) no cuenta como abierta. Las horas son las totales de la ficha
(`horas_*_semana × semanas`); las horas supervisadas no tienen filtro. `prerequisiteKnowledge` filtra por las claves
estructuradas que declaró la versión publicada (`version_curso.conocimientos_previos_claves`, del vocabulario
`conocimiento_previo`, [ADR-0028](../adr/0028-conocimientos-previos-como-claves-de-la-version.md)), no por el texto libre
de la ficha: `none` son los cursos cuya versión publicada no declara ninguna clave.

Respuesta 200:

```json
{
  "content": [CourseSummary],
  "page": 0, "size": 12, "totalElements": 3, "totalPages": 1,
  "facets": {
    "categories": [{ "slug": "gestion-municipal", "name": "Gestión municipal", "count": 2 }],
    "prerequisiteKnowledge": [{ "key": "navegador-web", "name": "Manejo de un navegador web", "count": 1 }]
  }
}
```

`facets.categories` cuenta los cursos publicados por categoría con todos los filtros **salvo** `category`, y
`facets.prerequisiteKnowledge` los cursos por clave con todos los filtros **salvo** `prerequisiteKnowledge` (para pintar
filtros). Una clave que ningún curso de esa consulta declara no aparece.

`CourseSummary`:

```json
{
  "slug": "srtm-anuncios",
  "code": "SRTM-ANU-101",
  "title": "…",
  "summary": "…",
  "language": "es",
  "level": "INICIAL",
  "modality": "ASINCRONA",
  "category": { "slug": "…", "name": "…" }, // o null
  "weeks": 4,
  "workload": { "totalHours": 48, "contactHours": 24, "autonomousHours": 24 },
  "nextOffering": {
    // o null si no hay edición próxima/abierta
    "code": "edicion-srtm-2026-11",
    "status": "INSCRIPCION_ABIERTA",
    "startDate": "2026-11-09",
    "endDate": "2026-12-04",
    "timeZone": "America/Lima",
    "enrollmentOpen": true,
    "enrollmentStatus": "OPEN"
  },
  "illustrative": true
}
```

### GET /api/lms/public/catalog/courses/{slug}

404 (problem+json, `detail: "Curso no encontrado"`) si el slug no existe, no tiene versión publicada o es de otra
organización — siempre la misma respuesta.

Respuesta 200 `CourseDetail` = todos los campos de `CourseSummary` (salvo `nextOffering`) más:

```json
{
  "prerequisiteKnowledge": "…", // texto libre o null
  "prerequisiteKnowledgeTags": [{ "key": "navegador-web", "name": "Manejo de un navegador web" }], // las claves de la versión, [] si ninguna
  "version": { "number": 1, "publishedAt": "2026-10-20T15:00:00Z" }, // publishedAt puede ser null
  "objectives": ["…"],
  "learningOutcomes": ["…"], // líneas no vacías
  "requirements": "…",
  "assessment": "…",
  "certification": "…", // cada uno o null
  "sources": ["…"],
  "workload": {
    "weeks": 6,
    "weekly": { "theory": 12, "practice": 12, "supervised": 0, "autonomous": 12 },
    "theoryHours": 72,
    "practiceHours": 72,
    "supervisedHours": 0,
    "autonomousHours": 72,
    "contactHours": 144,
    "directedHours": 144,
    "totalHours": 216
  },
  "credits": {
    "system": "ECTS", // "ECTS" | "SCH" | "PE_CREDITO" | null (NINGUNO)
    "approved": null, // número solo con una aprobación vigente (RF-CRE-08)
    "approval": null, // { credits, policyId, policyVersion, approvingBody, documentReference, documentDate, approvedAt } o null
    "proposal": {
      // null si system es null
      "system": "ECTS",
      "policyId": "ects-carga-total",
      "policyVersion": "1",
      "method": "…",
      "parameters": { "horasPorCredito": "27", "paso": "0.5" },
      "source": "…",
      "totalHours": 216,
      "calculated": 8,
      "proposed": 8,
      "warnings": [],
      "status": "PROPUESTO"
    }
  },
  "units": [
    {
      "order": 1,
      "title": "…",
      "description": "…",
      "objectives": ["…"],
      "estimatedHours": 6,
      "lessons": [{ "order": 1, "title": "…", "type": "LECTURA", "durationMinutes": 30, "summary": "…", "source": "wasichai/srtm-backend@6daacd3:…#…" }]
    }
  ],
  "offerings": [
    {
      // solo PROGRAMADA, INSCRIPCION_ABIERTA o EN_CURSO que no han terminado; por fecha de inicio
      "code": "…",
      "status": "INSCRIPCION_ABIERTA",
      "startDate": "2026-11-09",
      "endDate": "2026-12-04",
      "timeZone": "America/Lima",
      "enrollmentFrom": "2026-10-15",
      "enrollmentUntil": "2026-11-06",
      "enrollmentOpen": true,
      "enrollmentStatus": "OPEN", // "OPEN" | "CLOSED" | "FULL"
      "full": false, // cupo agotado
      "capacity": 30,
      "schedule": "…",
      "teachers": [{ "name": "…", "professionalTitle": "…", "role": "TITULAR" }]
    }
  ],
  "teachers": [{ "name": "…", "professionalTitle": "…", "bio": "…", "specialties": "…", "ratings": null }]
}
```

`teachers[].ratings` (ADR-0038 §12, RF-REP-06): el agregado de las valoraciones del docente, solo si su perfil es
público **y** consiente `valoraciones_publicas` (falso por omisión): media y n por dimensión de sus ediciones liberadas
(cifras congeladas con n ≥ `lms.reputation.min-sample`, sumadas con la media ponderada por n), con `criterion`, el
enlace a su definición (`/api/lms/public/ratings/criterion`), y `criterionVersion`. Si no, `null`. Nunca comentarios.

`enrollmentStatus` (RF-CAT-09, [ADR-0023](../adr/0023-cupo-agotado-en-la-proyeccion-del-catalogo.md)) = `CLOSED` si la
edición no está en `INSCRIPCION_ABIERTA` o la fecha de hoy (en la zona de la edición) cae fuera de
[`enrollmentFrom`, `enrollmentUntil`] cuando existen; si no, `FULL` cuando `full` y `OPEN` en otro caso. `enrollmentOpen`
= `enrollmentStatus` es `OPEN`. `full` = los matriculados alcanzan `capacity` (sin `capacity`, siempre `false`): la ficha
dice solo eso, nunca cuántos ni quiénes se matricularon; llega con el refresco de la proyección, así que el comando de
matrícula puede responder «sin cupo» antes de que la ficha lo diga. `full`, `enrollmentStatus` y el `enrollmentStatus`
de `nextOffering` son campos añadidos: un cliente que no los lee sigue funcionando. Los créditos son siempre una **propuesta**: la interfaz debe decir
«créditos propuestos, no oficiales» salvo que `approved` tenga valor, y entonces citar el órgano y el documento de
`approval`: la plataforma solo registra el reconocimiento de la institución (ADR-0035).

## Valores y nulos

- **Enumeraciones** (las del modelo, `model/model.json`): `level` `INICIAL` \| `INTERMEDIO` \| `AVANZADO`; `modality`
  `ASINCRONA` \| `SINCRONA` \| `HIBRIDA` \| `PRESENCIAL`; edición `status` `PROGRAMADA` \| `INSCRIPCION_ABIERTA` \| `EN_CURSO` (las
  `FINALIZADA` y `CANCELADA` no se publican); edición `enrollmentStatus` `OPEN` \| `CLOSED` \| `FULL` (propio del
  catálogo, no del modelo); lección `type` `LECTURA` \| `VIDEO` \| `PRESENTACION` \| `LABORATORIO` \|
  `EVALUACION` \| `SESION`; docente `role` `TITULAR` \| `ASISTENTE`. Un valor nuevo llega primero al modelo y aquí.
- **Pueden ser `null`**: `summary`, `category`, `nextOffering`, `prerequisiteKnowledge`, `version.publishedAt`,
  `requirements`, `assessment`, `certification`, `credits.system`, `credits.approved`, `credits.approval`, `credits.proposal`;
  en unidades `description` y `estimatedHours`; en lecciones `durationMinutes`, `summary` y `source`; en ediciones
  `enrollmentFrom`, `enrollmentUntil`, `capacity` y `schedule`; en docentes `professionalTitle`, `bio` y `specialties`.
  Las listas nunca son `null` (como mucho, vacías).
- `credits.proposal.parameters` es un mapa de texto a texto (`"27"`, no `27`).
- `weeks` y `workload.weeks` son el mismo valor: de 1 a 8, o de 12 a 26 en un curso semestral (RF-ACA-05).
- `sort` no admite dirección: cada orden tiene la suya (próximos y duración ascendentes, título alfabético).

## Contrato OpenAPI

`GET /api/lms/public/openapi` (público) sirve el contrato OpenAPI 3 de `/api/lms/**` generado por springdoc 3.2.0
([ADR-0008](../adr/0008-openapi-con-springdoc.md)); no incluye la API de Wasichai.

## Aula del estudiante (F2, ADR-0012)

Con sesión (`Authorization: Bearer …`). Un identificador ajeno —matrícula, lección o recurso de otra persona o de una
versión en la que no se está matriculado— responde `404`, igual que uno inexistente.

### POST /api/lms/enrollments

Cuerpo `{ "offering": "<código de la edición>" }`. Cabecera opcional `Idempotency-Key`.

- `201` → `Enrollment` (nueva) · `200` → la misma `Enrollment` si ya existía.
- `404` edición desconocida · `409` (`problem+json`, `detail` legible) inscripción cerrada, sin cupo, edición no
  disponible o prerrequisito sin cumplir · `401` sin sesión · `403` sin permiso de matricularse.
- Sin aceptación vigente de los términos de uso ([Términos de uso](#términos-de-uso-y-supresión-adr-0037)), `403` antes
  que nada, también para repetir una matrícula, con `detail` «Para matricularte debes aceptar los términos de uso
  vigentes (…)».
- Prerrequisitos (RF-ACA-08): si la versión de la edición declara cursos previos (`cursos_previos`), cada uno exige una
  matrícula propia `FINALIZADA` con resultado `APROBADO` en cualquier versión de ese curso. Si falta alguno, `409` con
  `detail` «Para matricularte en esta edición antes debes aprobar: `<código> (<título>)`; …», que nombra todos los que
  faltan; un código que no corresponde a ningún curso se nombra igual y nunca se cumple. Se comprueban después del
  estado y la ventana y antes del cupo.

`Enrollment`:

```json
{
  "id": "…",
  "status": "ACTIVA",
  "enrolledAt": "2026-10-28T15:00:00Z",
  "course": { "slug": "srtm-anuncios", "code": "SRTM-ANU-101", "title": "…" },
  "version": 1,
  "offering": { "code": "…", "status": "INSCRIPCION_ABIERTA", "startDate": "2026-11-09", "endDate": "2026-11-27", "timeZone": "America/Lima" },
  "progress": { "completedLessons": 0, "totalLessons": 14, "percent": 0 },
  "nextLesson": { "id": "…", "title": "…", "unitOrder": 1, "lessonOrder": 1 }
}
```

`nextLesson` es la primera lección no completada en orden (unidad, lección), sin contar las `NOT_YET`, o `null` si no
queda ninguna. `percent` es entero (0–100) sobre las lecciones que se pueden completar en F2 (las mismas).

### GET /api/lms/classroom/enrollments

Mis matrículas `ACTIVA` y `FINALIZADA` (F3; esta última en solo lectura: eventos e intentos nuevos → `409`) → `Enrollment[]`, la de inicio más próximo primero. Solo las propias, también para quien puede
ver todas las matrículas (un docente): el aula es del estudiante.

### GET /api/lms/classroom/enrollments/{id}

El aula de una matrícula propia; ajena, inexistente o `RETIRADA` → `404`, también para un docente. Una `FINALIZADA` sigue
legible. Lo mismo vale para sus sesiones y su finalización.

`Classroom` = `Enrollment` más:

```json
{
  "units": [
    {
      "id": "…",
      "order": 1,
      "title": "…",
      "description": "…",
      "progress": { "completedLessons": 1, "totalLessons": 4, "percent": 25 },
      "lessons": [
        {
          "id": "…",
          "order": 1,
          "title": "…",
          "type": "LECTURA",
          "durationMinutes": 30,
          "summary": "…",
          "completion": "EXPLICIT",
          "progress": { "status": "COMPLETADA", "startedAt": "…", "completedAt": "…", "evidence": "FINALIZACION_EXPLICITA" },
          "resources": [
            {
              "id": "…",
              "title": "…",
              "type": "HTML",
              "version": 1,
              "file": { "name": "leccion.html", "contentType": "text/html", "size": 2048, "url": "/api/objects/version_recurso/records/<id>/files/archivo" }
            }
          ],
          "assessment": null,
          "lab": null
        },
        {
          "id": "…",
          "order": 2,
          "title": "…",
          "type": "LABORATORIO",
          "lab": {
            "objective": "…",
            "requirements": ["…"],
            "milestones": ["…", "…"],
            "acceptanceCriteria": ["…"],
            "deliverables": ["…"],
            "environments": [{ "id": "…", "title": "Entorno de práctica", "url": "https://lab.example.org/…" }]
          }
        }
      ]
    }
  ],
  "achievements": [
    { "kind": "UNIT_COMPLETED", "at": "2026-11-03T15:20:00Z", "ref": "<id de la unidad>" },
    { "kind": "ASSESSMENT_PASSED", "at": "2026-11-04T10:05:00Z", "ref": "<id de la evaluación>" },
    { "kind": "CERTIFICATE_ISSUED", "at": "2026-11-05T09:00:00Z", "ref": "K7Q2-M9XW-4RTB" }
  ]
}
```

- `achievements` (RF-AUL-08): los logros de la matrícula, derivados al leer el aula de lo que ella misma muestra; no se
  guardan ni se escriben, y no cuestan otra lectura. Del más antiguo al más reciente (uno sin `at`, al final):
  - `UNIT_COMPLETED`: la unidad tiene completadas todas sus lecciones contables (las que no son `NOT_YET`; su `progress`
    es `completedLessons == totalLessons > 0`). `ref` es el `id` de la unidad; `at`, el `completedAt` más reciente de esas
    lecciones.
  - `ASSESSMENT_PASSED`: la evaluación está aprobada por un intento calificado (`GRADED`), el que muestra su calificación
    en `assessment.best`; un intento enviado con preguntas por corregir no cuenta. `ref` es el `id` de la evaluación
    (`assessment.id` de su lección); `at`, el envío del primer intento que aprobó.
  - `CERTIFICATE_ISSUED`: hay certificado emitido. `ref` es su código (`certificateCode`); `at`, su `issuedAt`.
- `completion`: cómo se completa la lección — `EXPLICIT` (el estudiante la marca: `LECTURA`, `PRESENTACION`,
  `LABORATORIO`), `VIDEO_END` (al terminar el vídeo), `NOT_YET` (`EVALUACION`, `SESION`: llegan en F3).
- `progress.status`: `NO_INICIADA` · `INICIADA` · `COMPLETADA` · `APROBADA` (F3). `evidence` solo cuando está completada:
  `FINALIZACION_EXPLICITA` (la marcó el estudiante) o `FIN_DE_VIDEO`. `startedAt`, `completedAt` y `evidence` son `null`
  cuando no aplican.
- El `id` de un recurso es el de su **versión** (la que usa la lección); `version` es su número.
- `file` puede ser `null` (recurso sin archivo); su `url` es siempre una ruta del mismo origen,
  `/api/objects/version_recurso/records/<id>/files/archivo`: la descarga de `wasichai-files`, con la misma sesión y el
  mismo alcance que el registro. Un
  `HTML` se muestra en un `iframe` con `sandbox` sin `allow-scripts`; `PDF` se descarga; `VIDEO` se reproduce.
- `lab` (RF-LAB-01, ADR-0027): solo en una lección `LABORATORIO`; `null` en las demás. `objective` puede ser `null`;
  `requirements`, `milestones` (en orden), `acceptanceCriteria` y `deliverables` son los campos «uno por línea» de la
  lección, sin las líneas vacías. La guía y los archivos son sus `resources`; la entrega y la rúbrica, su `assessment`.
- `lab.environments`: los `entorno_laboratorio` de la lección en su `orden`, leídos como el estudiante (solo los de sus
  matrículas, `ContentReadScope`). `url` es siempre `https` hacia un host público: escribir un `enlace` `http`, `file:`,
  con credenciales o hacia una dirección interna es `400` en `enlace` (`SafeLink`, RF-LAB-03), también para la plataforma.
  La interfaz lo abre en otra pestaña con `rel="noopener noreferrer"`; el servidor nunca lo visita.
- Subir a una versión de recurso un archivo cuyo tipo detectado no admite el `tipo` del recurso es `400` en `archivo`
  (lista en [seguridad §4.4](../security/README.md#44-archivos-f2-en-adelante), RNF-SEG-07).

### POST /api/lms/classroom/enrollments/{id}/lessons/{lessonId}/events

Cuerpo `{ "type": "STARTED" | "VIDEO_ENDED" | "MARKED_COMPLETE", "clientEventId": "<uuid>" }` → `200` con el
`progress` de la lección. Idempotente por `clientEventId`: repetir el mismo evento devuelve el progreso actual sin cambiar
nada (el cliente genera uno por intento y lo reutiliza al reintentar la misma acción). El mismo `clientEventId` con
otra matrícula, lección o tipo es `409` y no registra nada.

- `STARTED` vale en cualquier lección, también `NOT_YET`; en una ya iniciada o completada no cambia nada.
- `MARKED_COMPLETE` (lectura, presentación, laboratorio) o `VIDEO_ENDED` (vídeo) completan, aunque no se haya enviado
  `STARTED`; `startedAt` toma entonces el mismo instante.
- Solo la persona matriculada registra su progreso, sobre su matrícula `ACTIVA` (otra → `409`). Los eventos llegan a la
  vez sin carreras: uno por lección a la vez.
- `400` si `type` no es uno de los tres o `clientEventId` no es un UUID.
- `403` sin aceptación vigente de los términos de uso, sobre una matrícula propia y antes que las demás reglas («Para
  registrar tu progreso debes aceptar los términos de uso vigentes (…)»); una ajena sigue siendo `404`.

- `409` si la regla no se cumple: `MARKED_COMPLETE` en un vídeo o en una lección `NOT_YET`; `VIDEO_ENDED` en una
  lección que no es vídeo. El estado nunca retrocede.
- `404` matrícula o lección ajena, o lección de otra versión.

## Evaluaciones, sesiones y certificado del estudiante (F3, ADR-0016)

Con sesión, siempre sobre una matrícula propia: lo ajeno o inexistente responde `404`. Instantes en ISO 8601 con zona
(`Z`); fechas civiles `YYYY-MM-DD`; horas locales `HH:mm` en la zona de la edición.

### Cambios en `Classroom`

- `completion` gana `ASSESSMENT` (se completa al aprobar su evaluación) y `ATTENDANCE` (al asistir a su sesión).
  `NOT_YET` queda para una lección de evaluación o sesión aún sin definir. `percent` y `nextLesson` cuentan todas las
  lecciones salvo las `NOT_YET`.
- Una lección con evaluación lleva `assessment`:

```json
{
  "id": "…",
  "title": "Evaluación final",
  "maxAttempts": 2,
  "timeLimitMinutes": 30,
  "passingPercent": 70,
  "attemptsUsed": 1,
  "openAttemptId": null,
  "best": { "percent": 80, "passed": true, "pending": false }
}
```

`timeLimitMinutes` y `best` pueden ser `null`. `progress.status` es `APROBADA` cuando un intento aprobó.

### Intentos

| Método | Ruta                                                                        | Resultado                                               |
| ------ | --------------------------------------------------------------------------- | ------------------------------------------------------- |
| POST   | `/api/lms/classroom/enrollments/{id}/lessons/{lessonId}/attempts`           | `201` intento nuevo · `200` el abierto si ya hay uno    |
| GET    | `/api/lms/classroom/attempts/{attemptId}`                                   | `Attempt`                                               |
| PUT    | `/api/lms/classroom/attempts/{attemptId}/answers`                           | guarda respuestas (solo abierto y en plazo) → `Attempt` |
| POST   | `/api/lms/classroom/attempts/{attemptId}/submit`                            | envía y califica lo automático → `Attempt`              |
| POST   | `/api/lms/classroom/attempts/{attemptId}/questions/{questionId}/submission` | crea la entrega de una pregunta `FILE` → `Submission`   |

`PUT answers` recibe `{ "answers": [{ "questionId", "optionIds": [], "text": null, "submissionId": null }] }` con todas
las respuestas: reemplaza a las guardadas. Un intento vencido se cierra con lo guardado: sus escrituras responden `409` y
su lectura ya trae el resultado. Su `submittedAt` es el plazo, no el momento en que se tocó; el que nadie toca lo cierra un
barrido periódico en la cola de trabajos (`lms.assessment.sweep-every`, 1 min) y llega así a la cola del docente y al roster.

`409` (`detail` legible): intentos agotados, plazo vencido (el servidor lo comprueba; el envío tardío se rechaza),
intento ya enviado, lección sin evaluación o matrícula no activa. `400`: respuesta que no corresponde al tipo de pregunta.

`Attempt`:

```json
{
  "id": "…",
  "number": 1,
  "status": "OPEN",
  "startedAt": "…",
  "deadline": "…",
  "submittedAt": null,
  "questions": [
    {
      "id": "…",
      "order": 1,
      "type": "SINGLE",
      "prompt": "…",
      "points": 2,
      "options": [{ "id": "…", "text": "…" }],
      "rubric": []
    }
  ],
  "answers": [{ "questionId": "…", "optionIds": ["…"], "text": null, "submissionId": null }],
  "result": null
}
```

- `type`: `SINGLE` · `MULTIPLE` · `TRUE_FALSE` (dos opciones) · `SHORT` (texto corto, se compara sin mayúsculas ni
  espacios extremos) · `OPEN` (texto, se corrige con la rúbrica) · `FILE` (una entrega). `rubric`
  (`[{ "description", "points" }]`) solo en `OPEN` y `FILE`. Las opciones salen en el orden de la instantánea (barajado
  por intento si la evaluación lo pide).
- `status`: `OPEN` · `SUBMITTED` (enviado, con preguntas pendientes de corrección manual) · `GRADED`.
- **Mientras está abierto no hay soluciones ni puntos ganados.** `result` tras enviar:

```json
{
  "score": 7,
  "maxScore": 10,
  "percent": 70,
  "passed": true,
  "pending": false,
  "questions": [{ "questionId": "…", "points": 2, "maxPoints": 2, "correct": true, "comment": null, "solution": null, "reviewLessonId": "…" }]
}
```

`percent` y `passed` son `null` mientras `pending`. `solution` (`{ "optionIds": [...], "acceptedAnswers": [...] }`) solo si la
evaluación muestra las soluciones al enviar (`mostrar_soluciones: AL_ENVIAR`) **y la persona ya aprobó o agotó sus intentos**:
antes servirían para aprobar el siguiente. Con `NUNCA`, siempre `null`.

`reviewLessonId` es la lección que repasa la pregunta (`leccion_repaso` de `pregunta`, #260), o `null` si no tiene. Sale
con el resultado, una vez enviado el intento, sea cual sea `mostrar_soluciones`: no es una solución, solo dice dónde repasar,
y es una lección de la misma versión que el estudiante ya puede abrir. Viene de la instantánea: no cambia aunque cambie el
temario. Nunca está en `questions[]` del intento abierto.

En `answers[]`, `submission` es `null` o `{ "id", "file": null | { "name", "contentType", "size", "url" } }`, tanto para el
estudiante como para el docente. `deadline` es `null` sin tiempo límite. En `result`, una pregunta pendiente trae `points` y
`correct` a `null`; `points` admite decimales. Una pregunta abierta o de archivo sin responder vale 0 y no queda pendiente.
En `assessment.best`, `percent` y `passed` son `null` mientras `pending`.

`Submission`: `{ "id", "file": null | { "name", "contentType", "size", "url" }, "uploadUrl" }`. El archivo se sube con
`POST uploadUrl` (multipart, parte `file`, máx. 25 MB) y la misma sesión; queda fijo al enviar el intento. Solo
admite PDF, zip, texto (`text/plain`, `text/csv`), imágenes (PNG, JPEG, GIF, WebP) y ofimática (OOXML, ODF) según el
tipo que detecta `wasichai-files`; lo demás es `400` en `archivo` (RNF-SEG-07).

### Sesiones

`GET /api/lms/classroom/enrollments/{id}/sessions` → `Session[]` por inicio:

```json
{
  "id": "…",
  "title": "…",
  "type": "TEORIA",
  "modality": "VIRTUAL",
  "start": "2026-11-10T13:00:00Z",
  "end": "2026-11-10T14:30:00Z",
  "timeZone": "America/Lima",
  "status": "PROGRAMADA",
  "link": "https://…",
  "location": null,
  "lessonId": null,
  "originalSessionId": null,
  "attendance": null
}
```

`type`: `TEORIA` · `LABORATORIO` · `CONSULTA` · `RECUPERACION`. `modality`: `PRESENCIAL` · `VIRTUAL`. `status`:
`PROGRAMADA` · `REPROGRAMADA` · `CANCELADA`. `attendance`: `null` o `PRESENTE` · `TARDE` · `AUSENTE` · `JUSTIFICADO`. El
enlace solo es `https`.

### Panel del estudiante

`GET /api/lms/classroom/panel?page=0&size=25` → `ClassroomPanelItem[]`: una página de «Mis cursos» (las mismas matrículas
y en el mismo orden que `GET /api/lms/classroom/enrollments`), cada una con lo que antes se leía aparte por matrícula
(#261). `page` y `size` como en el roster: `size` vale 25 si falta y se acota a 1–200, `page` a ≥ 0; una página con
menos de `size` elementos es la última. Solo las matrículas propias, también para un docente.

`ClassroomPanelItem` = `Enrollment` más:

```json
{
  "upcomingSessions": [{ "id": "…", "title": "…", "start": "…", "end": "…", "status": "PROGRAMADA", "attendance": null }],
  "pendingAssessments": [
    {
      "lessonId": "…",
      "assessment": {
        "id": "…",
        "title": "…",
        "maxAttempts": 2,
        "timeLimitMinutes": null,
        "passingPercent": 60,
        "attemptsUsed": 0,
        "openAttemptId": null,
        "best": null
      }
    }
  ]
}
```

- `upcomingSessions`: `Session[]` (como en `GET …/sessions`) sin las canceladas ni las ya terminadas, la más próxima
  primero; la que está en curso sigue.
- `pendingAssessments`: las evaluaciones no aprobadas con un intento abierto o con intentos por usar, en el orden del
  curso. `assessment` es el de la lección en `Classroom`. No hay plazos de evaluación en el modelo.
- Una matrícula que no está `ACTIVA` o ya tiene certificado no tiene nada por delante: las dos listas van vacías.
- Se lee por lotes: las sentencias SQL no crecen con el número de matrículas de la página.

### Finalización y certificado

- `GET /api/lms/classroom/enrollments/{id}/completion` → `{ "met": false, "rules": [{ "rule": "LESSONS", "met": true,
"detail": "16 de 16 lecciones" }] }`. `rule`: `LESSONS` (todas las lecciones completables), `ASSESSMENTS` (todas las
  evaluaciones aprobadas), `ATTENDANCE` (asistencia mínima, %). Solo aparecen las que declara la versión.
- `POST /api/lms/classroom/enrollments/{id}/certificate` `{ "holderName": "Nombre para el certificado" }` → `201`
  `Certificate` (la matrícula pasa a `FINALIZADA` con resultado `APROBADO`) · `200` el ya emitido · `409` con las
  reglas pendientes en `detail` (el detalle por regla, en `GET …/completion`) · `400` nombre vacío o de más de 120 caracteres.
- `GET /api/lms/public/certificates/{code}` (sin sesión) → `Certificate` o `404`:

```json
{
  "code": "K7Q2-M9XW-4RTB",
  "issuedAt": "…",
  "holderName": "…",
  "course": { "code": "SRTM-ANU-101", "title": "…" },
  "version": 1,
  "offering": { "code": "…", "startDate": "…", "endDate": "…", "timeZone": "America/Lima" },
  "totalHours": 18,
  "result": "APROBADO",
  "rules": [{ "rule": "LESSONS", "met": true, "detail": "…" }]
}
```

Congelado al emitirse: no cambia aunque cambien después el curso o la matrícula.

## Términos de uso y supresión (ADR-0037)

Con sesión. Los datos personales se conservan el plazo que la persona aprueba en los términos de uso
([ADR-0030](../adr/0030-lgpl-definitiva-y-conservacion-segun-los-terminos.md)); sin aceptación vigente no hay matrícula ni
progreso (`403`). El catálogo público no la pide.

- `GET /api/lms/terms` → `{ "current": TermsVersion | null, "acceptance": TermsAcceptance | null }`: los vigentes (`null`
  si la organización no publicó ninguno: entonces nadie se matricula) y la aceptación de quien pregunta.
- `POST /api/lms/terms` `{ "text": "…", "retentionDays": 730 }` → `201` `TermsVersion`, la versión siguiente. Solo con
  `MANAGE_ORGANIZATION` (`403`); `400` texto vacío o de más de 100 000 caracteres, o plazo fuera de 1–36 500 días. Una
  versión publicada no cambia y publicar otra no altera ninguna aceptación.
- `POST /api/lms/terms/acceptance` `{ "version": 3 }` → `200` `TermsAcceptance`. Acepta la versión vigente, que se nombra
  (otra → `409`; sin términos publicados → `409`). Con una aceptación vigente, la renueva con esta versión y su plazo
  desde ahora. Con una retirada o vencida, sus datos se están suprimiendo: `409` con `Retry-After` hasta que termine.
- `DELETE /api/lms/terms/acceptance` → `202` `{ "jobId": "…" }`: retira la aceptación y encola la supresión de los datos
  personales (repetirlo mientras no termina devuelve el mismo trabajo) · `404` sin aceptación.

```json
{
  "current": { "version": 3, "text": "…", "retentionDays": 730, "publishedAt": "…" },
  "acceptance": { "version": 2, "acceptedAt": "…", "expiresAt": "…", "withdrawnAt": null, "valid": true }
}
```

`valid`: sin retirar y antes de `expiresAt`. Al retirarla o vencer, la supresión corre en la cola
(`lms.privacy.sweep-every`, 1 h, encola las vencidas): suprime eventos, progreso, asistencias, entregas, intentos sin
calificar, valoraciones (con sus puntajes, comentarios y apelaciones), denuncias resueltas y trabajos; deja sin la
persona sus denuncias abiertas y la matrícula (`RETIRADA` si estaba activa), los intentos calificados y el certificado sin la
persona (`holderName: null` en su verificación) y vacía su perfil docente. Detalle en
[seguridad §7](../security/README.md#7-privacidad-y-retención).

## Valoraciones del estudiante (F6, ADR-0038)

Con sesión y la acción `VALORAR` (ESTUDIANTE; sin ella, `403`), desde una matrícula propia (ajena o inexistente → `404`).
El valorado no lee nada de esto: DOCENTE y CREADOR no tienen `READ` sobre `valoracion` ni `puntaje_valoracion`, y la API
de registros los cierra a todos (`apiOnly`).

| Método | Ruta                                          | Resultado                                                         |
| ------ | --------------------------------------------- | ----------------------------------------------------------------- |
| GET    | `/api/lms/classroom/enrollments/{id}/ratings` | `RatingEligibility`: a quién puede valorar, con qué dimensiones   |
| POST   | `/api/lms/classroom/enrollments/{id}/ratings` | `{ type, targetId, scores, comment }` → `201` `Rating`            |
| GET    | `/api/lms/ratings/mine`                       | `Rating[]`, lo más nuevo primero                                  |
| PUT    | `/api/lms/ratings/{ratingId}`                 | `{ scores, comment }` → `Rating`; reemplaza puntajes y comentario |
| DELETE | `/api/lms/ratings/{ratingId}`                 | `204`                                                             |

- `type` es `DOCENCIA` (`targetId`: la asignación, de `teachers[].id`) o `RECURSO` (`targetId`: la versión de recurso, de
  `resources[].id`). `scores` va por clave de dimensión activa del destino, enteros de 1 a 5, al menos uno (`400`).
  `comment` es texto plano de hasta 2000 caracteres (`400`); vacío lo quita. La interfaz lo muestra siempre escapado.
- `403`: matrícula ni `ACTIVA` ni `FINALIZADA`; un destino que no es de la edición o de la versión de la matrícula;
  progreso del aula bajo `lms.reputation.min-progress` (50 %) sin haber finalizado (docente); ninguna lección que usa el
  recurso completada; lo propio (docente de la edición, autor del recurso).
- `409`: ya lo valoró (se edita la suya) o la ventana cerró (`lms.reputation.window-after-end`, 30 días tras `fecha_fin`,
  también para editar y retirar).
- `429` con `ProblemDetail` y `Retry-After`: más de `lms.reputation.max-edits-per-day` (5) altas, ediciones o retiradas
  en el día UTC. Lo rechazado no cuenta.
- `POST` acepta `Idempotency-Key` (ADR-058 de Wasichai): la misma clave y el mismo cuerpo devuelven la misma respuesta
  (`Idempotent-Replayed: true`) sin valorar otra vez; otro cuerpo con la clave, `422`.
- Editar el comentario lo devuelve a `PENDIENTE` (solo se muestra `PUBLICADA`); cambiar solo los puntajes, no. Su
  historial lo lee el autor en `/api/objects/valoracion/records/{id}/history`.

```json
{
  "enrollmentId": "…",
  "open": true,
  "closesOn": "2026-12-31",
  "progressPercent": 50,
  "minProgress": 50,
  "teachers": [{ "type": "DOCENCIA", "id": "…", "name": "Ana Quispe", "eligible": true, "reason": null, "ratingId": null }],
  "resources": [{ "type": "RECURSO", "id": "…", "name": "Lectura de la unidad", "eligible": false, "reason": "LESSON", "ratingId": null }],
  "dimensions": { "DOCENCIA": [{ "key": "docente_claridad", "name": "Explica con claridad" }], "RECURSO": [] }
}
```

`reason`, cuando no puede: `CLOSED`, `ENROLLMENT`, `PROGRESS`, `LESSON`, `OWN` o `RATED` (ya la valoró: `ratingId`).
`Rating`: `{ id, enrollmentId, type, targetId, targetName, scores: { clave: valor }, comment, moderation, createdAt,
updatedAt }`, con `moderation` `PENDIENTE`, `PUBLICADA` u `OCULTA` (`null` sin comentario).

### Resúmenes para el valorado (ADR-0038 §8–§11)

Con sesión y la acción `VER_RESUMEN` (DOCENTE y CREADOR; sin ella, `403`), solo de lo que tiene a la persona como
destino. Nunca traen estudiante, matrícula, fecha ni ids de valoraciones. Una dimensión con menos de
`lms.reputation.min-sample` valoraciones (5; configurada por debajo de 3, vale 3) va con `sufficient: false` y sin
`mean` ni `n`.

- `GET /api/lms/teaching/offerings/{code}/ratings` → `TeacherRatingSummary` del docente en una edición que tiene
  asignada (otra, `404`). `released: false` (sin cifras ni comentarios) hasta que la ventana cerró y todas las
  matrículas no retiradas tienen resultado, o pasado `lms.reputation.release-after-end` (60 días) del fin. Al liberarse,
  sus cifras se **congelan** (la primera lectura o el trabajo diario): un cambio posterior no las mueve. Los comentarios
  `PUBLICADA` llegan solo con muestra suficiente, sin autor ni fecha y en orden aleatorio, cada uno con su
  `token` opaco para denunciarlo.
- `GET /api/lms/ratings/resources` → `ResourceRatingSummary[]`: las versiones de recurso de las que la persona es
  autora, con sus cifras de todas las ediciones, recalculadas cada día por el trabajo `reputation.aggregate`
  (`calculatedAt`).
- `GET /api/lms/public/ratings/criterion` (sin sesión) → el criterio publicado y versionado (`media-simple@1`): escala,
  fórmula, muestra mínima y liberación. Cada resumen lo enlaza en `criterion`.

```json
{
  "released": true,
  "windowClosesOn": "2026-12-31",
  "releasedAtLatestOn": "2027-01-30",
  "criterion": "/api/lms/public/ratings/criterion",
  "criterionVersion": "media-simple@1",
  "dimensions": [
    { "key": "docente_claridad", "name": "Explica con claridad", "sufficient": true, "mean": 4.2, "n": 6 },
    { "key": "docente_disponibilidad", "name": "Responde y está disponible", "sufficient": false, "mean": null, "n": null }
  ],
  "comments": [{ "token": "…", "text": "…" }]
}
```

### Moderación, denuncias y apelación (ADR-0038 §13–§19)

Los puntajes cuentan sin moderación; un comentario nace `PENDIENTE` y solo llega al valorado `PUBLICADA`.

| Método | Ruta                                               | Quién                     | Resultado                                                  |
| ------ | -------------------------------------------------- | ------------------------- | ---------------------------------------------------------- |
| GET    | `/api/lms/moderation/ratings`                      | `MODERAR`                 | `ModerationItem[]`: pendientes, denunciados o apelados     |
| POST   | `/api/lms/moderation/ratings/{id}/decision`        | `MODERAR`                 | `{ decision: PUBLISH \| HIDE, reason }` → `ModerationItem` |
| POST   | `/api/lms/moderation/ratings/{id}/appeal-decision` | `MODERAR`, otro moderador | `{ decision: UPHOLD \| REVERSE }` → `ModerationItem`       |
| POST   | `/api/lms/ratings/{id}/appeal`                     | el autor (`VALORAR`)      | `{ text }` → `{ ratingId, text, pending }`                 |
| POST   | `/api/lms/ratings/comments/{token}/reports`        | el valorado (`DENUNCIAR`) | `{ reason }` → `201` `{ status: "ABIERTA" }`               |

- Ocultar exige `reason` entre `ATAQUE_PERSONAL`, `DATOS_PERSONALES`, `SPAM`, `FUERA_DE_TEMA`, `CONFLICTO_DE_INTERES` e
  `ILEGAL` (`400`). Nadie modera una valoración que escribió o que lo tiene como destino (`403`, también ADMIN); decidir
  cierra las denuncias abiertas. `ModerationItem` no trae quién escribió ni quién denunció.
- El autor apela una vez un comentario `OCULTA` (otro estado o una segunda vez, `409`; ajena, `404`), también con la
  ventana cerrada. La decide un moderador distinto del que ocultó (`403` el mismo), una vez y es final (`409`).
- El valorado denuncia por la ficha (`token`) de un comentario de su resumen; otra ficha, `404`. La denuncia no oculta
  nada. Más de `lms.reputation.max-reports-per-day` (10) denuncias en el día UTC → `429` con `Retry-After`.

## Espacio docente (F3, ADR-0016)

Con sesión, solo sobre las ediciones **asignadas** a la persona (`docente_edicion.usuario_id`); otra edición o un
registro de otra edición → `404`. Nadie lo usa por su rol: lo decide la asignación. Para auditar las calificaciones
(RF-ADM-03), quien tiene `MANAGE_ORGANIZATION` (ADMIN) **lee** la cola de corrección y el intento con su `history` de
cualquier edición, sin asignación; calificar le sigue respondiendo `404`.

| Método | Ruta                                            | Resultado                                                             |
| ------ | ----------------------------------------------- | --------------------------------------------------------------------- |
| GET    | `/api/lms/teaching/offerings`                   | `TeachingOffering[]`                                                  |
| GET    | `/api/lms/teaching/panel`                       | `TeachingPanelItem[]`; `?page` y `?size`                              |
| GET    | `/api/lms/teaching/offerings/{code}/students`   | `Roster[]`; `?page` y `?size` opcionales                              |
| GET    | `/api/lms/teaching/offerings/{code}/attempts`   | cola: intentos `SUBMITTED` → `PendingAttempt[]`; `?page`, `?size`     |
| GET    | `/api/lms/teaching/attempts/{attemptId}`        | `Attempt` con soluciones, respuestas y entregas                       |
| POST   | `/api/lms/teaching/attempts/{attemptId}/grades` | `{ questionId, points, comment, reason }` → `Attempt`                 |
| GET    | `/api/lms/teaching/offerings/{code}/sessions`   | `Session[]` (sin `attendance`)                                        |
| POST   | `/api/lms/teaching/offerings/{code}/sessions`   | crea una sesión o una serie → `Session[]`                             |
| POST   | `/api/lms/teaching/sessions/{id}/reschedule`    | `{ date, localTime, reason }` → `Session`                             |
| POST   | `/api/lms/teaching/sessions/{id}/cancel`        | `{ reason }` → `Session`                                              |
| GET    | `/api/lms/teaching/sessions/{id}/attendance`    | `AttendanceRow[]`                                                     |
| PUT    | `/api/lms/teaching/sessions/{id}/attendance`    | `{ records: [{ enrollmentId, status }], reason }` → `AttendanceRow[]` |

- `email` puede ser `null` en `Roster`, `PendingAttempt` y `AttendanceRow`: lo guarda la matrícula al crearse.
- `TeachingOffering`: `{ "code", "status", "startDate", "endDate", "timeZone", "course": { "slug", "code", "title" },
"enrolled": 12, "pendingAttempts": 3 }`. `pendingAttempts` son los intentos `SUBMITTED` que quien llama puede corregir:
  los de su cola, sin los suyos si además es estudiante de la edición (#338), así que coincide con el total de
  `GET …/attempts`. Lo mismo en `GET /offerings` y en el panel.
- `TeachingPanelItem` (#261) = `TeachingOffering` más `upcomingSessions` (`Session[]` sin `attendance`, sin las
  canceladas ni las ya terminadas, la más próxima primero) y `oldestPending` (`PendingAttempt[]`: los 5 primeros de la
  cola, en su orden; el total está en `pendingAttempts` y la cola entera en `GET …/attempts`). Una página de las
  ediciones asignadas en el orden de `GET /offerings` (la de inicio más próximo primero), con `page` y `size` como el
  roster pero siempre paginada: sin ellos, la primera página de 25. Se lee por lotes: las sentencias SQL no crecen con
  el número de ediciones de la página.
- `Roster`: `{ "enrollmentId", "email", "status", "progress": ProgressSummary, "attendance": { "attended": 3,
"total": 4 }, "assessments": [{ "lessonId", "title", "bestPercent": 80, "passed": true, "pending": false }] }`.
- `PendingAttempt`: `{ "attemptId", "enrollmentId", "email", "lessonId", "lessonTitle", "submittedAt",
"pendingQuestions": 1 }`.
- La cola admite `?status=GRADED` para encontrar un intento ya corregido (por defecto `SUBMITTED`). El `Attempt` del docente
  añade `enrollmentId`, `email` e `history` (`[{ questionId, points, comment, reason, authorId, at }]`).
- El roster y la cola se leen por lotes (M4 de #183): las sentencias SQL crecen con el temario y las sesiones, no con el
  número de estudiantes, y la cola no carga la instantánea ni las respuestas.
- El roster y la cola admiten `?page` (desde 0) y `?size` como la API de registros: `size` vale 25 si falta y se acota a
  1–200, `page` a ≥ 0. Sin ninguno de los dos devuelven la edición entera, como antes, y la respuesta sigue siendo un
  arreglo: una página con menos de `size` filas es la última.
  - Roster: una página de las matrículas no retiradas, en el mismo orden que sin paginar (el de alta); lee solo el progreso
    y la asistencia de esa página.
  - Cola: una página de los intentos del estado pedido, en el mismo orden que sin paginar (el de envío, y a igualdad el
    id), cortada en SQL (`LIMIT`/`OFFSET` sobre `lms.intento`); lee solo los correos de las matrículas de esa página. El
    intento propio no cuenta para la página.
- Solo se califican a mano las preguntas `OPEN` y `FILE` (`400` en otra), con dos decimales como máximo. El intento propio no
  existe para quien lo hizo (`404`), aunque sea docente de esa edición, y tampoco se marca su propia asistencia (`403`).
- Calificar: `points` entre 0 y los de la pregunta (`400` si no); `reason` obligatorio al **cambiar** una nota ya puesta,
  también el `0` automático de una pregunta sin responder (`409` sin él). Cada calificación queda en un historial que no se borra; al no quedar pendientes, el intento pasa a
  `GRADED` con su `percent` y `passed`.
- Crear sesiones: `{ "title", "type", "modality", "durationMinutes", "link": null, "location": null, "lessonId": null,
"recurrence": { "pattern": "ONCE" | "DAILY" | "ALTERNATE_DAYS" | "WEEKDAYS", "days": ["MONDAY", "WEDNESDAY"],
"startDate": "2026-11-09", "endDate": "2026-11-27", "localTime": "08:00" } }`. Cada ocurrencia conserva la hora
  local en la zona de la edición, también al cruzar el horario de verano. `link` solo `https` (`400` si no). Una
  sesión `RECUPERACION` lleva `originalSessionId`.
- Reprogramar o cancelar avisa a los matriculados de esa edición (y a nadie más) por las alertas de Wasichai y, si está
  configurado, por correo. Ambas guardan el motivo en el historial de la sesión.
- `AttendanceRow`: `{ "enrollmentId", "email", "status": null | "PRESENTE" | "TARDE" | "AUSENTE" | "JUSTIFICADO" }`.
  Cambiar una asistencia ya registrada exige `reason` (`409` sin él). La asistencia no toca el progreso de materiales.

## Estudio y revisión (F4, ADR-0018)

Con sesión. Los permisos son las acciones declaradas sobre `version_curso`; lo ajeno responde `404`.

### Estudio del creador

| Método | Ruta                                          | Resultado                                                    |
| ------ | --------------------------------------------- | ------------------------------------------------------------ |
| GET    | `/api/lms/studio/versions`                    | mis versiones → `StudioVersion[]`, las más recientes primero |
| GET    | `/api/lms/studio/versions/{id}`               | `StudioVersionDetail`                                        |
| POST   | `/api/lms/studio/courses/{courseId}/versions` | `{ "fromVersionId": null }` → `201` `StudioVersionDetail`    |
| POST   | `/api/lms/studio/versions/{id}/submit`        | envía a revisión → `StudioVersion`                           |
| GET    | `/api/lms/studio/versions/{id}/diff`          | contra la versión anterior → `VersionDiff`                   |

Una versión nueva copia la última del curso, o la de `fromVersionId` (`400` si no es de ese curso), a un `BORRADOR` de quien
llama con el número siguiente. Un curso sin versiones recibe la v1 `BORRADOR` vacía, sin unidades, con `modalidad: ASINCRONA`,
`duracion: SEMANAS` y `semanas: 1`, que el creador ajusta (#257). Exige `CREATE` sobre `version_curso` (`403`); un curso
inexistente o que no puedo leer, `404`. El número es único por curso: dos peticiones a la vez no crean dos versiones con el
mismo número, y la que pierde responde `409`.

El contenido (unidades, lecciones, evaluaciones) se edita con la API de registros de Wasichai, que la guarda deja solo al autor y
en borrador. La lección de repaso de una pregunta (`leccion_repaso`, opcional) es una lección de la misma versión: otra es
`400` en `leccion_repaso`. Copiar la versión la re-apunta a la lección copiada.

`StudioVersion`: `{ "id", "number", "status", "course": { "id", "code", "title" }, "authorId", "authorName", "reviewerId": null,
"reviewerName": null, "updatedAt", "openComments": 2 }`. `status`: `BORRADOR` · `EN_REVISION` · `APROBADA` · `PUBLICADA` · `RETIRADA`.

**Nombres** (#258, ADR-0034): cada `authorId` y `reviewerId` de `StudioVersion`, `ReviewComment` y `ReviewDecision` va con su
`authorName` o `reviewerName`, el nombre para mostrar de la persona en Wasichai, nunca su correo ni otro dato. Es `null` si el
id es `null` o si la persona está deshabilitada o fue borrada; el id se devuelve igual. Solo los recibe quien ya puede leer la
versión (lo demás sigue siendo `404`/`403`), y se leen de una vez para toda la respuesta.

`StudioVersionDetail` = `StudioVersion` más `units`: `[{ "id", "order", "title", "sourceRef": null, "lessons": [{ "id", "order",
"title", "type", "sourceRef": null, "hasAssessment": false, "resources": 1 }] }]`, `comments: ReviewComment[]`, `decisions: ReviewDecision[]`,
`history: VersionTransition[]`.

`VersionTransition`: `{ "from": "BORRADOR", "to": "EN_REVISION", "by", "byName", "at" }`, la más antigua primero: cada cambio de
`status` de la versión, por cualquier vía (estudio, bandeja, API de registros o plataforma), y su creación (`from: null`).
`by` es quien la movió y `byName` su nombre para mostrar (null si está deshabilitada o se borró); los dos son null si la movió
la plataforma. Historial de solo inserción en `lms.transicion_version` (ADR-0032); escribir otros campos no deja fila.

### Bandeja del revisor

| Método | Ruta                                     | Resultado                                       |
| ------ | ---------------------------------------- | ----------------------------------------------- |
| GET    | `/api/lms/review/versions`               | en revisión y a mi cargo → `StudioVersion[]`    |
| GET    | `/api/lms/review/versions/{id}`          | `StudioVersionDetail`                           |
| GET    | `/api/lms/review/versions/{id}/diff`     | contra la versión anterior → `VersionDiff`      |
| POST   | `/api/lms/review/versions/{id}/comments` | `{ "section", "text" }` → `201` `ReviewComment` |
| POST   | `/api/lms/review/comments/{id}/resolve`  | → `ReviewComment`                               |
| POST   | `/api/lms/review/versions/{id}/decision` | `{ "decision", "reason" }` → `StudioVersion`    |
| POST   | `/api/lms/review/versions/{id}/publish`  | exige `PUBLICAR` → `StudioVersion`              |

- **A mi cargo**: tengo `APROBAR` sobre `version_curso` (si no, `403` en toda la bandeja), no soy el autor (`403`) y la versión
  no tiene revisor asignado o lo soy yo; una asignada a otra persona no existe para mí (`404`). La bandeja lista las que están
  `EN_REVISION`, la que más espera primero; el detalle, los comentarios y su resolución valen en cualquier estado.
- **Quien publica** (`PUBLICAR`) lee el detalle y las diferencias de cualquier versión que no sea suya, también con revisor
  asignado, para publicar lo aprobado (#223); decidir y comentar siguen siendo de quien la revisa.
- **Revisor asignado**: `revisor_id` de `version_curso`, por la API de registros. Lo pone o lo quita quien tiene `PUBLICAR`
  (`403` si no), en `BORRADOR` o `EN_REVISION` (`409` después), nunca al autor (`400`) ni a sí mismo (`403`). La guarda
  exige lo mismo a la API de registros: con revisor asignado, nadie más aprueba, pide cambios ni rechaza (`403`).
- `decision`: `APROBAR` · `PEDIR_CAMBIOS` (vuelve a `BORRADOR`) · `RECHAZAR` (pasa a `RETIRADA`). `reason` obligatorio salvo al
  aprobar (`400`), hasta 4000 caracteres. Nadie decide sobre su propia versión (`403`); con `reviewerId` puesto, solo esa persona.
  Solo se decide una versión `EN_REVISION` (`409`). La transición y la decisión van en la misma transacción.
- **Solo aquí se decide** (ADR-0021): sacar una versión de curso de `EN_REVISION` con la API de registros es `403` para quien
  decide; el autor sí la retira a borrador. Una `version_recurso` no tiene bandeja: su revisor la decide con la API de
  registros y, salvo al aprobar, con la cabecera `X-Change-Reason` (`400` sin ella), que queda en el `audit_log`.
- `ReviewComment`: `{ "id", "section", "text", "authorId", "authorName", "createdAt", "resolved": false }`. `section` es texto libre que
  identifica la parte (`unidad:1/leccion:2`), hasta 200 caracteres; `text`, hasta 4000. Ambos obligatorios (`400`). Solo se
  comenta una versión `EN_REVISION` (`409`); resolver lo ya resuelto no cambia nada. `comentario_revision` es `apiOnly`: la API
  de registros no lo escribe, y por ella solo lo lee quien publica, el autor de la versión, quien la puede revisar y quien lo
  escribió (lo demás, `404`). `openComments` cuenta los no resueltos.
- `ReviewDecision`: `{ "decision", "reason", "authorId", "authorName", "at" }`, la más antigua primero; historial de solo inserción en
  `lms.decision_revision`. Comentarios y decisiones quedan con su versión: una versión nueva a partir de ella empieza sin ellos.
- `publish`: solo una versión `APROBADA` (`409`); pone `publicada_en` el servidor.
- `VersionDiff`: `{ "baseVersion": 1, "units": [{ "order", "change": "ADDED" | "REMOVED" | "CHANGED" | "SAME", "title",
"previousTitle", "fields": ["titulo"], "lessons": [{ "order", "change", "title", "previousTitle", "fields": ["resumen"] }] }] }`.
  La versión anterior es la del mismo curso con el `number` más alto por debajo del de esta (`baseVersion`); sin ella,
  `baseVersion` es `null` y todo es `ADDED`. Una unidad es la misma si tiene el mismo `order`; una lección, si tiene el mismo
  `order` dentro de esa unidad (las identidades de los registros cambian con cada copia). `fields` nombra los campos propios
  que difieren (los del modelo, en español, ordenados; sin `orden` ni el padre), y `recursos` cuando cambian los recursos
  enlazados o su orden; vacío en `ADDED` y `REMOVED`. Una unidad es `CHANGED` si cambia un campo suyo o alguna lección.
  `title` es `null` en lo quitado y `previousTitle` en lo añadido. Las evaluaciones no se comparan. Se calcula al pedirlo y no
  se guarda. Lo ve el autor en su estudio y quien la revisa en su bandeja, con las mismas reglas que el detalle (`404`/`403`).

### Importación

| Método | Ruta                             | Resultado                                                                          |
| ------ | -------------------------------- | ---------------------------------------------------------------------------------- |
| GET    | `/api/lms/studio/import/sources` | orígenes permitidos → `[{ "repository": "owner/repo", "sha": null }]`              |
| POST   | `/api/lms/studio/import`         | `{ "source": "owner/repo@sha:ruta/maestro.adoc", "courseId" }` → `202` `ImportJob` |
| GET    | `/api/lms/studio/import`         | mis trabajos de importación, el más reciente primero → `[ImportJob]`               |
| GET    | `/api/lms/studio/import/{id}`    | `ImportJob`                                                                        |

- Importar (y ver los orígenes) exige `CREATE` sobre `version_curso`, `unidad` y `leccion` (`403`). `source` es un archivo de un
  repositorio en un commit exacto: el `sha` completo (40 caracteres hexadecimales) y una ruta relativa sin `..` (`400` si no). Un
  repositorio (o un commit) fuera de `lms.importer.allowed-sources` → `403`; `sha: null` en los orígenes significa cualquier
  commit. `courseId` tiene que ser un curso que quien importa puede leer (`400` si no es un id, `404` si no existe).
- El trabajo corre en la cola persistente: se consulta con `GET` hasta que `status` sea `HECHO` o `FALLIDO`. Solo lo ve quien lo
  pidió; para cualquier otro, también el administrador, es `404`. Terminado, se guarda `lms.jobs.retention` (30 días) y después es `404`, como el de la generación
  ([ADR-0022](../adr/0022-limites-y-ciclo-de-vida-de-los-trabajos.md)).
- Pedir la misma fuente sobre el mismo curso mientras el trabajo anterior de la misma persona está `PENDIENTE` o `EN_CURSO`
  devuelve ese trabajo (`202`), sin otro borrador; terminado, importar otra vez es otro trabajo. Con
  `lms.jobs.max-active-per-requester` (5) trabajos pendientes o en curso, entre importaciones y generaciones, uno más es `429`
  con `Retry-After` ([ADR-0022](../adr/0022-limites-y-ciclo-de-vida-de-los-trabajos.md)).
- `GET /api/lms/studio/import` lista a lo sumo 50 trabajos de importación de quien llama, pendientes, en curso y terminados aún
  retenidos, el más reciente primero. Pide los mismos permisos que importar (`403`) y nunca muestra los de otra persona,
  tampoco al administrador. Sirve para que la interfaz no pierda de vista una importación al recargar.
- `ImportJob`: `{ "id", "status": "PENDIENTE" | "EN_CURSO" | "HECHO" | "FALLIDO", "error": null, "versionId": null, "report": null,
"source": "owner/repo@sha:ruta", "courseId", "createdAt", "finishedAt": null }`.
  `error` es el motivo de `FALLIDO` (o el último fallo pasajero mientras se reintenta). Con `HECHO`, `versionId` es la versión
  nueva del curso y `report`: `{ "source", "files", "includes", "sections", "images", "units", "lessons", "problemCount",
"problems": [{ "kind", "origin": "owner/repo@sha:ruta", "line", "detail", "severity" }], "problemsBySeverity": { "GAP": 1, "CHECK": 4 } }`
  (a lo sumo 500 problemas; `line` 0 es el archivo entero). `kind`: `MISSING_INCLUDE` · `INCLUDE_OUTSIDE_ROOT` · `INCLUDE_CYCLE` ·
  `INCLUDE_TOO_DEEP` · `UNRESOLVED_ATTRIBUTE` · `UNSUPPORTED_INCLUDE_OPTION` · `MISSING_IMAGE` · `IMAGE_OUTSIDE_ROOT` · `BROKEN_REFERENCE`
  · `DUPLICATE_ANCHOR` · `FILE_TOO_LARGE`. Un problema no detiene la importación: queda en el informe.
- `severity` la decide el servidor por `kind`: `GAP` cuando esa parte del manual no se leyó y al temario le pueden faltar unidades o
  lecciones (`MISSING_INCLUDE`, `INCLUDE_OUTSIDE_ROOT`, `INCLUDE_CYCLE`, `INCLUDE_TOO_DEEP`, `UNRESOLVED_ATTRIBUTE`, `FILE_TOO_LARGE`
  y cualquier tipo nuevo); `CHECK` cuando se leyó y queda algo que revisar (el resto). `problemsBySeverity` cuenta todos los
  problemas, también los que la lista no muestra; en un informe guardado antes de que existiera, cuenta los de la lista.
- El resultado es siempre una versión `BORRADOR` nueva del curso (número siguiente), con `autor_id` de quien la pidió y la
  duración y la modalidad de la última versión: una unidad por capítulo (sección de nivel 1, tras `leveloffset`) y una lección
  `LECTURA` por sección de nivel 2, cada una con su título y su `sourceRef` (`owner/repo@sha:ruta#ancla`, en
  `referencia_fuente`), que se congela con la versión y pasa a sus copias; un capítulo sin secciones lleva
  una lección con su propio título. **Sin el texto del manual** (ADR-0018 §6) y **nunca publica**. Falla (`FALLIDO`, sin
  borrador) si el maestro no existe, el origen dejó de estar permitido, el curso desapareció, la fuente pasa de
  `lms.importer.max-files` archivos o quien la pidió ya no está habilitado o perdió alguno de los permisos de arriba antes de
  que el trabajo corriera.

### Generación de artefactos

Una fuente AsciiDoc produce un HTML y un PDF en la cola ([ADR-0018 §7](../adr/0018-fase-4-estudio-revision-e-importador.md)),
y el resultado es una `version_recurso` **nueva en `BORRADOR`**, a nombre de quien la pidió, con el artefacto del tipo de su
recurso como `archivo`. Sigue el flujo editorial como cualquier otra versión; generar otra vez crea otra versión, nunca
sobrescribe. El HTML se guarda saneado con lista blanca (sin scripts, manejadores de eventos, `iframe`, formularios ni hojas
externas, [ADR-0022](../adr/0022-limites-y-ciclo-de-vida-de-los-trabajos.md)); su `sha256` es el de lo guardado.

La fuente es un solo archivo autosuficiente ([ADR-0031](../adr/0031-aceptacion-de-la-generacion-con-una-fuente-propia.md)):
en modo `secure`, `include::` no lee nada y queda como enlace a su destino. Una imagen llega al PDF si va incrustada como
URI `data:`; una ruta relativa deja su texto alternativo. En el HTML guardado las imágenes quedan por su texto alternativo
(el saneado solo admite `http` y `https` en `src`).

| Método | Ruta                                  | Resultado                                            |
| ------ | ------------------------------------- | ---------------------------------------------------- |
| POST   | `/api/lms/studio/sources/{id}/render` | sin cuerpo → `202` `RenderJob` (`status: PENDIENTE`) |
| GET    | `/api/lms/studio/renders/{id}`        | `RenderJob`                                          |

`RenderJob`: `{ "id", "status": "PENDIENTE" | "EN_CURSO" | "HECHO" | "FALLIDO", "error": null, "versionId": null,
"versionNumber": null, "artifacts": [{ "id", "format": "HTML" | "PDF", "sha256" }] }`. `error` es el del último intento
(sin Asciidoctor: `asciidoctor is not available on this server (…): the deployment image must include Asciidoctor
(ADR-0018 §7)`). Lo que reintentar no arregla (tipo no generable, fuente sin archivo o con otro hash, falta del
binario, fallo de Asciidoctor, salida vacía o demasiado grande, tiempo límite) es `FALLIDO` en el primer intento; un fallo
pasajero (almacenamiento, interrupción) vuelve a `PENDIENTE` hasta agotar `lms.jobs.max-attempts`
([ADR-0022](../adr/0022-limites-y-ciclo-de-vida-de-los-trabajos.md)).

- **Una a la vez**: pedir la misma fuente mientras el trabajo anterior de la misma persona está `PENDIENTE` o `EN_CURSO`
  devuelve ese trabajo (`202`); terminado, generar otra vez es otro trabajo y otra versión. El tope de trabajos activos por
  persona es el de la importación (`429` con `Retry-After`, [ADR-0022](../adr/0022-limites-y-ciclo-de-vida-de-los-trabajos.md)).
- **Permisos**: ambas rutas exigen `CREATE` sobre `version_recurso` (`403`; REVISOR y ESTUDIANTE no lo tienen) y la fuente
  se lee como quien pide (`404` si no existe o no la ve). Solo se generan recursos `HTML` o `PDF` (`409` para otro tipo).
  Un trabajo que no es de generación, de otra organización o **de otra persona** es `404`: solo lo lee quien lo pidió, también
  frente al administrador, como el de la importación. Si antes de ejecutarse quien la pidió deja de estar habilitado o
  pierde esos permisos, el trabajo es `FALLIDO` sin versión.
- **La fuente** se crea con la API de registros: se sube el archivo con `POST /api/objects/fuente_recurso/files/archivo`
  (parte `file`, **tipo declarado `text/plain`**, UTF-8, hasta 1 MB) y su `id` va en el alta
  `POST /api/objects/fuente_recurso/records` `{ "attributes": { "recurso", "numero", "archivo" } }`. Es de solo inserción
  (`409` al cambiarla): corregirla es subir otra con el número siguiente.
- **Los artefactos** se leen con `GET /api/objects/artefacto_generado/records?version_recurso={versionId}` (CREADOR y
  REVISOR): `formato`, `archivo`, `sha256`, `hash_fuente`, `generador`, `version_generador`, `trabajo_id` y las relaciones
  `version_recurso` y `fuente_recurso`. Se descargan con `GET /api/objects/artefacto_generado/records/{id}/files/archivo`,
  siempre como adjunto. La API de registros no los escribe (`403`).

## Registro de ejecuciones de IA (F5, ADR-0033 §7)

Cada llamada de un paso de la canalización deja una ejecución (`AgentRun`, RF-IA-03) en `lms.ejecucion_agente`. La escriben
los pasos desde su trabajo; esta API solo la lee.

| Método | Ruta                             | Resultado                                                                                  |
| ------ | -------------------------------- | ------------------------------------------------------------------------------------------ |
| GET    | `/api/lms/ai/runs?courseId={id}` | las ejecuciones del curso, la más reciente primero, a lo sumo 100 → `[AgentExecutionLine]` |
| GET    | `/api/lms/ai/runs/{id}`          | `AgentExecution`                                                                           |

- **Permisos**: ambas rutas exigen `UPDATE` sobre `version_curso` (CREADOR, REVISOR y ADMIN; `403` para ESTUDIANTE y DOCENTE) y
  el curso se lee como quien llama: el mismo alcance que el curso. Un curso que no existe o no ve es `404`; una ejecución que
  no existe, de otra organización o de un curso que no ve, también `404`. No hay ruta que escriba (`405`).
- `AgentExecutionLine`: `{ "id", "jobId", "step", "status", "generator", "provider": null, "model": null, "requestedBy",
"courseId", "unitId": null, "versionId": null, "attempt", "inputTokens": null, "outputTokens": null, "cost": null,
"startedAt", "finishedAt": null }`.
- `AgentExecution`: lo de la línea más `"organizationId", "objective", "inputs", "sources": [{ "ref": "owner/repo@sha:ruta#ancla",
"sha256" }], "modelSettings": { "maxTokens", "temperature": null }, "promptVersion", "templateVersion", "idempotencyKey",
"output": null, "validations": null, "error": null, "interventions": [{ "userId", "kind", "at", "note": null }]`.
  `inputs`, `output` y `validations` son el JSON del paso.
- `step`: `SOURCE_ANALYSIS` · `COURSE_DESIGN` · `LESSON_DRAFT` · `SCRIPT` · `CONSISTENCY_REVIEW` · `ASSISTANT`. `status`:
  `PENDING` · `RUNNING` · `SUCCEEDED` · `INVALID_OUTPUT` · `FAILED` · `CANCELLED`. `generator`: `MODEL` · `DETERMINISTIC`; con
  `DETERMINISTIC`, `provider`, `model` y `modelSettings` son `null`. `provider`: `anthropic` · `openai`. `kind`: `ACCEPTED` ·
  `EDITED` · `REJECTED`.
- `cost`: `{ "amount", "currency", "inputPerMillion", "outputPerMillion", "rateSince" }`, calculado al cerrar con la tarifa de
  `lms.ai.rates` que regía el día en que empezó, y guardado con ella: cambiar la tarifa no reescribe lo gastado. `null`
  mientras corre, sin consumo informado, con el doble determinista o si el modelo no tiene tarifa configurada. Cada tarifa de la lista lleva `provider`, `model`, `input-per-million`, `output-per-million`, `currency` y `since` (por entorno, `LMS_AI_RATES_0_PROVIDER`…).
- **Lo que no cambia**: lo pedido (paso, ámbito, objetivo, entradas, fuentes, modelo, versiones, clave e intento) no se edita;
  la ejecución se cierra una sola vez (estado, salida, validaciones, error, tokens, coste y fin) y las intervenciones solo se
  añaden al final. Nada se borra ni se purga con los trabajos. Lo hace cumplir un disparador, también frente a SQL directo.
- **Sin secretos**: ningún campo guarda claves ni cabeceras; `modelSettings` es tipado (no admite parámetros libres) y una clave
  configurada (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`) que aparezca en el objetivo, las entradas, la salida, el error o una nota
  se guarda como `[REDACTED]` (RNF-SEG-09).

## Créditos aprobados (RF-CRE-08, ADR-0035)

La plataforma calcula y **propone** créditos ([ADR-0007](../adr/0007-carga-academica-y-creditos-como-politica-versionada.md));
reconocerlos es un acto de la institución que una persona con `APROBAR_CREDITOS` sobre `version_curso` (rol
`AUTORIDAD_ACADEMICA`) registra en nombre de un órgano y con un documento
([ADR-0035](../adr/0035-aprobacion-de-creditos-por-una-autoridad-academica.md)).

| Método | Ruta                                       | Resultado                                                         |
| ------ | ------------------------------------------ | ----------------------------------------------------------------- |
| GET    | `/api/lms/credits/versions/{id}/proposal`  | propuesta de hoy y aprobación vigente → `VersionCredits`          |
| GET    | `/api/lms/credits/versions/{id}/approvals` | historial, lo más antiguo primero → `CreditApproval[]`            |
| POST   | `/api/lms/credits/versions/{id}/approvals` | `CreditApprovalRequest` → `201` `CreditApproval` (`APROBACION`)   |
| POST   | `/api/lms/credits/approvals/{id}/revoke`   | `CreditRevocationRequest` → `201` `CreditApproval` (`REVOCACION`) |

- **Quién**: aprobar y revocar exigen `APROBAR_CREDITOS` (`403`; un REVISOR con `APROBAR` tampoco la tiene). Leer exige leer
  la versión (`404` si no) y `READ` sobre `aprobacion_creditos` (`403`; el estudiante nunca).
- **Separación de funciones**, también para ADMIN (`403`): no aprueba ni revoca el autor de la versión, su revisor asignado,
  quien la aprobó en la bandeja ni quien tiene una asignación docente en una edición de alguna versión del curso.
- **Cuándo**: solo una versión `APROBADA` o `PUBLICADA`, con sistema de crédito (`409`), sin otra aprobación vigente (`409`:
  primero se revoca). Solo se revoca la aprobación vigente (`409`).
- `CreditApprovalRequest`: `{ "approvedCredits": 8, "reason": null, "approvingBody": "Consejo de Facultad",
"documentReference": "Resolución 045-2026-CF", "documentDate": "2026-10-30" }`. `approvedCredits` es opcional (por
  omisión, lo propuesto), positivo y con hasta 2 decimales; si difiere de lo propuesto, `reason` es obligatorio (`400`).
  `approvingBody` y `documentReference` son obligatorios, hasta 200 caracteres; `reason`, hasta 4000; `documentDate`,
  opcional. `CreditRevocationRequest` es igual sin `approvedCredits`, con `reason` obligatorio.
- El servidor **recalcula** con la política del sistema de la versión y guarda su instantánea. Hasta que existan políticas
  en datos (RF-CRE-07) es la de referencia: `ects-carga-total@1` o `sch-34cfr600.2-trabajo-equivalente@1`.
- `CreditApproval`: `{ "id", "versionId", "type": "APROBACION" | "REVOCACION", "revokes", "system", "policyId",
"policyVersion", "method", "parameters": { "horasPorCredito": "27" }, "source", "totalHours", "calculated", "proposed",
"approvedCredits", "reason", "approvingBody", "documentReference", "documentDate", "approvedBy", "approvedAt" }`. En una
  revocación, `revokes` es la aprobación revocada y la instantánea va vacía.
- `VersionCredits`: `{ "versionId", "versionStatus", "system", "proposal": { …como la del catálogo… }, "current":
CreditApproval | null }`.
- Las filas son `aprobacion_creditos` (`appendOnly`, `apiOnly`): la API de registros las lee pero no las crea, cambia ni
  borra; quedan en `/api/audit?objectName=aprobacion_creditos&recordId=…` con la persona, la hora y, en `reason`, un resumen del acto (tipo, órgano, documento, política con sus parámetros, horas y créditos, motivo; hasta 500 caracteres). El comando copia lo
  aprobado a `version_curso.creditos_aprobados` y `politica_creditos` (`id@versión`) y lo borra al revocar; esos dos campos
  no se escriben por la API de registros (`403` para todos). Una versión nueva no los hereda.
- En el catálogo, `credits.approved` y `credits.approval` (`{ "credits", "policyId", "policyVersion", "approvingBody",
"documentReference", "documentDate", "approvedAt" }`) solo existen con una aprobación vigente; tras una revocación vuelven
  a `null` en el refresco siguiente de la proyección.

### Políticas de crédito (RF-CRE-07, ADR-0036)

| Método | Ruta                                         | Resultado                                                     |
| ------ | -------------------------------------------- | ------------------------------------------------------------- |
| GET    | `/api/lms/credits/policies`                  | `?jurisdiction=&system=&status=&program=` → `CreditPolicy[]`  |
| GET    | `/api/lms/credits/policies/{id}`             | `CreditPolicy`                                                |
| POST   | `/api/lms/credits/policies/{id}/publish`     | exige `PUBLICAR`; solo un borrador (`409`) → `CreditPolicy`   |
| POST   | `/api/lms/credits/policies/{id}/retire`      | exige `PUBLICAR`; solo una publicada (`409`) → `CreditPolicy` |
| POST   | `/api/lms/credits/policies/{id}/new-version` | exige `CREATE`; `201` borrador con `number + 1`               |

- El borrador se crea y se edita con la API de registros: `POST /api/objects/politica_credito/records` con `clave`,
  `numero`, `nombre`, `sistema` (`ECTS`, `SCH`, `PE_CREDITO`), `metodo` (`ECTS_CARGA_TOTAL`, `US_SCH_34CFR600_2`,
  `PE_LEY30220_HORAS_LECTIVAS`), `parametros` (JSON de texto o números; vacío, los de la norma), `jurisdiccion` (ISO
  3166-1 alfa-2 o `EHEA`), `programa_codigo`, `fuente`, `fuente_url`, `vigente_desde`. Nace en `BORRADOR` (`409` si no);
  un método de otro sistema, un parámetro desconocido o fuera de la norma (ECTS fuera de 25–30 h; PE bajo 16 h de teoría o
  32 de práctica) o una jurisdicción inválida son `400`. Publicada o retirada no cambia ni se borra (`409`, también ADMIN).
- Parámetros: ECTS `horasPorCredito`, `paso`; SCH `horasInstruccionPorSemana`, `horasFueraDeClasePorSemana`,
  `semanasDeReferencia`, `paso`; PE `horasTeoriaPorCredito`, `horasPracticaPorCredito`, `paso`.
- `CreditPolicy`: `{ "id", "key", "number", "name", "status", "system", "method", "parameters", "jurisdiction",
"programCode", "source", "sourceUrl", "validFrom", "publishedBy", "publishedAt" }`. La leen el personal y la autoridad
  académica; el estudiante no (`403`). Lo de otra organización no existe (`404`).
- Una versión de curso se liga a una versión de política con `version_curso.politica_credito` (en borrador, como todo su
  contenido). Propone con ella si es de su sistema; sin ella, con la de referencia (`pe-ley30220-horas-lectivas@1` para
  `PE_CREDITO`); ligada a otro sistema, `proposal` es `null`. `VersionCredits.policyId` es la ligada. Aprobar exige la
  ligada `PUBLICADA` y de su sistema (`409`); la aprobación guarda `policyId` = `clave` y `policyVersion` = `numero`.
