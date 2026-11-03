# Criterios: opinión de consistencia contenido-evaluación

Versión **`consistency-review-criteria@1`** (la que nombra [`set.json`](set.json)). Tarea: la opinión del paso 6
([docs/ai §13](../../README.md#13-informe-de-validación-rf-ia-05)), instrucciones `consistency-review@1`, clave de modelo
`consistency` y `max_tokens` de `lms.ai.max-tokens.consistency`. Escritos antes de ejecutar: cambiar un criterio, un caso o
lo esperado es subir esta versión.

## Conjunto

Borradores sintéticos (`DraftSnapshot`, lo que lee el paso) con una incoherencia conocida o sin ninguna, y la opinión
esperada: el veredicto y los registros (`objeto:id`) que debe señalar.

## Puntuaciones automáticas (por código, nunca por un modelo)

Cada caso pasa por `ConsistencyReviewer` y por `ConsistencyReviewCheck`, la comprobación de producción.

| Puntuación      | Qué mide                                                                                          |
| --------------- | ------------------------------------------------------------------------------------------------- |
| `schema_valid`  | 1 si la salida se deserializó en `ConsistencyReview`; 0 si no                                     |
| `accepted`      | 1 si `ConsistencyReviewCheck` la aceptó (con resumen y no más de 50 hallazgos)                    |
| `origins_valid` | Hallazgos que quedaron entre todos los devueltos: uno que cita un registro inexistente resta      |
| `verdict`       | 1 si el veredicto es el esperado (`APPROVED` o `CONCERNS`)                                        |
| `origin_recall` | Registros esperados que señala algún hallazgo que quedó; no aplica a un caso sin ninguno esperado |

Un caso `FAILED` no puntúa. Esta tarea no tiene doble determinista: una opinión inventada sin modelo no aporta nada.

## Rúbrica humana (fuera del informe automático)

Una persona lee cada hallazgo guardado y anota, de 0 a 2, si describe de verdad una incoherencia del borrador y si el
resumen es útil para quien revisa.
