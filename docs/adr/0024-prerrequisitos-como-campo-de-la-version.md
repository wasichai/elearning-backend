# ADR-0024: Los prerrequisitos son un campo de la versión y los comprueba el comando de matrícula

**Estado**: aceptada · 2026-11-01 · amplía [ADR-0012](0012-aula-del-estudiante.md) y
[ADR-0013](0013-estructura-publicada-visible-recursos-por-matricula.md)

## Contexto

RF-ACA-08 pide prerrequisitos declarados por versión y comprobados al matricular, con un rechazo que diga el motivo. El
comando de matrícula (`EnrollmentService`) lee todo como el estudiante, así que lo que consulte tiene que poder leerlo él
sin ver de más. `curso.conocimientos_previos` ya existe, pero es texto libre para la ficha: no se puede comprobar.

## Decisión

1. **Un campo de `version_curso`, `cursos_previos` (`LONG_TEXT`)**, con los códigos de los cursos exigidos, uno por
   línea. Al ser de la versión, se congela con ella al publicarse (`EditorialWorkflowGuard`), se copia a la versión
   siguiente con el resto de sus valores (`NewVersionService`) y lo lee quien lee la versión, con el alcance de
   `ContentReadScope`: nadie ve los de un borrador ajeno. Todos los declarados son obligatorios; lo recomendable sigue
   siendo `conocimientos_previos`.
2. **Se exige el curso, no una versión concreta**: cuenta una matrícula propia `FINALIZADA` con resultado `APROBADO` en
   cualquier versión del curso. Las versiones sucesivas de un curso son el mismo curso corregido.
3. **La regla está en el comando**, dentro de su transacción, después del estado y la ventana de la edición y antes del
   cupo. Las matrículas se leen como el estudiante (`EnrollmentReadScope`: solo las suyas) y `matricula` es `apiOnly`, así
   que nadie se da un aprobado por la API genérica. Un código que no corresponde a ningún curso no se cumple nunca
   (falla cerrada) y el `409` lo nombra, para que se corrija en una versión nueva.

## Alternativas consideradas

- **Un Custom Object `prerrequisito_version`** (versión → curso exigido). Rechazada por ahora: daría integridad
  referencial, pero necesita su permiso de lectura para el estudiante, su alcance (hoy cualquiera con `READ` leería los
  de un borrador, y `ContentReadScope` no lo cubre), su lugar en la guarda editorial y en la copia de versiones. Todo eso
  ya lo tiene un campo de la versión. Si se pide un prerrequisito opcional, por versión exacta o con nota mínima, se
  reemplaza este ADR por ese objeto.
- **Exigir una versión concreta.** Rechazada: un curso que publica su v2 dejaría sin acceso a quien aprobó la v1.

## Consecuencias

- Un código mal escrito impide matricularse en esa versión hasta que se publique otra; el motivo del `409` lo hace
  visible. No hay guarda que lo valide al escribir: un borrador puede nombrar un curso que aún no existe.
- La ficha pública aún no lista los prerrequisitos; si hace falta, es un campo opcional más del catálogo.
