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

| nombre       | tipo                                          | regla                                                                          |
| ------------ | --------------------------------------------- | ------------------------------------------------------------------------------ |
| `q`          | texto ≤ 100                                   | búsqueda sin distinguir mayúsculas en título y resumen                         |
| `category`   | slug                                          | slug de la categoría                                                           |
| `level`      | `INICIAL` \| `INTERMEDIO` \| `AVANZADO`       |                                                                                |
| `modality`   | `ASINCRONA` \| `SINCRONA` \| `HIBRIDA`        |                                                                                |
| `startsFrom` | `YYYY-MM-DD`                                  | solo cursos con una edición abierta o programada que empieza ese día o después |
| `sort`       | `upcoming` (defecto) \| `title` \| `duration` | `upcoming`: próxima edición primero, sin edición al final                      |
| `page`       | entero ≥ 0 (defecto 0)                        |                                                                                |
| `size`       | 1–50 (defecto 12)                             | fuera de rango → **400** con `errors[{field:"size"}]`                          |

Un valor inválido (enum desconocido, fecha mal formada, `size` fuera de rango, `q` > 100) → **400** con `errors`.

Respuesta 200:

```json
{
  "content": [CourseSummary],
  "page": 0, "size": 12, "totalElements": 3, "totalPages": 1,
  "facets": { "categories": [{ "slug": "gestion-municipal", "name": "Gestión municipal", "count": 2 }] }
}
```

`facets.categories` cuenta los cursos publicados por categoría **sin** aplicar el filtro `category` (para pintar filtros).

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
  `ASINCRONA` \| `SINCRONA` \| `HIBRIDA`; edición `status` `PROGRAMADA` \| `INSCRIPCION_ABIERTA` \| `EN_CURSO` (las
  `FINALIZADA` y `CANCELADA` no se publican); lección `type` `LECTURA` \| `VIDEO` \| `PRESENTACION` \| `LABORATORIO` \|
  `EVALUACION` \| `SESION`; docente `role` `TITULAR` \| `ASISTENTE`. Un valor nuevo llega primero al modelo y aquí.
- **Pueden ser `null`**: `summary`, `category`, `nextOffering`, `prerequisiteKnowledge`, `version.publishedAt`,
  `requirements`, `assessment`, `certification`, `credits.system`, `credits.approved`, `credits.proposal`;
  en unidades `description` y `estimatedHours`; en lecciones `durationMinutes`, `summary` y `source`; en ediciones
  `enrollmentFrom`, `enrollmentUntil`, `capacity` y `schedule`; en docentes `professionalTitle`, `bio` y `specialties`.
  Las listas nunca son `null` (como mucho, vacías).
- `credits.proposal.parameters` es un mapa de texto a texto (`"27"`, no `27`).
- `weeks` y `workload.weeks` son el mismo valor.
- `sort` no admite dirección: cada orden tiene la suya (próximos y duración ascendentes, título alfabético).

## Contrato OpenAPI

`GET /api/lms/public/openapi` (público) sirve el contrato OpenAPI 3 de `/api/lms/**` generado por springdoc 3.2.0
([ADR-0008](../adr/0008-openapi-con-springdoc.md)); no incluye la API de Wasichai.
