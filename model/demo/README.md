# Datos de demostración

`data.json` contiene usuarios y registros **sintéticos** para probar el LMS de punta a punta (issue #19). Todo es
ficticio e ilustrativo: las versiones llevan `contenido_ilustrativo: true` y ningún contenido ha sido validado por un
experto. Solo para entornos de desarrollo y demostración: **nunca en producción**.

## Cómo se aplican

```bash
python3 model/apply.py --validate-only --demo     # valida el modelo, los roles y data.json, sin red
WASICHAI_DEMO_PASSWORD='…' python3 model/apply.py --url http://localhost:8090 --email … --password … --demo
```

`--demo` aplica primero el modelo y los roles y después los datos. La contraseña de los usuarios de demostración se pasa
**solo** por `WASICHAI_DEMO_PASSWORD` (es obligatoria con `--demo`) y nunca se escribe en disco; la deciden quien
instala el entorno y la política de contraseñas de Wasichai.

La aplicación es idempotente: cada usuario se busca por su correo y cada registro por su clave natural antes de crearlo,
así que una segunda ejecución no escribe nada. Lo que ya existe **no se modifica** (una versión publicada es
inmutable); la única excepción son los roles de un usuario de demostración, que se dejan en los declarados. Si el
servidor tiene dos registros con la misma clave natural, el script se detiene con un error.

## Formato

```json
{
  "users": [{ "email": "docente@demo.wasichai-learning.test", "displayName": "Diego Docente (demo)", "roles": ["DOCENTE"] }],
  "records": [
    {
      "ref": "version-srtm-1",
      "object": "version_curso",
      "key": ["curso", "numero"],
      "attributes": { "numero": 1, "estado": "PUBLICADA" },
      "refs": { "curso": "curso-srtm" }
    },
    {
      "ref": "perfil-diego",
      "object": "perfil_docente",
      "key": ["usuario_id"],
      "attributes": { "nombre_publico": "Diego Docente (demo)" },
      "users": { "usuario_id": "docente@demo.wasichai-learning.test" }
    }
  ]
}
```

| Clave        | Qué es                                                                                                                                |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------- |
| `users`      | Correo (dominio `demo.wasichai-learning.test`), nombre visible y roles (`roles.json` o `ADMIN`). Sin contraseña.                      |
| `ref`        | Alias del registro, solo de este archivo: minúsculas, dígitos y guiones, único.                                                       |
| `object`     | Custom Object de `model.json`.                                                                                                        |
| `key`        | Campos que identifican el registro: un campo `unique` o una `uniqueConstraints` del modelo. Sus valores salen de los demás apartados. |
| `attributes` | Campos propios con su tipo: fechas ISO 8601, `DATETIME` con zona, opciones de ENUM válidas.                                           |
| `refs`       | Campo RELATION → `ref` de un registro **anterior** en el archivo y del objeto destino de la relación; se envía su `id` del servidor.  |
| `users`      | Campo UUID → correo de un usuario de `users`; se envía su `id` de Wasichai (así se enlaza `perfil_docente.usuario_id`).               |

Los registros van ordenados por dependencias. `apply.py --validate-only --demo` comprueba todo lo anterior, además de
los campos y relaciones obligatorios y que ninguna clave natural se repita.

### Lo que hace una persona: matrículas, sesiones, intentos y notas

Lo que en el producto es un comando del LMS, la demo lo hace por ese comando e iniciando sesión como quien lo haría, así
que pasa por las mismas reglas (ventana de inscripción, asignación del docente, plazos, motivo al cambiar una nota):

| Clave         | Qué hace                                                                                                                 |
| ------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `workflows`   | El camino de estados de cada objeto; `commands` da un paso por una ruta `/api/lms/` (aprobar por la bandeja).            |
| `enrollments` | `{ user, offering }`: el estudiante se matricula con `POST /api/lms/enrollments`, en una edición abierta hoy.            |
| `sessions`    | Las crea el docente asignado a la edición con el comando de sesiones.                                                    |
| `attempts`    | `{ student, offering, lesson, teacher, answers, grades }`: el estudiante responde y envía; el docente asignado califica. |

En `attempts`, `answers` y `grades` nombran preguntas y opciones por su `ref`. El estudiante debe estar en `enrollments`
para esa edición, el docente asignado a ella (`docente_edicion`), la lección tener evaluación y cada nota ir a una
pregunta `ABIERTA` o `ARCHIVO`, dentro de sus puntos y con comentario. Es idempotente como lo demás: un intento ya enviado
no se rehace y una pregunta ya calificada no se vuelve a calificar; uno que quedó a medias se termina en la siguiente
ejecución.

## Contenido

| Código         | Curso                                                                  | Versiones                     | Ediciones         |
| -------------- | ---------------------------------------------------------------------- | ----------------------------- | ----------------- |
| `SRTM-ANU-101` | A · Rentas municipales (SRTM): acceso al portal y registro de anuncios | v1 `PUBLICADA`, v2 `BORRADOR` | 2026-11 y 2027-03 |
| `SGSPE-101`    | B · SGSPE: primeros pasos y gestión de compromisos                     | v1 `PUBLICADA`                | 2027-01           |
| `IA-AGT-201`   | C · Agentes de IA con trazabilidad y revisión humana                   | v1 `PUBLICADA`                | 2027-04           |
| `SRTM-ATC-001` | Atención al contribuyente en ventanilla (solo borrador)                | v1 `BORRADOR`                 | ninguna           |
| `CORTO-101`    | Curso corto: mensajes de error claros                                  | v1 `PUBLICADA`                | abierta hoy       |
| `TRAMITE-101`  | Instrucciones claras para un trámite en línea (recorrido editorial)    | v1 `PUBLICADA`                | abierta hoy       |

- La v2 de A y el curso `SRTM-ATC-001` sirven para comprobar que lo no publicado no se ve en el catálogo.
- `TRAMITE-101` es solo del recorrido editorial (e2e real del frontend, issue #224): cada ejecución copia su v1, la
  revisa y publica otra versión, y matricula a un estudiante nuevo en `TRAMITE-101-A`. Publicar saca del catálogo las
  ediciones de la versión anterior, así que ningún otro recorrido ni la matrícula de la demo lo usan.
- C usa la carga de ejemplo de la especificación: 6 semanas × (12 h de teoría + 12 h de práctica o laboratorio + 12 h
  autónomas), sistema ECTS y **sin** `creditos_aprobados`: los créditos los aprueba una persona con su regla.
- Ediciones en `America/Lima`, entre noviembre de 2026 y junio de 2027, con periodo de inscripción y capacidad.

### Fuentes de A y B

Las lecciones de A y B se derivan de secciones reales de los manuales y citan su origen en `referencia_fuente` con la
forma `repositorio@commit:ruta#ancla`:

- A: `wasichai/srtm-backend@6daacd3`, `docs/manuales/comun/acceso.adoc` y `docs/manuales/usuario/anuncios.adoc`.
- B: `wasichai/sgspe@0ebf69f`, `docs/manuals/user/02-primeros-pasos.adoc` y `docs/manuals/user/05-compromisos.adoc`.

Cada ancla se comprobó en el archivo de ese commit; la lista está en `model/tests/test_demo.py` (`VERIFIED_ANCHORS`) y
las pruebas fallan si una lección cita otra. En `05-compromisos.adoc` solo el capítulo tiene ancla explícita
(`usr-compromisos`): las demás son las que genera Asciidoctor con los atributos del manual (`idprefix` vacío,
`idseparator` `-`), y ningún otro título del manual de usuario las repite.

Ninguno de esos repositorios declara licencia (ver `docs/research/reference-repositories.md` §6), así que **no se copia
su texto**: solo títulos de sección, resúmenes escritos para este proyecto y referencias. Tampoco se reutilizan los
datos de sus capturas ni el nombre de la municipalidad de los manuales.

### Usuarios

Uno por rol (`estudiante@`, `docente@`, `creador@`, `revisor@`) y uno con dos roles (`docente.revisor@`, DOCENTE y
REVISOR), todos en `demo.wasichai-learning.test`, con nombres ficticios evidentes. Los dos docentes tienen
`perfil_docente` y están asignados a ediciones con `docente_edicion`.

### Matrículas, intentos y calificaciones

`estudiante@` está matriculado en `SRTM-ANU-101-A` y en `CORTO-101-A`. En el curso corto ya respondió la evaluación y
`docente@`, su docente asignado, corrigió la pregunta abierta: 8 de 10 puntos, aprobada con el 80 %. El intento queda en la
cola de lo corregido (`?status=GRADED`) y con su nota en el historial; la sesión y la asistencia quedan por hacer. Los
recorridos e2e crean sus propios estudiantes, así que este intento no cambia sus conteos.
