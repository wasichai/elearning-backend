# ADR-0022: Límites y ciclo de vida de los trabajos de la cola

**Estado**: aceptada · 2026-10-31 · precisa [ADR-0018](0018-fase-4-estudio-revision-e-importador.md) §4, §6 y §7

## Contexto

La revisión de seguridad del cierre de la Fase 4 ([#216](https://github.com/wasichai/elearning-backend/issues/216)) encontró
que la cola `lms.trabajo` y sus dos usuarios, el importador y la generación, cumplen lo que dice ADR-0018 pero dejan huecos:

- **M2**: importar y generar se encolan sin clave ni tope. Cada clic es un trabajo y, al importar, un borrador más; cada
  importación gasta hasta 300 lecturas de la API de GitHub con el token del servidor y cada generación, hasta 2 × 2 min de
  proceso.
- **L1**: si la réplica muere entre el commit del borrador importado y el cierre del trabajo, el reintento crea otro.
- **L7**: los trabajos corren como la plataforma y los permisos de quien los pidió solo se miran al encolar.
- **L2**: la generación reintenta fallos que no cambian (tipo no generable, hash distinto, falta del binario, tiempo
  límite agotado).
- **L3**: el HTML generado no se sanea, aunque `docs/security/README.md` §5.2 dice que el HTML de las lecciones se sanea
  con lista blanca antes de guardarse. En modo `secure`, `++++` y `pass:[]` dejan pasar el HTML y el `<script>` del autor.
- **L5**: los trabajos terminados se guardan para siempre, con el id de quien los pidió y errores que pueden citar la fuente.

## Decisión

1. **La clave vale mientras el trabajo no termina.** La unicidad de `(organización, tipo, clave)` pasa a ser un índice
   parcial sobre los trabajos pendientes o en curso. Encolar con la clave de uno de ellos devuelve ese trabajo; terminado,
   la misma clave hace otro, porque generar otra vez es, a propósito, una versión nueva. La importación usa como clave
   persona, curso y fuente; la generación, persona y fuente.
2. **Tope de trabajos activos por persona.** `lms.trabajo.solicitante` guarda quién lo pidió. Con
   `lms.jobs.max-active-per-requester` (5) trabajos pendientes o en curso, uno más es `429` con `Retry-After`
   (`RetryLaterException` de Wasichai). Lo que ya está pedido (misma clave) no cuenta otra vez. Es un tope blando: dos
   peticiones simultáneas pueden pasarlo por uno.
3. **El borrador importado y el cierre del trabajo van en la misma transacción.** El manejador cierra su trabajo dentro de
   la transacción del borrador; si ya no es dueño del reclamo, todo se deshace. Cerrar dos veces el mismo intento con el
   mismo resultado es inocuo, así que la cola no distingue quién lo cerró.
4. **Al ejecutar, se vuelve a mirar a quien pidió.** Antes de escribir, el trabajo comprueba como plataforma que la persona
   sigue habilitada en la organización y que sus roles guardados (no los del token con el que encoló) conservan los
   permisos que se exigieron al encolar, con `UserDirectory`, `RoleQueries` y `CurrentUser.hasPermission` de Wasichai.
   Si no, el trabajo termina `FALLIDO` sin escribir nada.
5. **Fallo permanente y fallo pasajero.** Un manejador señala con `PermanentJobFailure` lo que reintentar no arregla y la
   cola lo deja `FAILED` en ese intento. En la generación es permanente lo que depende de la fuente o del despliegue: tipo
   no generable, fuente sin archivo o con hash distinto, falta del binario, salida vacía o demasiado grande, fallo de
   Asciidoctor y tiempo límite agotado. Lo demás (almacenamiento, interrupción) se reintenta como hasta ahora.
6. **El HTML generado se sanea con lista blanca antes de guardarse**, como pide §5.2. Se usa jsoup (`Safelist`): ni
   Wasichai ni el JDK traen un saneador de HTML, y escribir uno a mano con expresiones regulares es justo lo que no se debe
   hacer. Se conserva el cuerpo con sus clases y anclas internas y el estilo que pone Asciidoctor en la cabecera; se quitan
   scripts, manejadores de eventos, `iframe`, formularios, enlaces a hojas externas y URL que no sean `http`, `https`,
   `mailto` o anclas. El aislamiento de §4.4 (descarga como adjunto con `nosniff`, `iframe` con `sandbox=""`) se mantiene:
   el saneado es una capa más, no la única.
7. **Los trabajos terminados se purgan.** La propia cola borra los `DONE` y `FAILED` cuyo `terminado_en` es más antiguo que
   `lms.jobs.retention` (30 días), en una pasada periódica de cada réplica que ejecuta trabajos. Su trazabilidad no se
   pierde: la versión y los artefactos guardan autor, fuente y `trabajo_id`, y `audit_log` guarda `import:<id>`.

## Alternativas consideradas

- **`Idempotency-Key` de Wasichai (ADR-058) en los dos `POST`.** Protege del reintento de red de un mismo cliente, no del
  segundo clic ni de otra pestaña, que mandan otra clave. Una clave derivada de lo que se pide cubre los tres casos.
- **Orden justo por organización al reclamar.** Hoy hay una organización por despliegue; el tope por persona acota a
  quien abusa. Se revisa con la operación de la Fase 6 si se comparte la cola entre organizaciones.
- **Marcar la versión con el trabajo que la creó (L1).** Necesita un campo en `version_curso` solo para eso; la transacción
  común no necesita nada en el modelo.
- **Decidir que el HTML de recursos solo se sirve aislado y corregir §5.2 (L3).** El aislamiento depende de cómo lo sirva
  cada cliente; el saneado viaja con el archivo.

## Consecuencias

- Pedir dos veces lo mismo mientras espera responde `202` con el mismo trabajo; el cliente no tiene que evitarlo.
- Quitar el rol a alguien detiene sus trabajos pendientes en cuanto se ejecutan, con un error que lo dice.
- El HTML generado puede perder lo que el autor metió a mano con `++++` o `pass:[]` fuera de la lista blanca.
- Un trabajo terminado se consulta durante `lms.jobs.retention`; después es `404`, como uno que no existe.
- Se implementa en un PR por hallazgo, en este orden: M2, L1, L7, L2, L3 y L5.
