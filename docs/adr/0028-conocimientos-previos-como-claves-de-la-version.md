# ADR-0028: Los conocimientos previos se declaran como claves de un vocabulario en la versión

**Estado**: aceptada · 2026-11-01 · amplía [ADR-0011](0011-catalogo-publico-leido-de-una-proyeccion.md) y
[ADR-0024](0024-prerrequisitos-como-campo-de-la-version.md)

## Contexto

RF-CAT-08 pide filtrar el catálogo por conocimientos previos. `curso.conocimientos_previos` es texto libre para la ficha:
«Ninguno sobre el sistema, se recomienda…» no se puede filtrar. Hace falta el campo estructurado más pequeño con el que
el filtro signifique algo. Wasichai 1.8.0 no tiene un tipo de campo de selección múltiple (sus tipos son `TEXT`,
`LONG_TEXT`, `ENUM` de un solo valor, `RELATION`… y los que añade un módulo); lo más cercano es una relación
`MANY_TO_MANY`.

## Decisión

1. **Un vocabulario, el Custom Object `conocimiento_previo`** (`clave` única, `nombre`, `descripcion`). Lo escribe ADMIN,
   como las categorías; todo el personal y el estudiante lo leen. La `clave` son minúsculas, dígitos y guiones, y `none`
   está reservada (es el valor del filtro «sin conocimientos previos»).
2. **Un campo de `version_curso`, `conocimientos_previos_claves` (`LONG_TEXT`)**, con las claves que el curso supone, una
   por línea, igual que `cursos_previos` (ADR-0024). Al ser de la versión se congela con ella al publicarse
   (`EditorialWorkflowGuard`) y `NewVersionService` la copia a la versión siguiente sin código nuevo. El texto libre de
   `curso.conocimientos_previos` sigue siendo la descripción para personas.
3. **`PrerequisiteKnowledgeGuard`** hace cumplir, para toda escritura: el formato de cada clave; que una persona solo
   declare claves del vocabulario (la plataforma, código de confianza, solo pasa el formato), y que una clave del
   vocabulario no cambie ni se borre, porque la nombran versiones publicadas. El `nombre` sí se corrige.
4. **El catálogo lee lo publicado**: la ficha lleva `prerequisiteKnowledgeTags` (`key` y `name`) de la última versión
   publicada; el filtro `prerequisiteKnowledge=<clave>|none` y la faceta `facets.prerequisiteKnowledge` se resuelven sobre
   esa ficha en `lms.catalogo_publicado`, como los filtros de horas. No hace falta migración (no hay `V14`): el campo nuevo
   cambia el hash de cada ficha y el siguiente refresco reescribe todas las filas; mientras, una ficha sin el campo
   cuenta como «sin claves».

## Alternativas consideradas

- **Una relación `MANY_TO_MANY` versión ↔ `conocimiento_previo`.** Daría un selector en la ficha de la versión, pero
  pide copiar los enlaces en `NewVersionService`, cargarlos en la semilla (`apply.py` no carga enlaces), leerlos uno a
  uno por versión al proyectar y proteger el borrado del vocabulario, que con `ON DELETE CASCADE` quitaría el enlace a una
  versión publicada sin pasar por su guarda. Las mismas razones de ADR-0024. Si el texto con claves se queda corto, un ADR
  nuevo reemplaza el campo por la relación.
- **El campo en `curso`, junto al texto libre.** Rechazada: `curso` no se congela al publicar, así que el filtro dejaría
  de reflejar lo publicado y cambiaría sin versión nueva.
- **Una lista cerrada en el código (un `enum` o propiedades).** Rechazada: cambiar el vocabulario pediría desplegar los
  dos repositorios y las etiquetas no vendrían del servidor.

## Consecuencias

- El creador escribe las claves en un texto largo, no las elige de una lista; las ve en «Conocimientos previos»
  (`/data/objects/conocimiento_previo/records`) y un `400` nombra la que no existe. Un selector múltiple se pedirá a
  Wasichai con este proyecto como usuario concreto.
- Una clave creada por error se queda (sin cursos no aparece en la faceta); se corrige su nombre.
- La demostración ya instalada no gana las claves en sus versiones publicadas: la semilla no modifica lo que existe.
