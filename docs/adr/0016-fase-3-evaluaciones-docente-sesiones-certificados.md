# ADR-0016: Fase 3 — evaluaciones con instantánea, docente por asignación, sesiones y certificados

**Estado**: aceptada · 2026-10-28

## Contexto

La Fase 3 ([roadmap](../product/roadmap.md#f3--evaluaciones-y-operación-docente)) pide un curso corto completo de punta
a punta: evaluaciones, entregas y rúbricas, sesiones y asistencia, vistas del docente, finalización y certificado. Además
recibe de F2 el alcance del docente (RF-ROL-04) y la navegación por permisos (RNF-UX-06). Hechos que condicionan:

- Un `RecordReadScope` limita lo que una persona lee; `RecordQuery` acepta `ids` y `criteria` con valores ligados.
- `requiresReason` hace obligatorio un motivo en cada escritura de un objeto y lo guarda en la auditoría (ADR-041 de
  Wasichai); el historial de un registro está en `/api/objects/{o}/records/{id}/history`.
- `wasichai-notifications` publica desde el código de la app, dentro de su transacción, a personas concretas, con correo
  opcional (`spring-boot-starter-mail`). `wasichai-documents` emite documentos congelados pero no genera PDF en el
  servidor.
- `AuthenticatedUser` trae el correo de la persona, no su nombre; `/api/users` es solo de administración.
- Wasichai no tiene tipo `JSON`: una instantánea estructurada no cabe en un Custom Object.

## Decisión

1. **Docente por asignación, no por rol.** `docente_edicion` gana `usuario_id` (la cuenta del docente asignado). Sus
   ediciones son las de sus asignaciones. `EnrollmentReadScope` deja leer `matricula` a su estudiante **o** al docente de
   su edición, y `progreso_leccion`, `entrega`, `asistencia` y `sesion` siguen la misma regla. Sin `VER_TODAS` para
   nadie sobre datos de estudiantes (ADR-0014). La matrícula guarda el **correo** del estudiante al crearse
   (`estudiante_correo`), lo mínimo para que su docente lo reconozca.
2. **Evaluaciones como contenido, intentos como hechos.** `evaluacion` (una por lección: intentos máximos, tiempo
   límite, orden aleatorio, nota mínima, peso, cuándo se muestran las soluciones), `pregunta` (opción única, múltiple,
   verdadero/falso, respuesta corta, abierta con rúbrica, archivo), `opcion` y `criterio_rubrica` son Custom Objects del
   temario: la guarda editorial los congela con su versión. **El estudiante no tiene `READ` sobre ellos** (llevan las
   soluciones). Cada intento vive en la tabla fija `lms.intento` con la **instantánea** de la evaluación al empezar
   (`jsonb`, soluciones incluidas, nunca expuestas mientras está abierto), las respuestas y la puntuación; el servidor
   impone intentos y plazo. Las calificaciones manuales van a `lms.calificacion`, solo inserción, con autor, motivo
   (obligatorio al cambiar una nota ya puesta) y momento: es el historial auditable de RF-EVA-06.
3. **Entregas.** `entrega` (Custom Object con archivo de `wasichai-files`): la crea el estudiante para su matrícula
   activa y una pregunta de tipo archivo; congelada al enviarse el intento. Un laboratorio con entrega es una lección
   con su evaluación de una pregunta de archivo y rúbrica.
4. **Cómo se completa cada lección**, calculado y no guardado: una lección con evaluación, al aprobar un intento
   (`APROBADA`); una con sesión enlazada, al asistir (presente o tarde); el resto como en F2. `completion` del contrato
   gana `ASSESSMENT` y `ATTENDANCE`; `NOT_YET` queda para una evaluación o sesión aún sin definir.
5. **Sesiones.** `sesion` (edición, lección opcional, tipo, modalidad, inicio, duración, enlace o ubicación, estado,
   sesión original para la recuperación), `requiresReason`. Las series se crean por comando con un patrón (diario,
   interdiario, días de la semana) y una **hora local en la zona de la edición**, que se mantiene al cruzar el horario
   de verano. Reprogramar y cancelar son comandos con motivo que avisan a los matriculados de esa edición
   (`wasichai-notifications`, correo con Mailpit en desarrollo). El acceso a la reunión pasa por un puerto
   `MeetingProvider`; el único adaptador es el enlace que pone el docente.
6. **Asistencia** (`asistencia`, `requiresReason`): la registra el docente de la edición; no toca el progreso de
   materiales, y viceversa.
7. **Finalización y certificado.** La versión declara sus reglas (todas las lecciones completables, evaluaciones
   aprobadas, asistencia mínima en %); una función pura las evalúa y cita cada regla. El estudiante pide su
   certificado: si cumple, la matrícula pasa a `FINALIZADA`/`APROBADO` y se escribe `lms.certificado` con un código de
   verificación aleatorio y una **instantánea** de lo certificado; si no, `409` con las reglas pendientes. La
   verificación es pública (`GET /api/lms/public/certificates/{code}`) y la interfaz imprime el certificado.
8. **API.** Aula: `/api/lms/classroom/**` (intentos, entregas, sesiones, certificado). Docente:
   `/api/lms/teaching/**` (sus ediciones, matriculados con progreso, cola de corrección, sesiones y asistencia). Contrato
   en [docs/api/lms.md](../api/lms.md).

## Alternativas consideradas

- **Preguntas legibles por el estudiante y ocultar las soluciones con permisos de campo.** Rechazada: un campo oculto
  aún se filtra por la API genérica y depende de no olvidar ninguno; la instantánea servida por la API propia no las
  incluye por construcción.
- **Intentos como Custom Object.** Rechazada: sin tipo `JSON`, la instantánea y las respuestas no caben (ADR-0004).
- **Certificado con `wasichai-documents`.** Diferida: sin PDF en el servidor no aporta frente a una página de impresión
  propia, y la verificación pública necesita igualmente su tabla. Se reconsidera con el PDF de F4.
- **Proveedor de videoconferencia concreto.** Fuera de alcance: el puerto permite añadirlo sin tocar el núcleo.

## Consecuencias

- El docente ve y corrige solo lo de sus ediciones; un docente de otra edición recibe `404` o `403`.
- Lo que el estudiante hizo queda en tablas propias (intentos, calificaciones, certificados) con su historia; los
  registros de Wasichai siguen siendo el temario y la operación.
- Nuevas dependencias justificadas: `wasichai-spring-boot-starter-notifications` y `spring-boot-starter-mail` (avisos de
  RF-SES-03/RF-AUL-09); Mailpit en Compose (RNF-OPS-03).
