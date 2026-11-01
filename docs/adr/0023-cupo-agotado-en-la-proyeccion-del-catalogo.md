# ADR-0023: El cupo agotado llega al catálogo como un sí o un no, por la proyección

**Estado**: aceptada · 2026-11-01 · amplía [ADR-0011](0011-catalogo-publico-leido-de-una-proyeccion.md) y
[ADR-0013](0013-estructura-publicada-visible-recursos-por-matricula.md) §2

## Contexto

RF-CAT-09 pide que la ficha diga el estado de la matrícula de cada edición: abierta, cerrada o **completa**. El cupo
ocupado vive en `lms.ocupacion_edicion` (ADR-0013), que el comando de matrícula suma bajo cerrojo; la proyección pública
`lms.catalogo_publicado` no lo tenía, y el filtro `enrollmentOpen` de RF-CAT-08 decía expresamente que no miraba el cupo.

## Decisión

1. **Qué se publica: un booleano por edición, `full`** (matriculados ≥ capacidad; sin capacidad, nunca). Ni el número de
   matriculados, ni las plazas libres, ni quién está matriculado. La capacidad ya era pública; con «completa» basta para
   decidir si matricularse, y un número exacto contaría la demanda de cada edición sin que ningún requisito lo pida.
2. **Cómo llega: lo escribe la proyección**, que en cada refresco lee `lms.ocupacion_edicion` de la organización pública
   con `DatabaseClient` (en segundo plano, como el resto de su lectura). La ruta anónima sigue leyendo solo
   `lms.catalogo_publicado`: la barrera física de ADR-0011 no cambia, ni entran en la ficha los identificadores internos
   de las ediciones que haría falta para cruzar la ocupación al servir. Como `full` solo cambia al agotarse (o liberarse)
   el cupo, una matrícula que no lo agota no cambia el `hash` y no reescribe nada.
3. **El estado se decide al leer**, como `enrollmentOpen` (`OfferingCalendar`): `enrollmentStatus` es `CLOSED` fuera del
   estado `INSCRIPCION_ABIERTA` o de la ventana, `FULL` dentro de ella con `full`, y `OPEN` en otro caso.
   `enrollmentOpen` pasa a ser `enrollmentStatus = OPEN`: una edición completa no admite matrículas, así que tampoco cuenta
   para el filtro `enrollmentOpen=true`, que comprueba lo mismo en SQL.

## Alternativas consideradas

- **Leer la ocupación al servir** (cruzar la tabla en cada petición anónima). Rechazada: la ruta pública leería una
  segunda tabla, que guarda por `organizacion_id` y `edicion_id`; la ficha tendría que llevar esos identificadores, y el
  filtro `enrollmentOpen`, un cruce por cada edición del `jsonb`. Todo para ganar, como mucho, `lms.catalog.refresh`.
- **Reproyectar la edición al matricular.** Rechazada: la proyección lee con `RecordService.asPlatform`, que Wasichai
  prohíbe dentro de una petición (ADR-0011); haría falta un trabajo por matrícula para adelantar lo que el refresco hace
  igual.
- **Publicar las plazas libres.** Rechazada por lo dicho en 1; si un requisito lo pide, es un campo más de la ficha.

## Consecuencias

- Una edición que se llena tarda hasta `lms.catalog.refresh` (30 s por defecto) en salir como completa. Quien la vea
  abierta en ese intervalo recibe del comando de matrícula un `409` «La edición no tiene cupo»: decide el servidor.
- Las filas proyectadas antes de este cambio se leen con `full = false` y se reescriben en el primer refresco, porque
  la ficha cambia de forma y con ella su `hash`: no hace falta migración.
- Los campos nuevos (`full`, `enrollmentStatus`, también en `nextOffering`) son adicionales: un cliente que no los lee
  sigue funcionando, y `enrollmentOpen` solo cambia de `true` a `false` en las ediciones completas.
