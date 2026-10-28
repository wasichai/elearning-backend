# ADR-0013: El estudiante ve la estructura de lo publicado; los recursos, solo matriculado

**Estado**: aceptada · 2026-10-28 · precisa el punto 2 de [ADR-0012](0012-aula-del-estudiante.md)

## Contexto

ADR-0012 limitaba a un estudiante a las versiones, unidades y lecciones de sus matrículas activas. Al escribir el
comando de matrícula aparecieron dos límites de Wasichai:

- Un valor `RELATION` que escribe una persona debe nombrar un registro **que esa persona puede leer** (D29/D30 de
  Wasichai): una matrícula nueva apunta a su edición y a su versión, y antes de matricularse el estudiante no podía
  leer ninguna de las dos. El comando nunca habría podido crearla.
- Contar el cupo exige ver las matrículas de los demás, que el alcance (con razón) le oculta.

## Decisión

1. **Lo publicado se ve; los recursos, solo matriculado.** Quien no tiene `VER_TODAS` lee `version_curso` en estado
   `PUBLICADA` **o** de sus matrículas activas (una retirada que ya cursaba la sigue viendo), las unidades y lecciones
   de esas versiones y las `edicion_curso` de esas versiones. Es lo que el catálogo público ya muestra (temario,
   ediciones); los borradores siguen invisibles. El **contenido** —`recurso_leccion` y `version_recurso`, con sus
   archivos— queda solo para las versiones de sus matrículas activas.
2. **El cupo se cuenta en una tabla propia.** `lms.ocupacion_edicion(organizacion_id, edicion_id, matriculados)` se
   bloquea con `SELECT … FOR UPDATE` dentro de la misma transacción (`TransactionalOperator`, ADR-038 de Wasichai) que
   crea la matrícula con `RecordService` y suma uno: dos altas simultáneas no superan el cupo y, si la matrícula
   falla, el contador vuelve atrás con ella.

## Alternativas consideradas

- **Crear la matrícula como la plataforma.** Imposible en una petición (`asPlatform` lanza, ADR-0011).
- **Una cuenta de servicio con `VER_TODAS` para el comando.** Otra identidad con credenciales que custodiar para
  saltarse una regla que aquí tiene sentido.
- **Contar con SQL directo sobre las tablas de Wasichai.** Depende de su almacenamiento interno (rechazado en ADR-0011).

## Consecuencias

- Un estudiante ve, por la API de registros, el temario de cualquier curso publicado (igual que en el catálogo), pero
  ningún recurso ni archivo de un curso en el que no está matriculado.
- El contador es dato propio: las matrículas que cree el código como la plataforma (pruebas, importaciones) no lo
  mueven; la retirada de una matrícula (F3) lo descuenta en la misma transacción.
