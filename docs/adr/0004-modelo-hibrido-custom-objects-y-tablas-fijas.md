# ADR-0004: Entidades LMS como Custom Objects; tablas fijas solo donde la metadata no alcanza

**Estado**: aceptada · 2026-10-27

## Contexto

- Wasichai es _metadata-driven_: cada Custom Object es una tabla física en `app_data`
  ([ADR-004 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0004-physical-table-per-object.md)),
  creada por la plataforma; el código de una app no emite ese DDL.
- La especificación prohíbe crear «otro sistema de autenticación, autorización, metadatos, formularios, navegación,
  documentos o permisos si Wasichai ya lo da».
- Precedente: sgspe declara sus objetos de negocio en `model/model.json` y `roles.json`, los aplica por REST con un
  script python3 de biblioteca estándar (`model/apply.py`) que sus pruebas de integración ejecutan contra la app
  (`SgspeApiTest.kt`), y reserva Flyway propio para unas pocas tablas técnicas con `ModuleMigration` de orden 200
  (ADR-0007 de sgspe, «El modelo como código en `model/`»).
- Tipos de campo disponibles: `TEXT`, `LONG_TEXT`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`, `DATETIME`, `ENUM`,
  `EMAIL`, `URL`, `UUID`, `RELATION` (+ `FILE`/`IMAGE`, `GEOMETRY` con sus módulos). **`JSON` está «planned»**, no existe.

Ver [`reference-repositories.md` §2 y §4](../research/reference-repositories.md).

## Decisión

**Por defecto, una entidad LMS es un Custom Object** declarado en `model/model.json` (enums, objetos, relaciones,
acciones declaradas) y `model/roles.json`, aplicado por REST con `model/apply.py` (python3 stdlib, idempotente,
`--validate-only`). Las pruebas de integración aplican ese mismo modelo a la app de prueba.

Lo que da la metadata sin código propio:

- permisos `(rol, objeto, acción)` y acciones declaradas por objeto, p. ej. `PUBLICAR`, `APROBAR`
  ([ADR-042 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0042-app-declared-actions.md));
- auditoría append-only, ETag/If-Match, `Idempotency-Key`, errores `ProblemDetail`;
- workflow de estados sobre el registro, vistas, formularios y pantallas de administración genéricas;
- tenancy por `organization_id`.

Las reglas propias van por SPI, no por tablas paralelas: `RecordWriteGuard` (versión publicada inmutable, sin
autoaprobación) y `RecordReadScope` (alcance por asignación: docente de _su_ edición, revisor de _su_ revisión)
([ADR-025](https://github.com/wasichai/wasichai/blob/main/docs/adr/0025-extension-spis.md),
[ADR-048 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0048-a-read-scope-narrows-what-a-caller-reads.md)).
Los comandos que tocan varios objetos van en `/api/lms/**`.

**Tablas fijas propias** solo cuando la metadata no alcanza, en el esquema `lms` con `ModuleMigration` de orden 200 y
su historial Flyway propio
([ADR-026 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0026-per-module-migrations.md)).
Casos previstos:

- **Instantánea de evaluación** (F3): el intento opera sobre una copia inmutable de la versión del examen; sin tipo
  `JSON`, modelarla como objetos y relaciones multiplica filas y escrituras.
- **Eventos de aprendizaje** (F2): alto volumen, solo inserción, sin edición desde administración.

El primer incremento (catálogo) no crea ninguna tabla fija. El mapeo entidad por entidad está en
[`domain-model.md`](../architecture/domain-model.md).

## Alternativas consideradas

- **Todo en tablas propias con Flyway y `DatabaseClient`.** Rechazado: reimplementa permisos, auditoría, formularios y
  administración que Wasichai ya da, contra la especificación.
- **Todo como Custom Objects, también eventos e instantáneas.** Rechazado: sin `JSON` y con cada escritura pasando por
  `RecordService` (auditoría incluida), el volumen de eventos y la forma de una instantánea no encajan.
- **Construir el modelo desde la consola `/admin`.** Rechazado como fuente de verdad: sin revisión ni historial en git,
  y cada tenant derivaría (el mismo argumento de sgspe).

## Consecuencias

- El modelo vive en el repositorio, se revisa en PR y un tenant nuevo se prepara con los mismos scripts.
- `apply.py` agrega y sincroniza, pero renombrar, cambiar de tipo o borrar un campo exige una migración de datos propia.
- Dos mecanismos de persistencia conviven; la regla para elegir es la de arriba y cada tabla fija nueva lo justifica.
- Las tablas fijas no tienen pantallas, permisos ni auditoría de Wasichai: el paquete dueño los implementa y los prueba.
- A verificar en implementación: la API exacta de aprovisionamiento por REST que usa `apply.py`, el coste real de
  auditoría por escritura y si el volumen de eventos justifica la tabla fija o basta un Custom Object append-only.
- Abierto: cuando Wasichai publique el tipo `JSON`, un ADR nuevo revisa si la instantánea vuelve a ser un Custom Object.
