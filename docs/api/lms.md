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

| nombre               | tipo                                                   | regla                                                                          |
| -------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------ |
| `q`                  | texto ≤ 100                                            | búsqueda sin distinguir mayúsculas en título y resumen                         |
| `category`           | slug                                                   | slug de la categoría                                                           |
| `level`              | `INICIAL` \| `INTERMEDIO` \| `AVANZADO`                |                                                                                |
| `modality`           | `ASINCRONA` \| `SINCRONA` \| `HIBRIDA` \| `PRESENCIAL` |                                                                                |
| `startsFrom`         | `YYYY-MM-DD`                                           | solo cursos con una edición abierta o programada que empieza ese día o después |
| `minWeeks`           | entero 1–26                                            | duración (`weeks`) mayor o igual                                               |
| `maxWeeks`           | entero 1–26, ≥ `minWeeks`                              | duración (`weeks`) menor o igual; un curso semestral dura de 12 a 26 semanas   |
| `enrollmentOpen`     | `true` \| `false`                                      | `true`: con alguna edición con `enrollmentOpen` hoy; `false`: sin ninguna      |
| `minTheoryHours`     | número 0–10000, ≤ 2 decimales                          | `workload.theoryHours` (horas totales de teoría) mayor o igual                 |
| `minPracticeHours`   | número 0–10000, ≤ 2 decimales                          | `workload.practiceHours` (práctica o laboratorio) mayor o igual                |
| `minAutonomousHours` | número 0–10000, ≤ 2 decimales                          | `workload.autonomousHours` (trabajo autónomo) mayor o igual                    |
| `sort`               | `upcoming` (defecto) \| `title` \| `duration`          | `upcoming`: próxima edición primero, sin edición al final                      |
| `page`               | entero ≥ 0 (defecto 0)                                 |                                                                                |
| `size`               | 1–50 (defecto 12)                                      | fuera de rango → **400** con `errors[{field:"size"}]`                          |

Los filtros se combinan (intersección). Un valor inválido (enum desconocido, fecha mal formada, `size` fuera de rango,
`q` > 100, semanas fuera de 1–26 o `minWeeks` > `maxWeeks`, `enrollmentOpen` distinto de `true`/`false`, horas negativas,
con más de dos decimales o en notación científica) → **400** con `errors`; cada error nombra su parámetro.

Filtros de RF-CAT-08: `enrollmentOpen` usa la misma regla que el campo `enrollmentOpen` de la ficha (ver más abajo), en la
zona horaria de cada edición; no mira el cupo, que no es público. Las horas son las totales de la ficha
(`horas_*_semana × semanas`); las horas supervisadas no tienen filtro. No hay filtro de conocimientos previos:
`prerequisiteKnowledge` es texto libre y un `null` significa «no declarado», no «ninguno».

Respuesta 200:

```json
{
  "content": [CourseSummary],
  "page": 0, "size": 12, "totalElements": 3, "totalPages": 1,
  "facets": { "categories": [{ "slug": "gestion-municipal", "name": "Gestión municipal", "count": 2 }] }
}
```

`facets.categories` cuenta los cursos publicados por categoría con todos los filtros **salvo** `category` (para pintar
filtros).

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
    "enrollmentOpen": true
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
  "prerequisiteKnowledge": "…", // o null
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
    "system": "ECTS", // "ECTS" | "SCH" | null (NINGUNO)
    "approved": null, // número si una persona los aprobó
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
      "capacity": 30,
      "schedule": "…",
      "teachers": [{ "name": "…", "professionalTitle": "…", "role": "TITULAR" }]
    }
  ],
  "teachers": [{ "name": "…", "professionalTitle": "…", "bio": "…", "specialties": "…" }]
}
```

`enrollmentOpen` = estado `INSCRIPCION_ABIERTA` y la fecha de hoy (en la zona de la edición) dentro de
[`enrollmentFrom`, `enrollmentUntil`] cuando existen. Los créditos son siempre una **propuesta**: la interfaz debe decir
«créditos propuestos, no oficiales» salvo que `approved` tenga valor.

## Valores y nulos

- **Enumeraciones** (las del modelo, `model/model.json`): `level` `INICIAL` \| `INTERMEDIO` \| `AVANZADO`; `modality`
  `ASINCRONA` \| `SINCRONA` \| `HIBRIDA` \| `PRESENCIAL`; edición `status` `PROGRAMADA` \| `INSCRIPCION_ABIERTA` \| `EN_CURSO` (las
  `FINALIZADA` y `CANCELADA` no se publican); lección `type` `LECTURA` \| `VIDEO` \| `PRESENTACION` \| `LABORATORIO` \|
  `EVALUACION` \| `SESION`; docente `role` `TITULAR` \| `ASISTENTE`. Un valor nuevo llega primero al modelo y aquí.
- **Pueden ser `null`**: `summary`, `category`, `nextOffering`, `prerequisiteKnowledge`, `version.publishedAt`,
  `requirements`, `assessment`, `certification`, `credits.system`, `credits.approved`, `credits.proposal`;
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
- `404` edición desconocida · `409` (`problem+json`, `detail` legible) inscripción cerrada, sin cupo o edición no
  disponible · `401` sin sesión · `403` sin permiso de matricularse.

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
          ]
        }
      ]
    }
  ]
}
```

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
su lectura ya trae el resultado.

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
  "questions": [{ "questionId": "…", "points": 2, "maxPoints": 2, "correct": true, "comment": null, "solution": null }]
}
```

`percent` y `passed` son `null` mientras `pending`. `solution` (`{ "optionIds": [...], "acceptedAnswers": [...] }`) solo si la
evaluación muestra las soluciones al enviar (`mostrar_soluciones: AL_ENVIAR`) **y la persona ya aprobó o agotó sus intentos**:
antes servirían para aprobar el siguiente. Con `NUNCA`, siempre `null`.

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

## Espacio docente (F3, ADR-0016)

Con sesión, solo sobre las ediciones **asignadas** a la persona (`docente_edicion.usuario_id`); otra edición o un
registro de otra edición → `404`. Nadie lo usa por su rol: lo decide la asignación.

| Método | Ruta                                            | Resultado                                                             |
| ------ | ----------------------------------------------- | --------------------------------------------------------------------- |
| GET    | `/api/lms/teaching/offerings`                   | `TeachingOffering[]`                                                  |
| GET    | `/api/lms/teaching/offerings/{code}/students`   | `Roster[]`                                                            |
| GET    | `/api/lms/teaching/offerings/{code}/attempts`   | cola de corrección: intentos `SUBMITTED` → `PendingAttempt[]`         |
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
"enrolled": 12, "pendingAttempts": 3 }`.
- `Roster`: `{ "enrollmentId", "email", "status", "progress": ProgressSummary, "attendance": { "attended": 3,
"total": 4 }, "assessments": [{ "lessonId", "title", "bestPercent": 80, "passed": true, "pending": false }] }`.
- `PendingAttempt`: `{ "attemptId", "enrollmentId", "email", "lessonId", "lessonTitle", "submittedAt",
"pendingQuestions": 1 }`.
- La cola admite `?status=GRADED` para encontrar un intento ya corregido (por defecto `SUBMITTED`). El `Attempt` del docente
  añade `enrollmentId`, `email` e `history` (`[{ questionId, points, comment, reason, authorId, at }]`).
- El roster y la cola devuelven la edición entera, sin paginar. Se leen por lotes (M4 de #183): las sentencias SQL crecen con
  el temario y las sesiones, no con el número de estudiantes, y la cola no carga la instantánea ni las respuestas.
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

El contenido (unidades, lecciones, evaluaciones) se edita con la API de registros de Wasichai, que la guarda deja solo al autor y
en borrador.

`StudioVersion`: `{ "id", "number", "status", "course": { "id", "code", "title" }, "authorId", "reviewerId": null,
"updatedAt", "openComments": 2 }`. `status`: `BORRADOR` · `EN_REVISION` · `APROBADA` · `PUBLICADA` · `RETIRADA`.

`StudioVersionDetail` = `StudioVersion` más `units`: `[{ "id", "order", "title", "lessons": [{ "id", "order", "title", "type",
"sourceRef": null, "hasAssessment": false, "resources": 1 }] }]`, `comments: ReviewComment[]`, `decisions: ReviewDecision[]`.

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
- **Revisor asignado**: `revisor_id` de `version_curso`, por la API de registros. Lo pone o lo quita quien tiene `PUBLICAR`
  (`403` si no), en `BORRADOR` o `EN_REVISION` (`409` después), nunca al autor (`400`) ni a sí mismo (`403`). La guarda
  exige lo mismo a la API de registros: con revisor asignado, nadie más aprueba, pide cambios ni rechaza (`403`).
- `decision`: `APROBAR` · `PEDIR_CAMBIOS` (vuelve a `BORRADOR`) · `RECHAZAR` (pasa a `RETIRADA`). `reason` obligatorio salvo al
  aprobar (`400`), hasta 4000 caracteres. Nadie decide sobre su propia versión (`403`); con `reviewerId` puesto, solo esa persona.
  Solo se decide una versión `EN_REVISION` (`409`). La transición y la decisión van en la misma transacción.
- `ReviewComment`: `{ "id", "section", "text", "authorId", "createdAt", "resolved": false }`. `section` es texto libre que
  identifica la parte (`unidad:1/leccion:2`), hasta 200 caracteres; `text`, hasta 4000. Ambos obligatorios (`400`). Solo se
  comenta una versión `EN_REVISION` (`409`); resolver lo ya resuelto no cambia nada. `comentario_revision` es `apiOnly`: la API
  de registros no lo escribe. `openComments` cuenta los no resueltos.
- `ReviewDecision`: `{ "decision", "reason", "authorId", "at" }`, la más antigua primero; historial de solo inserción en
  `lms.decision_revision`. Comentarios y decisiones quedan con su versión: una versión nueva a partir de ella empieza sin ellos.
- `publish`: solo una versión `APROBADA` (`409`); pone `publicada_en` el servidor.
- `VersionDiff`: `{ "baseVersion": 1, "units": [{ "order", "change": "ADDED" | "REMOVED" | "CHANGED" | "SAME", "title",
"previousTitle", "lessons": [{ "order", "change", "title", "previousTitle", "fields": ["resumen"] }] }] }`. Sin versión anterior,
  `baseVersion` es `null` y todo es `ADDED`.

### Importación

| Método | Ruta                             | Resultado                                                         |
| ------ | -------------------------------- | ----------------------------------------------------------------- |
| GET    | `/api/lms/studio/import/sources` | orígenes permitidos → `[{ "id", "repository", "sha", "master" }]` |
| POST   | `/api/lms/studio/import`         | `{ "sourceId", "courseId" }` → `202` `ImportJob`                  |
| GET    | `/api/lms/studio/import/{id}`    | `ImportJob`                                                       |

`ImportJob`: `{ "id", "status": "PENDIENTE" | "EN_CURSO" | "HECHO" | "FALLIDO", "error": null, "versionId": null, "report": null |
{ "includes": 12, "sections": 40, "images": 9, "problems": [{ "kind": "INCLUDE_ROTO" | "IMAGEN_FALTANTE" | "ANCLA_ROTA", "origin":
"repo@sha:ruta#ancla", "detail" }] } }`. Un origen fuera de la lista → `404`. El resultado es siempre un `BORRADOR` con
`sourceRef` en cada lección y sin el texto del manual.

### Generación de artefactos

Una fuente AsciiDoc produce un HTML y un PDF en la cola ([ADR-0018 §7](../adr/0018-fase-4-estudio-revision-e-importador.md)),
y el resultado es una `version_recurso` **nueva en `BORRADOR`**, a nombre de quien la pidió, con el artefacto del tipo de su
recurso como `archivo`. Sigue el flujo editorial como cualquier otra versión; generar otra vez crea otra versión, nunca
sobrescribe.

| Método | Ruta                                  | Resultado                                            |
| ------ | ------------------------------------- | ---------------------------------------------------- |
| POST   | `/api/lms/studio/sources/{id}/render` | sin cuerpo → `202` `RenderJob` (`status: PENDIENTE`) |
| GET    | `/api/lms/studio/renders/{id}`        | `RenderJob`                                          |

`RenderJob`: `{ "id", "status": "PENDIENTE" | "EN_CURSO" | "HECHO" | "FALLIDO", "error": null, "versionId": null,
"versionNumber": null, "artifacts": [{ "id", "format": "HTML" | "PDF", "sha256" }] }`. `error` es el del último intento
(sin Asciidoctor: `asciidoctor is not available on this server (…): the deployment image must include Asciidoctor
(ADR-0018 §7)`); un intento fallido vuelve a `PENDIENTE` hasta agotar `lms.jobs.max-attempts`.

- **Permisos**: ambas rutas exigen `CREATE` sobre `version_recurso` (`403`; REVISOR y ESTUDIANTE no lo tienen) y la fuente
  se lee como quien pide (`404` si no existe o no la ve). Solo se generan recursos `HTML` o `PDF` (`409` para otro tipo).
  Un trabajo que no es de generación o de otra organización es `404`.
- **La fuente** se crea con la API de registros: se sube el archivo con `POST /api/objects/fuente_recurso/files/archivo`
  (parte `file`, **tipo declarado `text/plain`**, UTF-8, hasta 1 MB) y su `id` va en el alta
  `POST /api/objects/fuente_recurso/records` `{ "attributes": { "recurso", "numero", "archivo" } }`. Es de solo inserción
  (`409` al cambiarla): corregirla es subir otra con el número siguiente.
- **Los artefactos** se leen con `GET /api/objects/artefacto_generado/records?version_recurso={versionId}` (CREADOR y
  REVISOR): `formato`, `archivo`, `sha256`, `hash_fuente`, `generador`, `version_generador`, `trabajo_id` y las relaciones
  `version_recurso` y `fuente_recurso`. Se descargan con `GET /api/objects/artefacto_generado/records/{id}/files/archivo`,
  siempre como adjunto. La API de registros no los escribe (`403`).
