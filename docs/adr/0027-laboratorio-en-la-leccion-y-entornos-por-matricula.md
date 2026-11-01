# ADR-0027: El laboratorio son campos de su lección; sus entornos, un objeto que solo leen los matriculados

**Estado**: aceptada · 2026-11-01 · amplía [ADR-0013](0013-estructura-publicada-visible-recursos-por-matricula.md) y
[ADR-0016](0016-fase-3-evaluaciones-docente-sesiones-certificados.md) §3

## Contexto

RF-LAB-01 pide que un laboratorio tenga objetivo, requisitos, guía, archivos, hitos, criterios de aceptación,
entregables, rúbrica y enlaces a entornos externos, y que el aula lo muestre. Hoy un laboratorio es una lección
`LABORATORIO` con su evaluación de una pregunta de archivo y rúbrica (ADR-0016 §3): la guía y los archivos son sus
recursos (`recurso_leccion`) y la rúbrica, `criterio_rubrica`. RF-LAB-03 y RNF-SEG-08 piden que los enlaces a entornos
sean `https` hacia un host público y que el servidor no los ejecute.

Lo publicado no cambia (`EditorialWorkflowGuard`), y la estructura publicada la lee cualquiera con `READ` sobre la
lección, matriculado o no (ADR-0013). Los recursos, en cambio, solo los leen los matriculados.

## Decisión

1. **Texto en la lección.** `leccion` gana `objetivo`, `requisitos`, `hitos`, `criterios_aceptacion` y `entregables`, todos
   `LONG_TEXT` y, salvo el objetivo, «uno por línea» como `version_curso.objetivos`. Un hito es una línea, en orden: no
   tiene fecha ni estado propios. Al ser de la lección se congelan con su versión, se copian a la siguiente
   (`NewVersionService`) y aparecen en las diferencias sin código nuevo.
2. **Los entornos, un Custom Object `entorno_laboratorio`** (`leccion`, `orden`, `titulo`, `enlace`), con `VER_TODAS` y los
   mismos permisos que `recurso_leccion`: los escribe CREADOR; ESTUDIANTE, DOCENTE y REVISOR solo los leen. Lo justifica el
   alcance, no el orden: `ContentReadScope` lo limita, como a los recursos, a las lecciones de las matrículas propias, así
   que quien no está matriculado no recibe el enlace aunque lea la estructura publicada. Es temario: la guarda editorial lo
   congela con la versión de su lección, `NewVersionService` lo copia y `VersionDiffer` lo compara (`entornos`).
3. **El enlace se valida con `SafeLink`**, la regla de las sesiones (https, sin credenciales, IPv4 solo canónica y pública,
   sin `localhost`, `.local` ni `.internal`), en `LabEnvironmentGuard` para toda escritura, la plataforma incluida: es un
   dato inválido (`400` en `enlace`), no un permiso. El servidor no resuelve DNS, no visita el enlace y no sigue
   redirecciones: solo lo muestra. El aula vuelve a aplicar `SafeLink` al leer, por si la regla se endurece.
4. **El aula** devuelve `lab` en cada lección `LABORATORIO` (`null` en las demás): las listas ya partidas por líneas y los
   entornos en su orden. La guía, los archivos y la rúbrica siguen donde estaban (recursos y evaluación).

## Alternativas consideradas

- **Todo como campos de la lección**, con los enlaces en un `LONG_TEXT` de una URL por línea. Rechazada: el enlace lo
  vería quien lee la estructura publicada sin matrícula, no tendría título y la validación sería por línea.
- **Un objeto `hito_laboratorio`** con fecha y estado. Rechazada por ahora: RF-LAB-01 solo pide nombrarlos, y las fechas de
  una edición ya las llevan sus sesiones y la evaluación. Si se pide seguimiento por hito, se reemplaza este ADR.
- **Un objeto `laboratorio` aparte de la lección.** Rechazada: duplicaría la lección (orden, unidad, progreso, completitud)
  y su lugar en la guarda, la copia y el alcance.

## Consecuencias

- Los campos de laboratorio existen en todas las lecciones; el aula solo los muestra en las `LABORATORIO`. Ninguna guarda
  impide escribirlos o crear un entorno en otro tipo de lección.
- El objetivo, los requisitos, los hitos, los criterios y los entregables de un curso publicado los lee quien lee su
  estructura, como el resumen de la lección; los entornos, solo los matriculados.
- El estudio del creador aún no los edita en una pantalla propia: se escriben por la API de registros (o los formularios
  de Wasichai).
