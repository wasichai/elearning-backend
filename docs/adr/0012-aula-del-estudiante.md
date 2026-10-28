# ADR-0012: El aula lee por matrícula, aísla el contenido y exige evidencia para el progreso

**Estado**: aceptada · 2026-10-28

## Contexto

La Fase 2 abre el aula: un estudiante se matricula, recorre las unidades de su curso, consume lecciones y recursos y
conserva su progreso. Tres hechos condicionan el diseño:

- Hasta F1, ESTUDIANTE solo lee `categoria` y `curso` (#64): leía borradores por la API de registros. Para el aula debe
  leer versiones, unidades, lecciones y recursos, pero **solo** los de las versiones en que está matriculado.
- Wasichai ofrece `RecordReadScope` (ADR-048 de Wasichai): una condición SQL con valores ligados que se añade a toda
  lectura de un objeto para una persona (nunca para ADMIN ni para la plataforma). Un registro fuera de alcance se lee
  como inexistente: `404` en su ruta y `400` como destino de una relación. Las descargas de `wasichai-files` pasan por
  el mismo registro.
- La especificación (§6.4) pide progreso en el servidor, ligado a la matrícula y a la versión, con estados definidos,
  y que «el tiempo con la página abierta» no sea evidencia de aprendizaje.

## Decisión

1. **Matrícula por comando.** `matricula` es `apiOnly`: la API genérica no la escribe. `POST /api/lms/enrollments`
   (como la persona, con su `CREATE` sobre `matricula`) comprueba la ventana de inscripción en la zona horaria de la
   edición y el cupo contando bajo `ClusterLock.withXactLock("lms.edicion.<id>")`; repetirla devuelve la misma. La
   matrícula copia la `version_curso` de la edición, que ya no cambia.
2. **Alcance por matrícula, no por rol.** Un `RecordReadScope` limita a quien no tiene la acción declarada `VER_TODAS`:
   `matricula` a las suyas (`estudiante_id`), y `version_curso`, `unidad`, `leccion`, `recurso_leccion` y
   `version_recurso` a lo de las versiones de sus matrículas **activas**. DOCENTE, CREADOR y REVISOR reciben
   `VER_TODAS`; el código nunca compara nombres de rol (regla 4 de CLAUDE.md).
3. **Contenido versionado y aislado.** `recurso` → `version_recurso` (con su archivo en `wasichai-files`: directorio
   local en desarrollo y pruebas, S3 compatible por configuración; nunca en PostgreSQL) → `recurso_leccion` ordenado.
   La guarda editorial se extiende: un recurso publicado no cambia y una lección solo usa versiones publicadas. El HTML
   de una lectura se muestra en un `iframe` con `sandbox` **sin** `allow-scripts`: el contenido nunca ejecuta código en
   la aplicación, sin añadir un saneador como dependencia. PDF se descarga; el vídeo se reproduce con su archivo.
4. **Progreso con evidencia.** `progreso_leccion` (`apiOnly`) solo avanza (`INICIADA → COMPLETADA`, `APROBADA` en F3).
   Qué la completa depende del tipo de lección: `LECTURA`, `PRESENTACION` y `LABORATORIO` por finalización explícita
   del estudiante; `VIDEO` solo con el evento de fin de reproducción; `EVALUACION` y `SESION` esperan a F3. El progreso
   por unidad y total se calcula, no se guarda. Los eventos crudos van a la tabla fija `lms.evento_aprendizaje`, solo
   `INSERT`, idempotentes por un identificador que pone el cliente.
5. **API propia del aula** bajo `/api/lms/classroom/**`, con sesión, contrato en [docs/api/lms.md](../api/lms.md).

## Alternativas consideradas

- **Proyección del aula en una tabla fija** (como el catálogo, ADR-0011). Rechazada: el aula siempre tiene sesión, así
  que puede leer los registros como la persona con su alcance; una proyección duplicaría el contenido y su refresco.
- **Alcance por nombre de rol.** Prohibido por las reglas del proyecto y frágil con varios roles por cuenta.
- **Desnormalizar `version_curso` en `leccion`** para un alcance de una sola condición. Rechazada por ahora: duplica
  un dato que una guarda tendría que mantener; la lección se alcanza por sus unidades.
- **Sanear el HTML** (DOMPurify o similar) y pintarlo en la página. Rechazada en F2: una dependencia más y un riesgo
  residual de XSS que el `iframe` aislado elimina por construcción.
- **MinIO en Compose ya.** Diferido: el almacenamiento local cubre desarrollo y pruebas; S3 se activa por
  configuración (`wasichai.files.store=s3`) cuando un despliegue lo pida (F6).

## Consecuencias

- Cada lectura de esos objetos por un estudiante cuesta las consultas del alcance (sus matrículas y unidades); se
  mide y se cachea por petición si hace falta.
- Pruebas IDOR obligatorias: matrícula, lección, recurso y archivo ajenos → `404`.
- Una lectura de HTML no puede usar scripts ni estilos que dependan de ellos; el material se diseña para eso.
- El docente seguirá viendo todas las versiones con `VER_TODAS` hasta su alcance por asignación (F3).
