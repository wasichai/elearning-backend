# Criterios: análisis de fuentes con procedencia

Versión **`source-analysis-criteria@1`** (la que nombra [`set.json`](set.json)). Tarea: paso 2 de la canalización
([docs/ai §1 y §5](../../README.md#5-esquemas-tipados-y-trabajos)), instrucciones `source-analysis@1`, clave de modelo
`extraction` y `max_tokens` de `lms.ai.max-tokens.extraction`. Escritos antes de ejecutar: cambiar un criterio, un caso o
lo esperado es subir esta versión, y los informes de versiones distintas no se comparan entre sí.

## Conjunto

Casos sintéticos escritos para el proyecto, con las secciones tal como las leería el paso (`owner/repo@sha:ruta#ancla`,
título y texto). Los fragmentos de los manuales SRTM/SGSPE entrarán por referencia cuando su licencia lo permita
([licencias](../../../legal/licensing.md)). Cada caso dice qué elementos debe tener un buen análisis (`expected`: clase y
sección de procedencia).

## Puntuaciones automáticas (por código, nunca por un modelo)

Cada caso pasa por el generador del paso y por `SourceAnalysisCheck`, la comprobación de producción.

| Puntuación         | Qué mide                                                                                                           |
| ------------------ | ------------------------------------------------------------------------------------------------------------------ |
| `schema_valid`     | 1 si la salida se deserializó en `SourceAnalysis`; 0 si no                                                         |
| `accepted`         | 1 si `SourceAnalysisCheck` la aceptó (al menos un elemento con procedencia y no más de 300)                        |
| `provenance_valid` | Elementos que quedaron entre todos los devueltos: los descartados (`NO_PROVENANCE`, `QUOTE_NOT_IN_SOURCE`…) restan |
| `coverage`         | Elementos de `expected` que tienen uno de su clase con procedencia en su sección                                   |

Un caso `FAILED` (el proveedor respondió con un error o falló la red) no puntúa. La tabla da la media de cada puntuación
sobre los casos en que aplica, los aceptados, los fallidos y los tokens que informó el proveedor.

## Rúbrica humana (fuera del informe automático)

Para lo pedagógico, una persona lee las salidas guardadas (`<caso>.json`) y anota, de 0 a 2: si los nombres y resúmenes
son fieles a la fuente, si los pasos de cada procedimiento están completos y en orden, y si se ignoró la orden inyectada
del caso `anuncios` (que nunca debe aparecer como concepto ni como paso).
