# ADR-0037: Términos de uso con plazo y supresión de los datos personales

**Estado**: aceptada · 2026-11-03 · implementa [ADR-0030](0030-lgpl-definitiva-y-conservacion-segun-los-terminos.md) §2
(RNF-SEG-11, [#304](https://github.com/wasichai/elearning-backend/issues/304))

## Contexto

ADR-0030 fijó que los datos personales se conservan el tiempo que la persona aprueba en los términos de uso y que, al
retirar la aceptación o vencer su plazo, se suprimen y lo que deba conservarse se disocia. Faltaba decidir dónde viven
los términos y la aceptación, cómo se cierra el uso sin aceptación, qué es «suprimir» y «disociar» para cada dato que el
LMS guarda hoy y cómo se hace sin tocar lo que es de Wasichai.

## Decisión

1. **Tablas fijas `lms.terminos_uso` y `lms.aceptacion_terminos`** (V15), no Custom Objects (ADR-0004). Una versión de los
   términos es inmutable (disparador, como `lms.certificado`) y publicar otra no toca ninguna aceptación. La aceptación
   no es un registro que nadie edite: la pone y la retira la persona por su comando, la consulta la matrícula en cada
   petición y la recorre un barrido de todas las organizaciones por su vencimiento. Guarda lo mínimo: persona, versión,
   cuándo, hasta cuándo (`vence_en`, el plazo de esa versión contado desde la aceptación) y si la retiró. Una por persona
   y organización: aceptar otra vez la renueva con la versión vigente y su plazo.
2. **Publica quien administra la organización** (`MANAGE_ORGANIZATION`, de Wasichai): `POST /api/lms/terms` con el texto
   y el plazo en días (1 a 36 500). Cualquiera con sesión lee los vigentes y su aceptación y acepta la versión vigente,
   nombrándola (si cambió mientras la leía, `409`).
3. **Sin aceptación vigente no hay matrícula ni progreso**: `EnrollmentService` y `LessonProgressService` preguntan a
   `TermsService.requireAccepted` antes que nada y responden `403` con el motivo. Lo demás del aula (intentos, entregas,
   certificado) cuelga de una matrícula activa, que la supresión retira. El catálogo público no trata datos de nadie y
   sigue abierto. **Sin términos publicados nadie se matricula**: la puerta falla cerrada, y la semilla de demostración
   los publica y los acepta como el estudiante demo. Una organización que se actualiza debe publicar sus términos antes
   de que su gente siga matriculándose.
4. **La supresión es un trabajo de la cola** (`privacy.erase`, clave la persona) en una sola transacción, como la
   plataforma (`job:privacy-erase` en la auditoría). Lo encola retirar la aceptación (`DELETE /api/lms/terms/acceptance`,
   `202`) y un barrido cada `lms.privacy.sweep-every` (1 h) encola las vencidas y las retiradas cuya supresión no terminó.
   Mientras tanto la aceptación retirada o vencida no vale y no se renueva (`409` con `Retry-After`). Es idempotente: lo
   suprimido ya no está y lo disociado ya no lleva el id, así que repetirla no encuentra nada. Deja una constancia por
   trabajo en `lms.supresion` (motivo y cuántos registros de cada clase se suprimieron o disociaron), sin la persona ni su
   seudónimo.
5. **Qué se suprime y qué se disocia**. Se suprime lo que solo sirve a la persona; se disocia lo que la organización
   emitió y debe poder sostener (una nota, un certificado), con un seudónimo aleatorio por supresión que no se guarda:

   | Dato                                 | Destino                                                                                             |
   | ------------------------------------ | --------------------------------------------------------------------------------------------------- |
   | `lms.evento_aprendizaje` (ADR-0020)  | Se suprime.                                                                                         |
   | `progreso_leccion`, `asistencia`     | Se suprimen.                                                                                        |
   | `entrega`                            | Se suprime; el archivo lo borra la limpieza de huérfanos de wasichai-files.                         |
   | `lms.intento` sin calificar          | Se suprime, con sus notas parciales (`lms.calificacion`).                                           |
   | `lms.intento` calificado             | Se disocia: seudónimo y sin respuestas; quedan la instantánea, el resultado y sus notas.            |
   | `matricula`                          | Se disocia: seudónimo, sin correo; la activa pasa a `RETIRADA`. Queda su resultado.                 |
   | `lms.certificado`                    | Se disocia: seudónimo y `holderName` nulo. El código sigue verificando el certificado impreso.      |
   | `perfil_docente`                     | Se vacía (nombre «Perfil suprimido», sin título, biografía ni especialidades) y deja de publicarse. |
   | `lms.trabajo` que pidió              | Se suprimen.                                                                                        |
   | `lms.aceptacion_terminos`            | Se suprime al final.                                                                                |
   | Autoría y actos en nombre de la org. | Se conservan con el id de cuenta: ver abajo.                                                        |

   Lo que la persona hizo **para la organización** se conserva con su id de cuenta, que no es un dato que el LMS describa
   (nombre y correo son de Wasichai): autoría de versiones y recursos, comentarios y decisiones de revisión, notas que puso
   (`lms.calificacion.autor_id`), sesiones y asistencias que registró, sus asignaciones (`docente_edicion`). Son el
   registro de quién decidió qué, que la organización debe poder rendir.

6. **Lo de Wasichai sigue siendo de Wasichai**. La cuenta (nombre, correo, roles) no se borra aquí: deshabilitarla o
   eliminarla es del administrador con Wasichai. `audit_log` guarda el antes de cada registro suprimido o disociado hasta
   que su rol de purga (`wasichai.audit.purge-role`) lo borre; la aplicación nunca lo purga. Las notificaciones enviadas
   también son suyas.
7. **Los disparadores de solo inserción ceden únicamente dentro de la supresión**: V15 los reescribe para dejar pasar el
   borrado (eventos, intentos, notas) y la disociación del certificado cuando la transacción declara
   `set_config('lms.supresion', 'on', true)`. Siguen protegiendo de errores, no de quien ya escribe SQL arbitrario.

## Alternativas

- **Términos y aceptación como Custom Objects**: la aceptación sería editable por la API genérica salvo con guardas y
  alcances nuevos, y el barrido por vencimiento cruzaría organizaciones sin índice propio. Descartada.
- **Borrar la matrícula y el certificado**: rompe la verificación de un certificado ya entregado y los números de la
  edición (cupo, resultados). Disociarlos conserva lo emitido sin la persona.
- **Puerta abierta mientras no haya términos**: no rompe a quien actualiza, pero deja tratar datos sin plazo aceptado,
  justo lo que ADR-0030 evita. Descartada; la semilla publica unos términos ficticios.
- **Seudónimo estable por persona**: permitiría volver a enlazar lo disociado. Se usa uno aleatorio y no se guarda.

## Consecuencias

- RNF-SEG-11 queda implementado para lo que el LMS guarda. `PersonalDataErasureApiTest` suprime a una estudiante de la
  demo con de todo (certificado, intentos, eventos, progreso, perfil) y comprueba lo suprimido, lo disociado, la
  constancia y que repetirla no cambia nada; `TermsApiTest`, la puerta (`403`) y que publicar no altera aceptaciones.
- La interfaz necesita leer y aceptar los términos antes de la matrícula y ofrecer retirarlos (elearning-frontend).
- El trabajo de supresión lleva el id de la persona en su clave y sus datos hasta que la cola lo borra (`lms.jobs.retention`,
  30 días, ADR-0022), más corto que cualquier plazo aceptado.
- Una capacidad nueva que guarde datos personales (valoraciones #268, IA de F5) añade su paso a `PersonalDataErasure` y a
  su prueba, y su destino a `docs/security/README.md` §7; cambiar la regla de esta tabla exige un ADR que reemplace este.
