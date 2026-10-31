# ADR-0021: La revisión de un curso se decide solo en la bandeja; la de un recurso, con motivo

**Estado**: aceptada · 2026-10-31 · precisa [ADR-0018](0018-fase-4-estudio-revision-e-importador.md) §2

## Contexto

ADR-0018 §2 dice que cada decisión de revisión (`APROBAR`, `PEDIR_CAMBIOS`, `RECHAZAR`) queda en `lms.decision_revision`,
de solo inserción, y que lleva motivo salvo aprobar. La bandeja (`POST /api/lms/review/versions/{id}/decision`) lo cumple:
inserta la decisión y transita en la misma transacción, con un cerrojo por versión. Pero `EditorialWorkflowGuard` también
admitía la misma transición por la API genérica de registros (`PATCH /api/objects/version_curso/records/{id}` con
`estado`), y por ahí no quedaba decisión ni se exigía motivo: solo la fila del `audit_log` de Wasichai (M1 de #216).

`version_recurso` sigue el mismo flujo editorial (ADR-0012), pero no tiene bandeja ni decisiones propias:
`lms.decision_revision` está ligada a `version_curso`.

## Decisión

1. **La revisión de una `version_curso` se decide solo en la bandeja.** La guarda rechaza (`403`) que quien decide saque
   una versión de `EN_REVISION` por cualquier otra vía. La bandeja marca su escritura con un elemento del contexto de la
   corrutina (`ReviewDecisionWrite`), como propone Wasichai para lo que una aplicación escribe en proceso (ADR-040 de
   Wasichai); la API de registros no lo lleva. El autor sigue pudiendo retirar su versión a borrador: no es una decisión.
2. **Una `version_curso` no nace aprobada.** Crearla directamente en `APROBADA` sería una aprobación sin revisión ni
   decisión: `403`, también para quien publica.
3. **La revisión de una `version_recurso` sigue en la API de registros, con motivo.** Pedir cambios o rechazar exige la
   cabecera `X-Change-Reason` (`400` sin ella), que Wasichai guarda en el `audit_log` de la escritura (ADR-041 de
   Wasichai). Aprobar no la necesita. Nadie crea una versión de recurso propia ya aprobada.

## Alternativas consideradas

- **Que la guarda escriba la decisión y exija el motivo por la cabecera.** Rechazada: la guarda corre antes de la escritura
  y no garantiza estar en una transacción con ella, así que podría quedar una decisión sin transición; además habría dos
  caminos que escriben decisiones, con su cerrojo duplicado. Una sola puerta es más fácil de razonar.
- **Una bandeja para recursos.** Rechazada por ahora: no hay un segundo usuario que la pida. Si llega, sigue este ADR.

## Consecuencias

- Las pruebas y el script de la demo aprueban versiones de curso por la bandeja (`model/demo/data.json`, `commands`).
- Cada salida de revisión de un curso por quien decide tiene su fila en `lms.decision_revision`; la de un recurso, su motivo
  en el `audit_log`, salvo al aprobar.
