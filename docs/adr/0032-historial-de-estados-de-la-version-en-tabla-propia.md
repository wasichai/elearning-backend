# ADR-0032: El historial de estados de una versión es una tabla propia de solo inserción

**Estado**: aceptada · 2026-11-03 · precisa [ADR-0018](0018-fase-4-estudio-revision-e-importador.md) §2 y
[ADR-0021](0021-la-revision-de-un-curso-se-decide-en-la-bandeja.md)

## Contexto

El estudio y la bandeja muestran una versión del curso con su `status` y su `updatedAt`, y con las decisiones de revisión
de `lms.decision_revision`. Las decisiones no cubren cada transición: enviar a revisión, retirarla a borrador, publicar o
retirar una publicada no son decisiones. La interfaz derivaba la línea de estados sin saber quién movió la versión ni
cuándo (#259, épica #253).

`estado` es un campo de `version_curso` y cambia por cuatro vías: el estudio (enviar a revisión), la bandeja (decidir y
publicar), la API genérica de registros de Wasichai (el autor la retira a borrador, quien publica la retira) y la plataforma
(la carga de la demo, la importación). Wasichai guarda cada escritura en su `audit_log`, pero es interno: la regla 1 de
CLAUDE.md prohíbe leer sus tablas y clases internas (`AuditService`).

## Decisión

1. **Tabla fija propia `lms.transicion_version`** (migración V16, ADR-0004): organización, versión, estado anterior (nulo
   en la creación), estado nuevo, quién (nulo es la plataforma) y cuándo (`clock_timestamp()`). Un disparador impide
   `UPDATE` y `DELETE`, como en `lms.decision_revision`. Un `id` de identidad da el orden en que se escribieron.
2. **La escribe un `RecordChangeListener`** (`VersionTransitionListener`), el SPI con el que Wasichai avisa de cada
   escritura de un registro, sea cual sea la vía. Inserta una fila al crear una `version_curso` y cada vez que su `estado`
   antes y después difieren; escribir otros campos no deja nada. Wasichai llama a los listeners justo después de escribir,
   en la transacción de quien escribe ([ADR-038 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0038-record-service-joins-the-callers-transaction.md)):
   la fila se confirma o se deshace con el cambio. El estudio (enviar a revisión, crear una versión), la bandeja (decidir,
   publicar) y la importación ya escriben en una transacción.
3. **El detalle del estudio y de la bandeja trae `history`**: `[{ from, to, by, byName, at }]`, la más antigua primero, con
   el nombre de quien la movió leído junto con los demás nombres de la versión (`DisplayNames`, ADR-0034). Se lee con las
   mismas reglas que el detalle: quien no ve la versión recibe `404`.

## Alternativas consideradas

- **Leer el `audit_log` de Wasichai.** Ya tiene antes, después, quién y cuándo de cada escritura. Rechazada: es una tabla
  interna de Wasichai (regla 1); su forma puede cambiar en cualquier versión, y filtrar sus filas por el cambio de un campo
  de un JSON es un contrato que Wasichai no ofrece.
- **Un campo en el registro** (el estado anterior, o una lista de transiciones en un JSON de `version_curso`). Rechazada:
  quien escribe el registro por la API de registros reescribiría también su historial, y `EditorialWorkflowGuard` congela
  todo campo de una versión fuera de borrador (regla 5), así que habría que abrirle una excepción. Una tabla de solo
  inserción no tiene ninguno de los dos problemas.
- **Escribir la fila desde cada servicio** (estudio, bandeja). Rechazada: la API de registros y la plataforma quedarían sin
  historial. El listener es la única puerta por la que pasan todas.

## Consecuencias

- La API genérica de registros de Wasichai no abre una transacción: por ella, el cambio de estado, su fila del `audit_log`
  y la del historial se confirman cada una por su lado. Si la inserción del historial fallara tras el cambio, la petición
  responde `500` y el cambio queda sin su fila, con la misma garantía que Wasichai da a su propio `audit_log`. Las vías
  propias (estudio, bandeja, importación) sí son atómicas.
- La supresión de datos personales ([ADR-0037](0037-terminos-de-uso-y-supresion-de-datos-personales.md)) no la toca,
  como a las decisiones de revisión: es el registro de quién movió qué, que la organización debe poder rendir.
- Una versión borrada (solo en borrador) deja sus filas, como sus decisiones: nadie las lee sin la versión.
- `version_recurso` no tiene historial: no tiene estudio ni bandeja que lo muestre. Si llega una vista que lo pida, se
  extiende el mismo listener.
