# ADR-0041: Los recursos de un curso se revisan y publican antes que el curso

**Estado**: aceptada · 2026-11-03 · [#366](https://github.com/wasichai/elearning-backend/issues/366); precisa
[ADR-0012](0012-aula-del-estudiante.md), [ADR-0018](0018-fase-4-estudio-revision-e-importador.md) y
[ADR-0021](0021-la-revision-de-un-curso-se-decide-en-la-bandeja.md)

## Contexto

Una versión de recurso sigue el mismo flujo editorial que una versión de curso (ADR-0012): `BORRADOR → EN_REVISION →
APROBADA → PUBLICADA`, con su autor, sin autoaprobación y con lo publicado inmutable. `EditorialWorkflowGuard` exigía que
una lección solo enlazara versiones de recurso `PUBLICADAS`, pero solo al escribir el enlace (`recurso_leccion`) y solo
para personas: la plataforma (`userId` nulo) no pasa por la guarda.

El borrador de lección de #283 (`LessonDraftWriter`) escribe como la plataforma, por lección, un `recurso` y una
`version_recurso` en `BORRADOR` a nombre de quien lo pidió, enlazada a la lección. Resultado (hallazgo alto de la revisión
de seguridad de la Fase 5, #302):

- la `version_curso` podía ir a revisión, aprobarse y publicarse sin que nadie hubiera revisado su material;
- el alcance del estudiante (`ContentReadScope`, `ClassroomReader`) no miraba el estado de la versión de recurso;
- la autora podía seguir editando ese material en borrador con el curso ya publicado: lo publicado cambiaba (regla 5).

## Decisión

1. **Cada recurso pasa su propia revisión antes que el curso.** Llevar una `version_curso` a `EN_REVISION`, `APROBADA` o
   `PUBLICADA` es `409` si alguna lección de su temario enlaza una `version_recurso` que no está `PUBLICADA` (o que ya no
   existe); el mensaje nombra cada lección con la versión y su estado. Lo exige `EditorialWorkflowGuard` en toda escritura de
   una persona: el envío del estudio, la decisión de la bandeja y la API de registros. Se comprueba al final, tras los
   permisos: quien no puede dar el paso recibe su `403`.
2. **El flujo del borrador generado**: la autora envía a revisión cada `version_recurso` (por la API de registros), otra
   persona con `APROBAR` la aprueba (con motivo si pide cambios o la rechaza, ADR-0021) y quien tiene `PUBLICAR` la
   publica; luego la autora envía el curso, que se valida (docs/ai §13), se revisa en la bandeja y se publica. Nunca se
   publican solos ni por la plataforma: el trabajo de IA no cambia estados (`AiWorkGuard`).
3. **Lo enlazado desde lo revisado no cambia.** Una `version_recurso` en `BORRADOR` enlazada desde una `version_curso` que
   ya no es borrador no cambia de contenido ni se borra (`409`); su flujo sí sigue (se puede enviar a revisión y publicar).
   Cubre los datos anteriores a esta decisión y lo que escriba la plataforma.
4. **Defensa en profundidad en la lectura.** `ContentReadScope` solo sirve al estudiante versiones de recurso `PUBLICADAS`
   (y los recursos de esas versiones), aunque una lección de su matrícula enlace otra. `ClassroomReader` filtra igual para
   quien lee con `VER_TODAS`. Una versión de recurso que se retira deja de servirse.
5. **Coste**: una sentencia SQL por comprobación, sea cual sea el temario (`CurriculumResources`, subconsultas sobre las
   tablas de Wasichai como `CreditDuties`, ADR-0025), leída sin el alcance de quien escribe: la guarda decide sobre lo que
   hay, no sobre lo que esa persona ve. Sin el modelo para saberlo, falla cerrada.

## Alternativas consideradas

- **Publicar los recursos junto con el curso en la misma decisión** (la bandeja aprobaría y publicaría también sus
  versiones de recurso). Ahorra pasos, pero la decisión de la bandeja es sobre la versión de curso (ADR-0021) y una
  `version_recurso` puede servir a lecciones de cursos distintos (RF-CON-01): su aprobación tiene que quedar como suya, con
  su rastro. Se puede revisar cuando la bandeja admita recursos.
- **Que la plataforma no enlace recursos en borrador** (el borrador generado sin material): el borrador perdería lo que lo
  hace útil, y el agujero seguiría para cualquier otro código de confianza.
- **Solo filtrar la lectura del estudiante**: el curso publicado seguiría prometiendo material que nadie ve ni revisó.

## Consecuencias

- Un borrador generado necesita, antes de enviarse, una revisión por recurso. El estudio puede mostrar después qué
  recursos faltan (el `409` ya los nombra).
- Una versión de recurso retirada desaparece del aula de los cursos que la enlazan: retirar es una decisión editorial y el
  curso se corrige con una versión nueva.
- Sin migraciones.
