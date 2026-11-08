# ADR-0043: El nombre de cada matriculado, junto a su correo, en el roster y la asistencia

**Estado**: aceptada · 2026-11-08 · amplía [ADR-0042](0042-el-nombre-de-quien-entrego-en-el-espacio-docente.md) ·
[#378](https://github.com/wasichai/elearning-backend/issues/378)

## Contexto

- ADR-0042 dio el nombre para mostrar a la cola de corrección, al panel docente y al intento, y dejó fuera el roster de
  la edición (`GET /api/lms/teaching/offerings/{code}/students`) y la asistencia de una sesión
  (`GET`/`PUT /api/lms/teaching/sessions/{id}/attendance`). Las dos siguen identificando a cada estudiante solo por el
  correo de su matrícula; el docente que pasa lista recorre una lista de correos (wasichai/elearning-frontend#179, H11).
- Wasichai 1.10.0 sigue sin `displayNamesById` en `UserDirectory`.
- `ScopeCostApiTest` vigila que el roster no ligue un arreglo de ids que crezca con sus estudiantes: el alcance de
  matrículas se resuelve con subconsultas (M4 de #183).

## Decisión

1. **`Roster` y `AttendanceRow` ganan `name`**: el nombre para mostrar de quien se matriculó, leído con `DisplayNames`
   (ADR-0034, mismas condiciones: de la organización y habilitada). `null` si la persona fue deshabilitada o borrada; el
   correo de la matrícula sigue.
2. **Mismo alcance.** Lo decide la asignación a la edición, como hoy; lo ajeno sigue siendo `404` antes de buscar nombres.
   Es un dato más para quien ya ve el correo de la misma persona.
3. **Una lectura por respuesta.** El roster (entero o una página) y la lista de asistencia leen los nombres de sus filas en
   una sentencia. Esa sentencia liga los ids de las filas que devuelve: crece con lo que la pantalla muestra, nunca más.
   `ScopeCostApiTest` lo admite con ese límite; el alcance sigue sin ligar nada que crezca.

## Alternativas consideradas

- **Leer los nombres con una subconsulta sobre las matrículas de la edición**, sin ligar ids. Rechazada: exige leer la
  tabla de la matrícula por fuera de `RecordService`, saltándose su alcance de lectura, y depender de su nombre físico.
- **Que la interfaz cruce los nombres de la cola con el roster.** Rechazada: solo cubre a quien tiene un intento por
  corregir.
- **Guardar el nombre en la matrícula.** Rechazada por lo mismo que en ADR-0034 y ADR-0042.

## Consecuencias

- Un roster sin paginar de N estudiantes liga un arreglo de N ids en la lectura de nombres. La interfaz ya puede pedir el
  roster por páginas (`?page`, `?size`).
- Cuando `UserDirectory` dé nombres por id, el ADR que reemplace a ADR-0034 cubre también este uso.
