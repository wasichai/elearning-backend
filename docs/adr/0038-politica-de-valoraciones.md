# ADR-0038: Política de valoraciones: anonimato, muestra mínima, moderación y conservación

**Estado**: aceptada · 2026-11-03 · RF-REP-01, RF-REP-02, RF-REP-04, RF-REP-05, RF-REP-06 (parcial), RNF-SEG-10,
RNF-SEG-11 · [#268](https://github.com/wasichai/elearning-backend/issues/268) · amplía
[ADR-0037](0037-terminos-de-uso-y-supresion-de-datos-personales.md) con lo que se suprime de las valoraciones

## Contexto

- F6 trae las valoraciones de docentes y de recursos. `docs/architecture/domain-model.md` §4.6 diseñaba `valoracion` y
  `puntaje_valoracion`, pero no estaban en el modelo ni en el código. `docs/security/README.md` ya exigía solo
  matriculados, una por par, moderación y límite de tasa (§5.4), y agregados con muestra suficiente y consentimiento
  (§7).
- La investigación de #268 (Udemy, Coursera, Open edX, Moodle, evaluación docente universitaria, GDPR art. 5 y Ley 29733
  del Perú) concluye: matrícula y progreso mínimo; el anonimato se corta en el servidor, no en la pantalla; muestra
  mínima entre 4 y 6; el docente ve sus resultados **después de las notas**; se modera por motivos tasados y una opinión
  negativa pertinente no se retira.
- Los datos personales se conservan lo que aprueban los términos de uso (ADR-0030, ADR-0037).
- Un estudiante no lee las asignaciones docentes (`AssignmentReadScope`), y la plataforma (`asPlatform`) no corre dentro
  de una petición: las comprobaciones que cruzan la matrícula, la asignación y el autor de un recurso no pueden hacerse
  leyendo como quien valora.

## Decisión

### Quién valora y qué

1. Valora la persona de una `matricula` `ACTIVA` o `FINALIZADA` de la edición (`403` si no), con la acción `VALORAR`
   sobre `valoracion` (ESTUDIANTE). El destino es un **docente** (cada `docente_edicion` de la edición) o un **recurso**
   (una `version_recurso` enlazada en una lección de la versión de la matrícula).
2. **Progreso mínimo**: para un docente, el progreso del aula ≥ `lms.reputation.min-progress` (50 %) o la matrícula
   `FINALIZADA`; para un recurso, una de las lecciones que lo usan completada (`403` si no).
3. **Una valoración por par** matrícula–destino (`409`): `uniqueConstraints` reales.
4. **Ventana**: se crea, edita o retira hasta `lms.reputation.window-after-end` (30 días) tras `fecha_fin` de la
   edición, en su zona horaria (`409` fuera de ella). Editar el comentario lo devuelve a `PENDIENTE`; el registro guarda
   su historial (lo lee su autor).
5. **Dimensiones** por destino en `dimension_valoracion` (clave, nombre, destino, activa, orden), configurables por la
   organización: la demo trae claridad, utilidad, actualidad y calidad para recursos y tres para docentes. Escala entera
   de 1 a 5 y al menos una dimensión activa del destino (`400`). Comentario opcional de texto plano de hasta 2000
   caracteres; la interfaz lo muestra siempre escapado.

### Conflicto de interés y represalias

6. **Nadie valora lo suyo** (`403`): un docente de la edición no valora a ningún docente de ella, tampoco a sí mismo; el
   autor de una versión de recurso no la valora.
7. **El valorado nunca ve la identidad de quien valora**. DOCENTE y CREADOR no tienen `READ` sobre `valoracion` ni
   `puntaje_valoracion` (ni su `history` ni su auditoría, que Wasichai cierra con el mismo permiso): solo resúmenes por
   endpoints del LMS, sin matrícula, persona, fecha ni ids, con tokens opacos; y nunca «quién no valoró».
8. **Las valoraciones no tocan las notas**: el docente ve sus resultados cuando la ventana cerró y todas las matrículas
   de la edición tienen resultado, o pasado `lms.reputation.release-after-end` (60 días) tras `fecha_fin`.
   `ArchitectureTest` impide que `assessment` y `enrollment` usen `reputation`.

### Muestra mínima y agregados (RF-REP-05)

9. Un indicador se muestra con n ≥ `lms.reputation.min-sample` (5); configurado por debajo de **3**, vale 3. Si no,
   solo «muestra insuficiente», sin media ni n.
10. Los comentarios publicados llegan al docente solo con muestra suficiente, sin autor ni fecha y en orden aleatorio;
    nunca son públicos en esta versión.
11. Media simple y n por dimensión, con un **criterio publicado y versionado** (`GET /api/lms/public/ratings/criterion`).
    Los agregados de una edición se **congelan** al liberarse en la tabla fija `lms.indicador_valoracion` (sin identidad:
    destino, edición, dimensión, n, media, versión del criterio), como la proyección del catálogo (ADR-0011); los de
    recurso se recalculan cada día y al cerrar una ventana (trabajo `reputation.aggregate` en `lms.trabajo`). La cantidad
    nunca se presenta como calidad.
12. En la ficha pública del docente, solo con `perfil_publico`, el consentimiento nuevo
    `perfil_docente.valoraciones_publicas` (falso por omisión) y n ≥ k; cada cifra enlaza su criterio (RF-REP-06).

### Moderación, denuncias y apelación (RF-REP-04)

13. Los puntajes cuentan sin moderación previa; el comentario nace `PENDIENTE` y se muestra solo `PUBLICADA`.
14. Modera quien tiene la acción `MODERAR` sobre `valoracion` (hoy solo ADMIN); nadie modera una valoración que lo tiene
    como destino ni una que escribió (`403`).
15. Ocultar exige uno de los motivos tasados: `ATAQUE_PERSONAL`, `DATOS_PERSONALES`, `SPAM`, `FUERA_DE_TEMA`,
    `CONFLICTO_DE_INTERES` o `ILEGAL` (`400` sin él). Lo negativo y pertinente no se oculta.
16. El valorado (`DENUNCIAR`, DOCENTE y CREADOR) denuncia un comentario que ve, por su token; la denuncia no oculta nada
    por sí sola.
17. El autor de un comentario `OCULTA` apela una vez (`409` la segunda); la decide **otro** moderador y es final.
18. Un moderador delegado no ve la identidad del autor (`fieldPermissions` sobre `estudiante_id` y `matricula`); la
    matrícula solo la ve quien tiene `MANAGE_ORGANIZATION`.

### Abuso (RNF-SEG-10)

19. `lms.reputation.max-edits-per-day` (5 altas, ediciones o retiradas) y `max-reports-per-day` (10) por persona y día
    (UTC) → `429` con `ProblemDetail` y `Retry-After` hasta el día siguiente. Se cuentan en la tabla fija
    `lms.cuota_valoracion` en la misma transacción que el cambio: lo rechazado no cuenta. El límite global de peticiones
    queda en el ingress (#73).
20. Pedir valoraciones a cambio de algo se prohíbe en los términos de uso, no en código.

### Conservación (RNF-SEG-11)

21. Valoraciones, puntajes, comentarios, apelaciones y denuncias se conservan **mientras la persona mantenga aceptados
    los términos de uso**. La supresión de ADR-0037 suprime sus valoraciones con sus puntajes, comentarios y
    apelaciones, y su cuota; disocia sus denuncias abiertas y borra las resueltas; conserva los agregados congelados
    (anónimos y con n ≥ k) y recalcula los de recurso.
22. El autor puede retirar su valoración mientras la ventana esté abierta.

### Implementación

23. Paquete `elearning.reputation`. Custom Objects `apiOnly` (los escribe solo el LMS): `valoracion` (estudiante,
    matrícula, edición desnormalizada, destino, `docente_edicion` o `version_recurso`, comentario, estado de moderación)
    y `puntaje_valoracion` (valoración, dimensión, valor). El destino es `DOCENCIA` o `RECURSO`: nunca un nombre de rol
    en el código (`ArchitectureTest`). `docente_edicion` es un `UUID` y no un `RELATION`: Wasichai
    exige que el destino de un `RELATION` sea legible por quien escribe, y el estudiante no lee asignaciones.
24. `RatingWriteGuard` para **toda** escritura, la plataforma incluida: un destino y del tipo declarado, la misma edición
    que la matrícula, el destino de esa matrícula, lo suyo no, quien escribe a su nombre, ventana abierta para la
    persona (la plataforma suprime fuera de ella), escala válida, dimensión activa del destino, lo que identifica la
    valoración no cambia, solo quien modera publica u oculta. `RatingReadScope`: el estudiante lee lo suyo; quien
    modera, todo menos lo que lo tiene como destino; nadie más lee nada.
25. Lo que guarda y alcance comprueban sin depender de quien llama (la asignación del docente, el autor del recurso, la
    lección que lo usa) se lee por subconsultas sobre las tablas de Wasichai con la organización ligada, como
    `CreditDuties` y los alcances de ADR-0025.

### Abierto, a decidir por el titular

- **`audit_log` de Wasichai** guarda el antes y el después de cada valoración y solo lo purga su rol de purga
  (`wasichai.audit.purge-role`): la supresión de la regla 21 no lo alcanza, como en ADR-0037 §6. Debe reflejarse en los
  términos de uso.
- **Delegar `MODERAR`** en un rol propio (con los `fieldPermissions` de la regla 18): hoy solo lo tiene ADMIN.
- Los valores por defecto (progreso 50 %, k = 5, ventana de 30 días, liberación a los 60 días, límites diarios 5 y 10)
  son configuración (`lms.reputation.*`) y están a confirmar.
- **Inferencia por contexto** en ediciones pequeñas aunque n ≥ k: el piso de 3, el orden aleatorio y la falta de fechas
  la reducen, no la eliminan; la interfaz avisa al estudiante.

## Alternativas consideradas

- **Valoraciones en tablas fijas `lms`**: el anonimato sería más fácil de cerrar, pero se perdería la metadata (formularios
  de administración de dimensiones, historial, auditoría) sin necesidad: los permisos de Wasichai ya dejan al docente sin
  `READ`. Solo el agregado (que no tiene identidad) y la cuota (un contador) van a tablas fijas.
- **Leer como la persona en la guarda**: el estudiante no lee la asignación ni sabe quién es el autor de un recurso, y
  darle `READ` sobre `docente_edicion` ataría el perfil público a la cuenta del docente (lo que `AssignmentReadScope`
  evita). Descartada.
- **Calcular el resumen al vuelo**: un cambio posterior a la liberación movería cifras que el docente ya vio. Se congela.
- **Ocultar al valorado solo en la interfaz** (Moodle anónimo): el nombre seguiría al alcance de la API. Descartada.

## Consecuencias

- `RatingApiTest` y las pruebas de cada paso de #268 usan sujetos de un solo rol, nunca ADMIN, y releen los datos tras
  cada negativa.
- La interfaz del aula ofrece valorar desde la matrícula y la del docente su resumen (elearning-frontend).
  `/api/auth/me/permissions` de Wasichai solo lista los objetos que el rol lee, así que no muestra `VER_RESUMEN` ni
  `DENUNCIAR` al valorado: la interfaz pide el resumen y un `403` le dice que no lo tiene.
- Una dimensión nueva o desactivada no cambia lo congelado; cambiar la fórmula exige una versión nueva del criterio.
- `PersonalDataErasure` añade su paso y su prueba (ADR-0037, «una capacidad nueva que guarde datos personales»).
