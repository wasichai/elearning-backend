# ADR-0035: Los créditos los aprueba una autoridad académica, con separación de funciones

**Estado**: aceptada · 2026-11-03 · complementa [ADR-0007](0007-carga-academica-y-creditos-como-politica-versionada.md)
(«aprobar es F6») · RF-CRE-08 · [#269](https://github.com/wasichai/elearning-backend/issues/269),
[#315](https://github.com/wasichai/elearning-backend/issues/315)

## Contexto

- ADR-0007 separa créditos **calculados**, **propuestos** y **aprobados**, y deja la aprobación para F6: «una persona
  autorizada lo aprueba en un acto registrado y auditado». `version_curso` ya tenía `creditos_aprobados` y
  `politica_creditos` (texto `id@versión`).
- **Fallo encontrado** por la investigación de #269: `EditorialWorkflowGuard` solo exigía `APROBAR` —la acción
  **editorial** del revisor— para escribir `creditos_aprobados`. Quien aprobaba el contenido ponía un número con un
  `PATCH` genérico, sin política, sin evidencia institucional y sin separación de funciones; solo quedaba el cambio en
  `audit_log`.
- La investigación de #269 (EEES/ECTS, 34 CFR 600.2, Ley 30220 del Perú, SATCA, SCT) concluye que **en ninguna
  jurisdicción aprueba el docente ni el autor del curso**: es un acto institucional (Consejo de Facultad, comité de
  currículo, _registrar_) respaldado por un documento. La plataforma registra ese acto y lo liga a la regla de cálculo; no
  lo sustituye.
- Las políticas de crédito en datos, por jurisdicción, institución y programa (RF-CRE-07), llegan en un paso aparte de
  #269. Hasta entonces las únicas políticas son las de referencia en código: `EctsPolicy` y `SemesterCreditHourPolicy`.

## Decisión

1. **Acción propia `APROBAR_CREDITOS` sobre `version_curso`**, distinta de `APROBAR`, exigida en el servidor con
   `CurrentUser.requirePermission`. Rol nuevo **`AUTORIDAD_ACADEMICA`** («Autoridad académica (créditos)»): quien actúa
   por el órgano que reconoce créditos. Lee categorías, cursos, versiones, temario y ediciones; tiene `UPDATE` sobre
   `version_curso` solo porque el comando escribe como la persona (la guarda no le deja cambiar nada más) y `READ` y
   `CREATE` sobre `aprobacion_creditos`. CREADOR, DOCENTE, REVISOR y ESTUDIANTE no la tienen.
2. **Un acto, una fila: `aprobacion_creditos`** (Custom Object `appendOnly` y `apiOnly`). Tipo `APROBACION` o
   `REVOCACION`; la versión; la **instantánea** de la política aplicada (id, versión, sistema, método, parámetros en JSON,
   fuente) y del cálculo (horas totales, créditos calculados y propuestos); los créditos aprobados; el motivo (obligatorio
   si difiere de lo propuesto y al revocar); el órgano y el documento que lo respaldan (obligatorios), su fecha; quién y
   cuándo. Nada se cambia ni se borra: revocar inserta otra fila que nombra la aprobación revocada (`sustituye`).
3. **Comandos** en `elearning.credits` (`/api/lms/credits/**`): ver la propuesta y la aprobación vigente, el historial,
   aprobar y revocar. Aprobar exige una versión `APROBADA` o `PUBLICADA` con sistema de crédito (`409` si no), sin otra
   aprobación vigente (`409`: se revoca antes), y **recalcula en el servidor** con la política; el número enviado, si
   difiere de lo propuesto, necesita motivo (`400`).
4. **La política usada es, por ahora, la de referencia del sistema de la versión** (`ects-carga-total@1`,
   `sch-34cfr600.2-trabajo-equivalente@1`), y su instantánea queda en la fila. El paso de RF-CRE-07 la sustituye por la
   versión de política ligada a la versión del curso, sin cambiar lo ya aprobado.
5. **Separación de funciones por datos, en el servidor, también para ADMIN.** No aprueba ni revoca los créditos de una
   versión su autor (`autor_id`), su revisor asignado (`revisor_id`), quien la aprobó en la bandeja (fila `APROBAR` de
   `lms.decision_revision`) ni quien tiene una asignación docente en una edición de alguna versión del curso
   (`docente_edicion.usuario_id`) → `403`. La última comprobación lee las tablas de Wasichai con una subconsulta, como los
   alcances de [ADR-0025](0025-alcances-de-lectura-con-subconsultas-sobre-tablas-de-wasichai.md), para no depender de
   lo que la autoridad pueda leer; sin el modelo para saberlo, falla cerrada.
6. **`creditos_aprobados` y `politica_creditos` solo los escribe el comando**, en la misma transacción que su fila y bajo
   un candado por versión: `EditorialWorkflowGuard` exige la marca `CreditApprovalWrite` y además `APROBAR_CREDITOS`; por
   la API de registros es `403` para todos, ADMIN incluido, y una versión no nace con ellos. Son una copia de lo vigente
   para quien lee la versión; la fuente de verdad es la fila.
7. **El catálogo** muestra `approved` solo con una aprobación vigente (la última fila de la versión es una
   `APROBACION`), junto con el órgano, el documento, su fecha y la política, nunca quién la registró. Si no, los créditos
   son propuestos y no oficiales. Una versión nueva del curso no hereda la aprobación (`DRAFT_DROPS`): se aprueba otra vez.
8. **Lectura**: el historial lo lee quien tiene `READ` sobre `aprobacion_creditos` (el personal y la autoridad), nunca
   ESTUDIANTE. No hace falta un `RecordReadScope`: todo rol que lo lee ya lee todas las versiones (`VER_TODAS`), y la
   versión se lee como quien llama (lo ajeno, `404`).

## Alternativas consideradas

- **Seguir con `APROBAR`.** Rechazada: es el fallo. Revisar contenido y reconocer créditos son actos de órganos distintos.
- **Una tabla fija `lms.aprobacion_creditos`**, como `lms.decision_revision`. Rechazada: el Custom Object da `appendOnly`,
  `apiOnly`, el `audit_log` de Wasichai con persona y hora sin código extra, y la lectura por la API de registros.
- **Separación de funciones por rol** (p. ej. «un REVISOR nunca aprueba créditos»). Rechazada: compara nombres de rol
  (regla 4) y no cubre a ADMIN ni a una persona con varios roles; lo que importa es qué hizo esa persona con esa versión.
- **Cuatro ojos al aprobar** (dos autoridades). Fuera de alcance: el acto ya lo respalda un órgano colegiado y su
  documento; queda abierto para las políticas (paso 2).
- **Recalcular en el cliente.** Rechazada: el servidor recalcula y guarda la instantánea; el cliente solo envía el número
  si difiere, con motivo.

## Consecuencias

- Cada crédito aprobado se puede explicar: qué carga, qué política y versión, qué órgano, qué documento, quién y cuándo,
  en la fila y en `/api/audit`.
- Cierra el fallo de #269: el `PATCH` genérico de `creditos_aprobados` es `403` para todos.
- ADMIN tiene todas las acciones en Wasichai: la separación por datos le aplica, pero puede asignarse el rol; lo mitiga la
  revisión de las entradas `admin:*` (docs/security §3).
- Coste: un objeto, un rol y una acción más, y una dependencia `credits → authoring` (para leer las decisiones de la
  bandeja).
- La interfaz debe decir que la plataforma solo propone y registra: el reconocimiento oficial es de la institución.
