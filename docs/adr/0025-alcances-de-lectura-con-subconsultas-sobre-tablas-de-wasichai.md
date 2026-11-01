# ADR-0025: Los alcances de lectura se resuelven con subconsultas sobre las tablas de Wasichai

**Estado**: aceptada · 2026-11-01 · provisional hasta que Wasichai ofrezca una vía propia
([wasichai/wasichai#147](https://github.com/wasichai/wasichai/issues/147)) · precisa cómo se calculan los alcances de
[ADR-0012](0012-aula-del-estudiante.md), [ADR-0013](0013-estructura-publicada-visible-recursos-por-matricula.md) y
[ADR-0014](0014-docente-sin-ver-todas-y-recurso-congelado.md), sin cambiar qué ve cada persona

## Contexto

- `ContentReadScope` y `EnrollmentReadScope` son `RecordReadScope`
  ([ADR-048 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0048-a-read-scope-narrows-what-a-caller-reads.md)):
  devuelven un `RecordCriterion` que Wasichai añade a la lectura. Lo que se ve de un objeto depende de otros (una lección,
  de la unidad, la versión y la matrícula; el progreso del docente, de las matrículas de sus ediciones).
- Hasta [#236](https://github.com/wasichai/elearning-backend/pull/236) esos objetos se leían **como quien llama** con
  `RecordService` y sus ids se ligaban como un arreglo (`ANY(?)`). Wasichai aplicaba así sus permisos, la organización y
  `ownRecordsOnly`, pero el arreglo crecía con lo que la pantalla no muestra. Medido en `ScopeCostApiTest`: el arreglo
  ligado más largo del aula pasaba de 16 a 19 con tres cursos publicados más (todas las unidades de todas las versiones
  visibles) y el del roster de 3 a 8 con seis estudiantes más.
- Es lo que pedían M1 de [#104](https://github.com/wasichai/elearning-backend/issues/104) («los arreglos ligados crecen
  con el catálogo») y M4 de [#183](https://github.com/wasichai/elearning-backend/issues/183) (con 1000 estudiantes, unas
  2000 listas de 1000 filas en un GET; «alcance con subconsulta SQL»). #198 ya calculaba el alcance una vez por petición
  y #202 y #234 leían por lotes: el coste que quedaba era el del arreglo.
- La regla 1 de CLAUDE.md prohíbe usar las clases internas de Wasichai (`RecordStore`, `ObjectSchemaManager`,
  `AuditService`). `WasichaiSchemas` y `MetadataService` son API pública; cada Custom Object es una tabla física en
  `app_data` ([ADR-004 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0004-physical-table-per-object.md)).

## Decisión

1. **Los dos alcances construyen su criterio con subconsultas SQL** sobre las tablas de datos de Wasichai, en la misma
   sentencia que la lectura. La tabla sale de `WasichaiSchemas.dataTable(definition.obj.physicalTable)` y las columnas,
   de los campos de `MetadataService.loadDefinition` (en caché), entrecomilladas con `SqlIdentifier.quote`; todo valor va
   ligado. `ContentReadScope` resuelve la cadena matrícula → versión → unidad → lección → enlace → recurso;
   `EnrollmentReadScope` lee el progreso, las entregas y la asistencia del docente por la subconsulta de las matrículas de
   sus ediciones. La matrícula y las sesiones siguen ligando las ediciones de la persona: ese arreglo no crece con nadie más.
2. **Cada nivel repite a mano lo que la lectura como quien llama aplicaba:** su `organization_id` (ligado); su acción
   `VER_TODAS`, que quita el filtro de ese nivel; y, sin `READ` sobre ese objeto, nada (falla cerrada, donde antes la
   lectura interna daba `403`). Un objeto o un campo que no existe también da nada. `EnrollmentReadScope` solo necesita
   la organización y el `READ` de `matricula`: el alcance de esas matrículas ya lo da la edición asignada. Sin nombres de
   rol (regla 4).
3. **`ownRecordsOnly` no se repite**, porque ningún rol de `model/roles.json` lo fija. Si alguno lo fija, este ADR se
   revisa antes (ver Consecuencias).
4. **Es provisional.** Se pidió a Wasichai que un `RecordReadScope` pueda expresar su criterio como subconsulta sobre otro
   objeto y que Wasichai aplique los permisos, la organización y `ownRecordsOnly` de quien llama
   ([wasichai/wasichai#147](https://github.com/wasichai/wasichai/issues/147)). Cuando exista, un ADR nuevo reemplaza este
   y los alcances pasan a esa vía.

## Alternativas consideradas

- **Leer como quien llama, con listas de ids** (lo anterior). Rechazada por su coste: el arreglo ligado crece con el
  catálogo publicado y con los estudiantes, y cada nivel suma sentencias. Su ventaja, que la semántica de permisos es
  la de Wasichai, es justo lo que pide #147.
- **Revertir #236.** Rechazada: vuelve al coste medido sin ganar nada que la prueba de alcance no vigile ya. Es la vía de
  salida si una versión de Wasichai rompe las subconsultas y no hay arreglo inmediato.
- **Pedir a Wasichai una API y esperar.** Rechazada como único camino: M4 y M1 son hallazgos de disponibilidad abiertos y
  la API no tiene fecha. Se pide igualmente (#147), con este proyecto como segundo usuario concreto, y se adopta al
  llegar.
- **Desnormalizar `version_curso` en `leccion`** (valorado en M1). Innecesario: la subconsulta resuelve la cadena en la
  misma sentencia, y duplicaría un dato que la guarda editorial tendría que mantener.

## Consecuencias

- **Se duplica** de Wasichai: el filtro por organización, el efecto de `VER_TODAS` y «sin `READ`, nada», nivel a nivel.
  **No se duplica** `ownRecordsOnly` ni los alcances propios de los objetos intermedios: la subconsulta de matrículas
  dentro de `ContentReadScope` filtra por `estudiante = quien llama` en lugar de pasar por `EnrollmentReadScope`. Un rol
  nuevo con `ownRecordsOnly` sobre `matricula`, `version_curso`, `unidad`, `leccion` o los recursos ampliaría lo que se ve
  sin que nada falle: ese cambio de `model/roles.json` exige revisar estos dos alcances.
- **Riesgo: se depende de cómo Wasichai guarda sus tablas**: la tabla física por objeto en `app_data`, la columna `id`,
  `organization_id` y el nombre de columna de cada campo. Si una versión cambia eso (borrado lógico, otra columna de
  organización, tablas compartidas), el alcance puede dar nada o, peor, de más, y sin error de compilación.
- **Cómo se protege:**
  - las pruebas de alcance (`ContentScopeApiTest`, `EnrollmentScopeApiTest`, `TeacherScopeApiTest`) prueban qué ve y qué
    no ve cada persona, también lo ajeno, contra un Wasichai real en Testcontainers;
  - las de coste (`ScopeCostApiTest`, `ClassroomCostApiTest`, `TeachingCostApiTest`) vigilan que el arreglo ligado no
    crezca y que el número de sentencias no suba;
  - **al subir de versión de Wasichai** se revisa en su `docs/HISTORY.md` y sus ADR cualquier cambio en las tablas de
    datos (`WasichaiSchemas`, `physicalTable`, `columnName`, `organization_id`, borrado de registros), en la semántica de
    lectura (`ownRecordsOnly`, acciones de «ver todas», alcances) y el estado de #147, y se ejecutan esas pruebas antes de
    integrar.
- El contrato de la API no cambia: es la misma respuesta con menos coste.
