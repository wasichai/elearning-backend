# ADR-0031: Los nombres para mostrar se leen de la tabla de personas de Wasichai

**Estado**: aceptada · 2026-11-03 · provisional hasta que `UserDirectory` de Wasichai dé nombres por id ·
[#258](https://github.com/wasichai/elearning-backend/issues/258)

## Contexto

- El estudio y la bandeja devuelven `authorId` y `reviewerId` de la versión y el `authorId` de cada comentario y de cada
  decisión: identificadores que no dicen quién. #258 decidió devolver junto a cada id el **nombre para mostrar** de la
  persona, nunca su correo, solo en las respuestas que ya exponen ese id y a quien ya puede leer la versión, resuelto por
  lotes, y `null` si la persona está deshabilitada o fue borrada.
- `UserDirectory` es la vía pública de Wasichai para encontrar personas sin tocar sus tablas
  ([ADR-045 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0045-organizational-units.md)). En
  Wasichai 1.8.0 da ids por correo, qué ids son personas habilitadas, todas las habilitadas y correos por id; **no da el
  nombre para mostrar**. `emailsById` daría el correo, que es justo lo que no se quiere entregar.
- La regla 1 de CLAUDE.md prohíbe reimplementar lo de Wasichai y usar sus clases internas. [ADR-0025](0025-alcances-de-lectura-con-subconsultas-sobre-tablas-de-wasichai.md)
  ya aceptó, como provisional, leer tablas de Wasichai con SQL propio cuando su API no alcanza.

## Decisión

1. **`elearning.common.DisplayNames` lee `id` y `display_name` de `${WasichaiSchemas.metadata}.users`** en una sola
   sentencia por respuesta, con los ids ligados como arreglo y las mismas condiciones que `UserDirectory.existing`: de la
   organización de quien llama y `enabled`. Una persona deshabilitada, borrada o de otra organización, y una cuenta de
   servicio (su fila está deshabilitada), no aparecen: su nombre es `null` y su id sigue.
2. **Solo se lee el nombre.** Ni el correo ni ningún otro dato de la persona sale de esa sentencia.
3. **El nombre va donde ya va el id, y nada más.** `StudioReader` arma el resumen, el detalle y cada comentario, así que
   quien no podía leer la versión sigue recibiendo `404`/`403` antes de que se busque ningún nombre. El resumen de varias
   versiones (mis versiones, la bandeja) y el detalle con sus comentarios y decisiones hacen **una** lectura de nombres para
   todas las personas: el coste no crece con ellas (`AuthoringCostApiTest`).
4. **Es provisional.** Cuando `UserDirectory` ofrezca nombres por id (`displayNamesById`, con las condiciones de
   `existing`), un ADR nuevo reemplaza este y `DisplayNames` pasa a esa vía o desaparece. Se pide a Wasichai con este
   proyecto como segundo usuario concreto (issue por abrir en wasichai/wasichai).

## Alternativas consideradas

- **Devolver el correo con `emailsById`.** Rechazada: es dato personal que la pantalla no necesita, y #258 lo excluye.
- **Pedir el nombre a Wasichai y esperar.** Rechazada como único camino: la API no tiene fecha y la interfaz muestra ids.
- **Una lectura por persona** (`GET /api/users/{id}` o una sentencia por id). Rechazada: el coste crecería con las personas,
  y la API de administración de personas exige administrar la organización.
- **Guardar el nombre en la versión, el comentario o la decisión al escribirlos.** Rechazada: duplicaría un dato que la
  persona puede cambiar y mostraría el nombre de quien ya fue deshabilitado.

## Consecuencias

- **Riesgo: se depende de cómo Wasichai guarda a las personas**: la tabla `users` en su esquema de metadatos y sus columnas
  `id`, `organization_id`, `enabled` y `display_name`. Si una versión las cambia, la sentencia falla (un error, no un dato de
  más) y las pruebas de `AuthoringNamesApiTest` lo detectan contra un Wasichai real en Testcontainers.
- **Al subir de versión de Wasichai** se revisa en su `docs/HISTORY.md` cualquier cambio en `users` y si `UserDirectory`
  ya da nombres; si los da, se reemplaza este ADR.
- La API gana `authorName` y `reviewerName` junto a sus ids; ningún campo cambia de sentido.
