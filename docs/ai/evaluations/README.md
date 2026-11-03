# Comparación reproducible de modelos (RF-IA-09)

Conjuntos fijos y criterios de [docs/ai §9](../README.md#9-comparación-reproducible-de-modelos), uno por tarea. Se
escriben y se confirman **antes** de ejecutar: `./gradlew aiEval` se niega a correr contra proveedores reales si esta
carpeta tiene cambios sin confirmar, y cada ejecución guarda el commit y el sha256 de `set.json` y `criteria.md`.

| Tarea                                       | Paso                            | Criterios                       |
| ------------------------------------------- | ------------------------------- | ------------------------------- |
| [`source-analysis`](source-analysis/)       | Análisis de fuentes (E33.4)     | `source-analysis-criteria@1`    |
| [`consistency-review`](consistency-review/) | Opinión de consistencia (E34.2) | `consistency-review-criteria@1` |

El diseño pedagógico (E33.5), el borrador de lecciones (E33.6) y los guiones (E36.1) entran aquí cuando estén en `main`.

Cada carpeta tiene `set.json` (casos y lo esperado) y `criteria.md` (puntuaciones automáticas, que calcula código, y la
rúbrica humana). Los resultados no se guardan aquí: van a `build/ai-eval/<inicio>/` (cómo se ejecuta y qué queda, en
[docs/ai §9](../README.md#9-comparación-reproducible-de-modelos)); un informe que se quiera conservar se copia a mano a
`results/` dentro de su tarea, con el nombre de su inicio.

Aparte, [`demo-unit/`](demo-unit/README.md) no es una tarea comparada: es el procedimiento y la plantilla de la ejecución
manual de la unidad de demostración de extremo a extremo con cada proveedor (E38.1, #300).
