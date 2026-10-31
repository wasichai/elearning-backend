# ADR-0019: El docente no lee las claves, nadie se acredita a sí mismo y las soluciones llegan al terminar

**Estado**: aceptada · 2026-10-31 · precisa [ADR-0016](0016-fase-3-evaluaciones-docente-sesiones-certificados.md)

## Contexto

La revisión de seguridad del cierre de la Fase 3 encontró tres huecos en lo decidido:

- DOCENTE tenía `READ` sobre `evaluacion`, `pregunta`, `opcion` y `criterio_rubrica`, sin alcance: cualquier docente leía las
  soluciones de todos los cursos, también en borrador, y una cuenta puede ser además estudiante.
- Un docente matriculado en su propia edición veía sus soluciones por el espacio docente y se marcaba asistencia, lo que basta
  para la regla de asistencia y el certificado.
- Con `AL_ENVIAR` y más de un intento, enviar el primero en blanco daba las soluciones para aprobar el segundo.

## Decisión

1. DOCENTE no lee esos cuatro objetos: corrige con la instantánea del intento.
2. El intento propio no existe en el espacio docente (`404`) y nadie marca su propia asistencia (`403`). Se sigue permitiendo
   enseñar y cursar la misma edición: es raro, pero bloquearlo no aporta si nadie puede acreditarse a sí mismo.
3. `AL_ENVIAR` muestra las soluciones al aprobar o al agotar los intentos.

## Consecuencias

- `correct` por pregunta se sigue mostrando al enviar: con varios intentos permite descartar opciones. Se acepta, porque ya es
  retroalimentación, y `NUNCA` no cambia eso; una evaluación que lo quiera evitar usa un solo intento.
- Quien prepare material de un curso necesita el rol CREADOR, no DOCENTE.
