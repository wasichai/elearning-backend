# ADR-0042: El nombre de quien entregó, junto a su correo, en el espacio docente

**Estado**: aceptada · 2026-11-08 · amplía la regla 3 de [ADR-0034](0034-nombres-para-mostrar-desde-la-tabla-de-personas-de-wasichai.md) ·
[#376](https://github.com/wasichai/elearning-backend/issues/376)

## Contexto

- La cola de corrección (`GET /api/lms/teaching/offerings/{code}/attempts`), el panel docente (`oldestPending`) y el
  intento que lee su docente identifican a quien entregó solo por `estudiante_correo` de la matrícula. Quien corrige
  recorre una lista de correos; las plataformas de referencia muestran el nombre (épica de interfaz
  wasichai/elearning-frontend#179, H11).
- ADR-0034 resolvió los nombres para mostrar con `DisplayNames` y dijo que el nombre va «donde ya va el id, y nada más».
  Estas respuestas no llevan el id de la persona: llevan su correo, que la identifica igual y es más sensible que el
  nombre.
- Wasichai 1.10.0 sigue sin `displayNamesById` en `UserDirectory`.

## Decisión

1. **`PendingAttempt` y el `Attempt` del docente ganan `name`**: el nombre para mostrar de quien entregó, leído con
   `DisplayNames` (ADR-0034, mismas condiciones: de la organización y habilitada). `null` si la persona fue deshabilitada o
   borrada; el correo de la matrícula sigue como estaba.
2. **Solo donde ya va el correo de esa persona.** El alcance no cambia: la cola y el intento siguen decididos por la
   asignación a la edición (o por `MANAGE_ORGANIZATION`, solo leer), y lo ajeno sigue siendo `404` antes de buscar ningún
   nombre. El roster y la asistencia no cambian en este paso.
3. **Por lotes.** La cola y cada página del panel hacen una lectura de nombres para todos sus intentos; el intento, una.
   Las pruebas de coste existentes (`TeachingPanelApiTest`, `TeachingCostApiTest`) lo vigilan.

## Alternativas consideradas

- **Solo el correo, como hasta ahora.** Rechazada: es lo que la interfaz quiere dejar de mostrar en primer plano.
- **Guardar el nombre en la matrícula al crearla, como el correo.** Rechazada por lo mismo que en ADR-0034: duplica un
  dato que la persona cambia y seguiría mostrando a quien fue deshabilitado o suprimido.
- **Devolver el id del estudiante para que la interfaz pida el nombre.** Rechazada: un dato más sin uso y una lectura por
  persona.

## Consecuencias

- Un dato personal más en estas respuestas, para quien ya ve el correo de la misma persona: no se abre a nadie nuevo.
- La supresión de ADR-0037 no toca la cuenta de Wasichai: el nombre deja de salir aquí cuando quien administra la
  deshabilita o la borra, como en el estudio.
- Cuando `UserDirectory` dé nombres por id, el ADR que reemplace a ADR-0034 cubre también este uso.
