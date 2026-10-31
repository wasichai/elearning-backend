# ADR-0020: Lo que las revisiones de las Fases 2 y 3 dejaron como está, y cuánto se guardan los eventos

**Estado**: aceptada · 2026-10-31 · precisa [ADR-0016](0016-fase-3-evaluaciones-docente-sesiones-certificados.md)

## Contexto

Las revisiones de seguridad del cierre de las Fases 2 (#104) y 3 (#183) dejaron hallazgos que no son fallos, sino preguntas
de producto:

- El nombre del certificado (`holderName`) lo escribe el estudiante, sin validarlo contra su cuenta, y la verificación
  pública lo muestra.
- Marcar `PRESENTE` o `TARDE` en una sesión futura completa ya la lección enlazada.
- `lms.evento_aprendizaje` crece con cada evento nuevo, también los `STARTED` que no cambian nada, y su disparador impide
  borrar.

## Decisión

1. **El certificado lleva el nombre tal como lo escribe el estudiante.** No acredita identidad: certifica que esa matrícula
   cumplió las reglas de su versión. La verificación pública muestra ese nombre y el código; nunca el correo ni el id de la
   cuenta.
2. **Asistir completa la lección, sea cual sea la fecha de la sesión.** La asistencia la registra el docente asignado, y él
   responde de cuándo la marca. Cambiarla después exige motivo y queda en el historial.
3. **Los eventos de aprendizaje se guardan mientras el estudiante acepte los términos.** Son evidencia del progreso y de la
   finalización. Cuando retire esa aceptación, se suprimen los suyos.

## Consecuencias

- El punto 3 necesita la aceptación de términos, que aún no existe, y una supresión que pase por encima del disparador de
  solo inserción como operación de plataforma auditada. Va con la privacidad de la Fase 6 (`docs/security/README.md` §7).
  Mientras tanto no se borra ningún evento.
- Sin límite de eventos por persona, el volumen depende del límite de peticiones de la ruta (#73).
- Un intento vencido y abandonado sigue `OPEN` hasta que alguien lo toca; el barrido que lo cierra con su plazo va con la
  cola persistente de E26.1 (ADR-0018).
