# Requisitos

Requisitos funcionales (RF) y no funcionales (RNF) de wasichai-learning, derivados de la especificación del usuario
(las referencias «§N» remiten a sus secciones) y de las decisiones de [`docs/adr/`](../adr/README.md). El cómo está en
[`docs/architecture/overview.md`](../architecture/overview.md) y [`docs/architecture/domain-model.md`](../architecture/domain-model.md);
el cuándo, en [`roadmap.md`](roadmap.md).

## Convenciones

- **Identificador estable** `RF-<ÁREA>-NN` / `RNF-<ÁREA>-NN`. No se renumera ni se reutiliza: un requisito que se
  abandona se elimina en un PR que lo explica, y su identificador queda libre para siempre.
- **Fase objetivo** F1…F6, según [`roadmap.md`](roadmap.md). F1 incluye el primer incremento (catálogo público).
- **Estado**: `planificado` hasta que su criterio de aceptación se verifica **ejecutando** una prueba o una comprobación
  reproducible en un PR fusionado; entonces pasa a `implementado` citando ese PR. Escribir código o documentación no
  basta.
- «A verificar» marca un detalle de Wasichai o de terceros que se confirmará al implementar, leyendo su código.
- A fecha de 2026-10-28, F1 y F2 están cumplidas. Los 16 requisitos F2 que no cumplían su aceptación se
  repartieron entre F3, F4 y F6 (decisión de la dirección, #103); cada uno conserva su «Pendiente de F2».

## Requisitos funcionales

### RF-CAT — Catálogo público (§6.1)

- **RF-CAT-01** · F1 · implementado ([#55](https://github.com/wasichai/elearning-backend/pull/55)) — Un visitante anónimo lista los cursos con versión publicada de la organización
  configurada (`lms.public.organization`).
  _Aceptación:_ `GET /api/lms/public/catalog/courses` sin credenciales responde 200 y, en una prueba de integración con
  un curso publicado y otro en borrador, solo devuelve el publicado.
- **RF-CAT-02** · F1 · implementado ([#55](https://github.com/wasichai/elearning-backend/pull/55), [#63](https://github.com/wasichai/elearning-backend/pull/63)) — Búsqueda por texto sobre título y resumen.
  _Aceptación:_ prueba de integración donde `q` reduce el resultado a los cursos que contienen el término, sin
  distinguir mayúsculas.
- **RF-CAT-03** · F1 · implementado ([#55](https://github.com/wasichai/elearning-backend/pull/55)) — Filtros por categoría, nivel, modalidad y fecha de inicio, combinables.
  _Aceptación:_ una prueba por filtro y una de combinación (intersección); un valor de enumeración desconocido devuelve
  `400` con `ProblemDetail`.
- **RF-CAT-04** · F1 · implementado ([#55](https://github.com/wasichai/elearning-backend/pull/55), [#63](https://github.com/wasichai/elearning-backend/pull/63)) — Orden configurable y paginación con tamaño máximo.
  _Aceptación:_ pruebas de cada criterio de orden; un tamaño de página por encima del máximo no devuelve más elementos
  que el máximo (rechazo o recorte, la decisión se documenta en el contrato OpenAPI).
- **RF-CAT-05** · F1 · implementado ([#56](https://github.com/wasichai/elearning-backend/pull/56), [#16 web](https://github.com/wasichai/elearning-frontend/pull/16)) — Ficha de curso por `slug` con objetivos, requisitos previos, temario por unidades,
  ediciones (fechas en la zona horaria de la edición), docentes, carga por tipo de hora y créditos propuestos.
  _Aceptación:_ prueba de integración que comprueba esos campos; la UI rotula los créditos como «propuestos, no
  oficiales».
- **RF-CAT-06** · F1 · implementado ([#56](https://github.com/wasichai/elearning-backend/pull/56)) — Un curso no publicado es indistinguible de uno inexistente.
  _Aceptación:_ `GET …/courses/{slug}` de un borrador y de un slug inventado devuelven el mismo `404`.
- **RF-CAT-07** · F1 · implementado ([#56](https://github.com/wasichai/elearning-backend/pull/56), [#64](https://github.com/wasichai/elearning-backend/pull/64)) — El catálogo solo expone campos públicos (DTO propio, no el registro de Wasichai).
  _Aceptación:_ prueba de contrato que falla si la respuesta incluye identificadores de autor, estado editorial,
  comentarios de revisión u otros campos internos.
- **RF-CAT-08** · F6 · planificado — Filtros adicionales: duración, conocimientos previos, estado de matrícula y horas
  por tipo (teoría, práctica/laboratorio, autónomo).
  _Aceptación:_ pruebas de integración por filtro y control de la UI con estado vacío.
  _Pendiente de F2 (#103):_ faltan los filtros nuevos.
- **RF-CAT-09** · F3 · planificado — La ficha describe evaluación y requisitos de certificación, y el estado de
  matrícula de cada edición (abierta, cerrada, completa).
  _Aceptación:_ prueba de integración con ediciones en cada estado.

### RF-ACA — Gestión académica: cursos, versiones, ediciones y matrículas (§5, §6.2, §16)

- **RF-ACA-01** · F1 · implementado ([#42](https://github.com/wasichai/elearning-backend/pull/42)) — `CourseDefinition` y `CourseVersion` son entidades distintas; una versión tiene
  estado `BORRADOR`, `EN_REVISION`, `APROBADA`, `PUBLICADA` o `RETIRADA`.
  _Aceptación:_ `model/apply.py --validate-only` acepta el modelo; prueba de integración que crea dos versiones de un
  curso y publica solo una.
- **RF-ACA-02** · F1 · implementado ([#42](https://github.com/wasichai/elearning-backend/pull/42), [#56](https://github.com/wasichai/elearning-backend/pull/56)) — `CourseOffering` (edición) con inicio, fin, zona horaria IANA, capacidad,
  ventana de matrícula y docentes asignados.
  _Aceptación:_ el modelo aplicado contiene esos campos y la ficha del catálogo los muestra (RF-CAT-05).
- **RF-ACA-03** · F2 · implementado ([#64](https://github.com/wasichai/elearning-backend/pull/64)) — Una versión `PUBLICADA` es inmutable.
  _Aceptación:_ prueba de integración: modificar o borrar una versión publicada se rechaza con `ProblemDetail`
  (`RecordWriteGuard`); los cambios exigen una versión nueva.
- **RF-ACA-04** · F2 · implementado ([#42](https://github.com/wasichai/elearning-backend/pull/42), [#64](https://github.com/wasichai/elearning-backend/pull/64)) — Un creador autorizado crea cursos y versiones en borrador; sin el permiso, no.
  _Aceptación:_ prueba con un usuario CREADOR (éxito) y uno ESTUDIANTE (`403`).
- **RF-ACA-05** · F3 · planificado (PR abierto: [#144](https://github.com/wasichai/elearning-backend/pull/144)) — Duración de 1 a 8 semanas o semestral, y modalidad presencial, virtual síncrona,
  asíncrona o híbrida.
  _Aceptación:_ validación del modelo con casos límite (0 semanas rechazado; 1, 8 y semestral aceptados).
  _Pendiente de F2 (#103):_ el modelo no tiene modalidad presencial ni duración semestral, y no valida de 1 a 8 semanas.
- **RF-ACA-06** · F2 · implementado ([#94](https://github.com/wasichai/elearning-backend/pull/94), [#97](https://github.com/wasichai/elearning-backend/pull/97)) — Matrícula (`Enrollment`) de un estudiante en una edición identificable, única
  e idempotente.
  _Aceptación:_ repetir la orden con la misma `Idempotency-Key` no crea un duplicado; matricularse dos veces en la
  misma edición se rechaza.
  _Nota:_ la idempotencia va por la clave natural (edición, estudiante): repetir devuelve la misma matrícula
  (`200`), sin duplicado, con o sin `Idempotency-Key` (`docs/api/lms.md`).
- **RF-ACA-07** · F2 · implementado ([#97](https://github.com/wasichai/elearning-backend/pull/97)) — La matrícula respeta capacidad y ventana de inscripción.
  _Aceptación:_ pruebas fuera de ventana y con cupo agotado, incluida una prueba concurrente que no sobrepasa la
  capacidad.
- **RF-ACA-08** · F3 · planificado — Prerrequisitos declarados por versión, comprobados al matricular.
  _Aceptación:_ prueba de rechazo con motivo explícito cuando falta un prerrequisito obligatorio.
- **RF-ACA-09** · F3 · planificado — Reglas de finalización explícitas por versión (lecciones requeridas,
  evaluaciones aprobadas, asistencia mínima).
  _Aceptación:_ pruebas unitarias del cálculo con cada regla y su combinación; el resultado cita la regla aplicada.
- **RF-ACA-10** · F6 · planificado — Lista de espera como extensión de la matrícula.
  _Aceptación:_ al liberarse una plaza, el primero en espera recibe la oferta; prueba de orden y de caducidad.

### RF-CRE — Carga académica y créditos (§6.3)

- **RF-CRE-01** · F1 · implementado ([#42](https://github.com/wasichai/elearning-backend/pull/42)) — La versión registra semanas y horas semanales de teoría, práctica/laboratorio,
  otras actividades supervisadas y estudio autónomo.
  _Aceptación:_ validación que rechaza horas negativas y semanas < 1.
- **RF-CRE-02** · F1 · implementado ([#49](https://github.com/wasichai/elearning-backend/pull/49)) — Cálculo de horas de contacto, autónomas y carga total.
  _Aceptación:_ prueba unitaria del caso de la especificación: 6 semanas × (12 teoría + 12 laboratorio + 12 autónomo)
  = 144 h de contacto, 72 h autónomas, 216 h totales.
- **RF-CRE-03** · F1 · implementado ([#50](https://github.com/wasichai/elearning-backend/pull/50)) — Política ECTS parametrizable (horas por crédito dentro de 25–30) que produce una
  propuesta con sistema, método, parámetros, fuente y versión de la política.
  _Aceptación:_ con 27 h/crédito el caso anterior propone 8 ECTS; la regla de redondeo es un parámetro probado.
- **RF-CRE-04** · F1 · implementado ([#50](https://github.com/wasichai/elearning-backend/pull/50), [#63](https://github.com/wasichai/elearning-backend/pull/63)) — Política de _semester credit hours_ según 34 CFR 600.2, con el tratamiento del
  laboratorio y otras actividades como parámetro de la institución, no como constante.
  _Aceptación:_ pruebas unitarias con los parámetros documentados y la fuente citada en la propuesta.
- **RF-CRE-05** · F1 · implementado ([#50](https://github.com/wasichai/elearning-backend/pull/50), [#64](https://github.com/wasichai/elearning-backend/pull/64)) — El sistema calcula y **propone**; nunca marca créditos como aprobados.
  _Aceptación:_ el dominio no expone ninguna operación que produzca un crédito aprobado; prueba que lo comprueba.
- **RF-CRE-06** · F1 · implementado ([#50](https://github.com/wasichai/elearning-backend/pull/50)) — Sin conversión entre ECTS y SCH.
  _Aceptación:_ no existe función de conversión; cada propuesta lleva un único sistema; la UI no muestra
  equivalencias.
- **RF-CRE-07** · F6 · planificado — Políticas de crédito configurables por programa, institución y jurisdicción, y
  versionadas.
  _Aceptación:_ cambiar una política crea una versión nueva y las propuestas anteriores conservan la suya.
- **RF-CRE-08** · F6 · planificado — Aprobación humana de créditos por una persona autorizada, auditada.
  _Aceptación:_ sin el permiso → `403`; la aprobación queda en la auditoría con la política y versión usadas.

### RF-AUL — Aula del estudiante y progreso (§6.4)

- **RF-AUL-01** · F2 · implementado ([#101](https://github.com/wasichai/elearning-backend/pull/101), [front#28](https://github.com/wasichai/elearning-frontend/pull/28)) — Panel con cursos matriculados y próximos.
  _Aceptación:_ prueba de UI y de integración: un estudiante ve solo sus matrículas.
- **RF-AUL-02** · F3 · planificado — Curso por unidades con navegación lateral persistente, lectura amplia y siguiente
  actividad evidente.
  _Aceptación:_ prueba E2E que recorre una unidad completa usando solo la acción «siguiente».
  _Pendiente de F2 (#103):_ la navegación existe (front#29); falta el e2e que recorre una unidad entera solo con «siguiente».
- **RF-AUL-03** · F3 · planificado — Lecciones de lectura HTML, PDF, vídeo y presentación, con material descargable.
  _Aceptación:_ prueba E2E que abre una lección de cada tipo de la semilla de demostración.
  _Pendiente de F2 (#103):_ HTML, PDF y vídeo verificados contra el backend real (front#33); falta una presentación en la demo.
- **RF-AUL-04** · F3 · planificado — Estados de progreso definidos: iniciada, completada, aprobada. Una lectura se
  completa por acción explícita; un vídeo, por eventos reales del reproductor con umbral parametrizable. El tiempo
  con la página abierta no cuenta.
  _Aceptación:_ pruebas unitarias de cada transición; abrir una lectura sin marcarla no la completa.
  _Pendiente de F2 (#103):_ estados y evidencias por tipo verificados (#100); falta el umbral parametrizable del vídeo (hoy, fin de reproducción).
- **RF-AUL-05** · F3 · planificado — Progreso persistido en servidor, ligado a la matrícula y a la versión del curso.
  _Aceptación:_ prueba E2E: tras cerrar sesión y entrar desde otro navegador, «continuar» lleva a la última lección.
  _Pendiente de F2 (#103):_ persistencia verificada al recargar (front#33); falta el e2e en otro navegador con «continuar».
- **RF-AUL-06** · F2 · implementado ([#99](https://github.com/wasichai/elearning-backend/pull/99), [#100](https://github.com/wasichai/elearning-backend/pull/100)) — Eventos de aprendizaje registrados de forma idempotente para progreso e informes.
  _Aceptación:_ reenviar un evento con el mismo identificador no altera el progreso.
- **RF-AUL-07** · F2 · implementado ([#101](https://github.com/wasichai/elearning-backend/pull/101), [front#30](https://github.com/wasichai/elearning-frontend/pull/30)) — Progreso general y por unidad visible al estudiante.
  _Aceptación:_ prueba de UI con progreso parcial y completo.
- **RF-AUL-08** · F3 · planificado — El aula muestra próximas sesiones, plazos, evaluaciones, calificaciones,
  intentos, retroalimentación, logros y certificados.
  _Aceptación:_ prueba E2E con los datos de demostración.
- **RF-AUL-09** · F3 · planificado — Notificaciones de publicación de contenido y de cambios de calendario.
  _Aceptación:_ reprogramar una sesión notifica a los matriculados de esa edición y a nadie más (prueba con el canal de
  correo local).

### RF-SES — Sesiones síncronas y asistencia (§6.5)

- **RF-SES-01** · F3 · planificado — `TeachingSession` con fecha y hora en la zona de la edición, duración, docente,
  tipo, modalidad y enlace o ubicación.
  _Aceptación:_ prueba de integración de creación y lectura por el estudiante matriculado.
- **RF-SES-02** · F3 · planificado — Sesiones recurrentes por patrón (diario, interdiario, días configurables),
  correctas ante cambios de horario de verano.
  _Aceptación:_ prueba unitaria con una zona con horario de verano que cruza la transición: la hora local se mantiene.
- **RF-SES-03** · F3 · planificado — Reprogramación, cancelación y sesión de recuperación con historial.
  _Aceptación:_ la sesión original conserva su historial y los matriculados reciben aviso (RF-AUL-09).
- **RF-SES-04** · F3 · planificado — Acceso a la reunión mediante un adaptador de proveedor de videoconferencia; el
  adaptador por defecto es un enlace introducido por el docente.
  _Aceptación:_ prueba de arquitectura: el núcleo no depende de ningún proveedor concreto.
- **RF-SES-05** · F3 · planificado — Asistencia (`AttendanceRecord`) registrada por el docente, separada del progreso
  de materiales.
  _Aceptación:_ marcar asistencia no modifica el progreso de lecciones, y viceversa.

### RF-EVA — Evaluaciones y certificados (§6.6, §16)

- **RF-EVA-01** · F3 · planificado — Tipos de pregunta: opción única, opción múltiple, verdadero/falso, respuesta
  corta, abierta con rúbrica y entrega de archivo.
  _Aceptación:_ una prueba de creación, respuesta y calificación por tipo.
- **RF-EVA-02** · F3 · planificado — Intentos máximos, tiempo límite, orden aleatorio, peso y nota mínima por
  evaluación.
  _Aceptación:_ pruebas de agotar intentos y de envío tras el tiempo límite (rechazado por el servidor).
- **RF-EVA-03** · F3 · planificado — Cada intento opera sobre una instantánea inmutable de la versión de la
  evaluación.
  _Aceptación:_ editar la evaluación con un intento abierto no cambia las preguntas ni la calificación del intento.
- **RF-EVA-04** · F3 · planificado — Las respuestas correctas no se exponen antes de lo que permita la política de la
  evaluación.
  _Aceptación:_ prueba de contrato de la API del intento abierto: no contiene soluciones.
- **RF-EVA-05** · F3 · planificado — Corrección automática para tipos cerrados y manual con rúbrica y comentarios para
  abiertas y entregas.
  _Aceptación:_ pruebas de ambos caminos; una abierta queda pendiente hasta la corrección manual.
- **RF-EVA-06** · F3 · planificado — Historial auditable de calificaciones; cambiar una nota exige permiso en el
  ámbito y motivo.
  _Aceptación:_ un docente de otra edición recibe `403`; el cambio autorizado queda en la auditoría con el motivo.
- **RF-EVA-07** · F3 · planificado — El resultado de una prueba es distinto de la aprobación del curso.
  _Aceptación:_ aprobar una evaluación no aprueba el curso si quedan reglas de RF-ACA-09 sin cumplir.
- **RF-EVA-08** · F3 · planificado — Certificado emitido solo al cumplir requisitos explícitos, congelado al emitirse
  y verificable.
  _Aceptación:_ sin requisitos cumplidos → rechazo; el certificado emitido no cambia si después cambian los datos.

### RF-LAB — Laboratorios (§6.7)

- **RF-LAB-01** · F3 · planificado — Laboratorio con objetivo, requisitos, guía, archivos, hitos, criterios de
  aceptación, entregables, rúbrica y enlaces a entornos externos.
  _Aceptación:_ el modelo lo admite y el aula lo muestra (prueba de UI).
- **RF-LAB-02** · F3 · planificado — Entrega, calificación con rúbrica, resultado y retroalimentación.
  _Aceptación:_ prueba E2E de entrega por el estudiante y corrección por el docente asignado.
- **RF-LAB-03** · F3 · planificado — Los enlaces a entornos se validan (esquema `https`, sin ejecución en el
  servidor).
  _Aceptación:_ un enlace `http`, `file:` o a una dirección interna se rechaza (ver RNF-SEG-08).

### RF-ROL — Roles, permisos y alcance (§7)

- **RF-ROL-01** · F1 · implementado ([#42](https://github.com/wasichai/elearning-backend/pull/42), [#52](https://github.com/wasichai/elearning-backend/pull/52)) (alcance por asignación del DOCENTE y del REVISOR: F3/F4) — Roles ESTUDIANTE, DOCENTE, CREADOR y REVISOR declarados en `model/roles.json`,
  junto al ADMIN de Wasichai.
  _Aceptación:_ `model/apply.py` los aplica de forma idempotente; prueba de integración que los lee.
- **RF-ROL-02** · F1 · implementado ([#52](https://github.com/wasichai/elearning-backend/pull/52), [#64](https://github.com/wasichai/elearning-backend/pull/64)) — La autorización se decide en el servidor con los permisos de Wasichai; el código
  no compara nombres de rol.
  _Aceptación:_ la prueba de arquitectura falla ante un literal de rol en `src/main`.
- **RF-ROL-03** · F3 · implementado ([#111](https://github.com/wasichai/elearning-backend/pull/111)) — Un usuario puede tener varios roles a la vez.
  _Aceptación:_ prueba con un usuario ESTUDIANTE + CREADOR que obtiene la unión de permisos.
- **RF-ROL-04** · F3 · planificado — Alcance por recurso: el docente gestiona solo sus ediciones asignadas; el
  revisor, solo las versiones asignadas; el estudiante, solo sus matrículas.
  _Aceptación:_ pruebas de autorización negativa cambiando identificadores (RNF-SEG-03).
  _Pendiente de F2 (#103):_ hecho para el estudiante (sus matrículas, su progreso, su contenido); el docente aún ve todas las ediciones (llega con su espacio, F3).
- **RF-ROL-05** · F3 · implementado ([#135](https://github.com/wasichai/elearning-backend/pull/135)) — Nadie se asigna privilegios a sí mismo; la gestión de roles queda auditada.
  _Aceptación:_ prueba de autoasignación rechazada. Si Wasichai ya lo impide (a verificar), la prueba documenta ese
  comportamiento; si no, se añade una regla propia.
  _Verificado en Wasichai 1.6.0:_ usuarios, sus roles y los permisos de un rol exigen `MANAGE_ORGANIZATION`, que solo
  tiene ADMIN (`apply.py` no deja dar permisos globales a un rol LMS); los cambios quedan en `/api/audit`. Sin regla
  propia: Wasichai no distingue «a sí mismo», pero quien puede asignar roles ya es administrador.
- **RF-ROL-06** · F4 · planificado — Sin autoaprobación: el autor de una versión no puede aprobarla cuando la
  política exige revisión independiente.
  _Aceptación:_ prueba con un usuario CREADOR + REVISOR sobre su propia versión → rechazo.

### RF-CON — Contenido, versiones y flujo editorial (§9, §10.7–10.10)

- **RF-CON-01** · F4 · implementado ([#140](https://github.com/wasichai/elearning-backend/pull/140)) — `LearningResource` versionado y reutilizable con identificador estable, título,
  descripción, idioma, ubicación (curso, unidad, lección), autor, responsable editorial y estado de revisión.
  _Aceptación:_ el modelo aplicado contiene esos campos; un recurso se reutiliza en dos lecciones.
- **RF-CON-02** · F4 · planificado — Los binarios (PDF, vídeo, imágenes) van a almacenamiento de objetos, no a
  PostgreSQL, con hash registrado.
  _Aceptación:_ prueba de integración con almacenamiento S3 compatible local; el hash se comprueba al descargar.
  _Pendiente de F2 (#103):_ los archivos van a `wasichai-files` con su sha256, nunca a PostgreSQL (#98); falta la prueba con S3 local y la comprobación del hash al descargar.
- **RF-CON-03** · F4 · planificado — Fuente canónica (AsciiDoc o LaTeX) separada de los artefactos generados, con
  relación fuente↔producto (versión de la fuente, generador, hash).
  _Aceptación:_ cada artefacto publicado identifica su fuente exacta.
- **RF-CON-04** · F4 · planificado — Publicar crea una versión inmutable; los artefactos publicados nunca se
  sobrescriben.
  _Aceptación:_ publicar v2 no cambia los artefactos de v1 ni el progreso de quien cursa v1.
- **RF-CON-05** · F4 · planificado — Flujo editorial borrador → revisión → aprobación → publicación con bandeja del
  revisor: diferencias, comentarios por sección, incidencias y decisiones (aprobar, pedir cambios, rechazar).
  _Aceptación:_ recorridos E2E 8–11 de la especificación (§20).
- **RF-CON-06** · F4 · planificado — Las correcciones no borran versiones anteriores ni alteran las decisiones del
  revisor.
  _Aceptación:_ tras una corrección, comentarios y decisiones previas siguen ligados a su versión.
- **RF-CON-07** · F4 · planificado — Generación de HTML y PDF desde AsciiDoc como trabajo asíncrono.
  _Aceptación:_ una lección importada produce ambos artefactos validados sin bloquear peticiones HTTP.

### RF-IMP — Importador AsciiDoc (§9; ADR-0010)

- **RF-IMP-01** · F4 · planificado — Solo importa de orígenes permitidos (lista configurada) o paquetes autorizados.
  _Aceptación:_ un origen fuera de la lista se rechaza.
- **RF-IMP-02** · F4 · planificado — Analiza el documento maestro y su grafo `include::` (con `leveloffset`) y extrae
  capítulos, secciones, procedimientos, imágenes, diagramas, tablas y referencias cruzadas.
  _Aceptación:_ pruebas con los manuales de SRTM (profundidad 1) y SGSPE (profundidad 2).
- **RF-IMP-03** · F4 · planificado — Trazabilidad de cada elemento a `repositorio@sha:ruta#ancla`.
  _Aceptación:_ toda unidad propuesta enlaza su origen exacto.
- **RF-IMP-04** · F4 · planificado — Informe de importación con includes, imágenes, enlaces y referencias faltantes
  o rotos.
  _Aceptación:_ una fuente con defectos sembrados los reporta todos.
- **RF-IMP-05** · F4 · planificado — Propone unidades y lecciones que el creador modifica; nunca publica.
  _Aceptación:_ tras importar, todo queda en `BORRADOR`; extracción, diseño pedagógico y aprobación son pasos
  separados.

### RF-IA — Canalización editorial con IA (§10, §11; ver [`docs/ai/README.md`](../ai/README.md))

- **RF-IA-01** · F5 · planificado — Brief estructurado (temática, público, objetivos, duración, idioma, nivel,
  expertos, fuentes autorizadas) validado por esquema.
  _Aceptación:_ un brief incompleto se rechaza con los campos que faltan.
- **RF-IA-02** · F5 · planificado — Pasos reproducibles (análisis de fuentes con procedencia, diseño pedagógico,
  borrador con plantillas versionadas) con salidas tipadas; una salida inválida se rechaza o va a corrección.
  _Aceptación:_ prueba con una salida malformada del doble determinista.
- **RF-IA-03** · F5 · planificado — Cada ejecución (`AgentRun`) registra usuario, ámbito, objetivo, entradas, fuentes,
  modelo y proveedor (sin secretos), versiones de instrucciones y plantillas, resultados, validaciones, estado,
  errores, consumo, duración e intervenciones humanas.
  _Aceptación:_ prueba que comprueba cada campo y la ausencia de claves.
- **RF-IA-04** · F5 · planificado — Trabajos persistentes con reintentos acotados, idempotencia y cancelación.
  _Aceptación:_ reintentar tras un fallo no duplica coste registrado ni artefactos; cancelar detiene el trabajo.
- **RF-IA-05** · F5 · planificado — Validaciones automáticas de estructura, referencias, alineación objetivos-
  evaluación, enlaces, duplicidad, accesibilidad y metadatos antes de la revisión humana.
  _Aceptación:_ un borrador con defectos sembrados no llega a la bandeja sin su informe.
- **RF-IA-06** · F5 · planificado — Los agentes no publican, no ejecutan código ni leen archivos arbitrarios; el texto
  de las fuentes es dato no confiable.
  _Aceptación:_ prueba con una fuente que contiene instrucciones inyectadas: no cambia herramientas, permisos ni
  estado editorial.
- **RF-IA-07** · F5 · planificado — Permisos separados para generar, validar, aprobar y publicar.
  _Aceptación:_ un usuario que solo puede generar recibe `403` al aprobar.
- **RF-IA-08** · F5 · planificado — Embabel se usa en la versión verificada (1.5.3); sin credenciales hay un doble
  determinista claramente identificado; si algo no es compatible se documenta y se entrega una alternativa real.
  _Aceptación:_ la suite de pruebas pasa sin claves de proveedor y la UI marca los resultados del doble.
- **RF-IA-09** · F5 · planificado — Comparación de modelos por tarea con entradas, criterios, versiones e informes
  conservados.
  _Aceptación:_ un informe reproducible por tarea comparada.
- **RF-IA-10** · F5 · planificado — Indicadores de la canalización (§10) calculados desde `AgentRun` y revisiones.
  _Aceptación:_ cada indicador tiene definición escrita y prueba de cálculo; ninguno se presenta como calidad
  pedagógica.

### RF-MED — Multimedia (§12)

- **RF-MED-01** · F5 · planificado — Guion por secciones con duración, diapositivas, subtítulos y transcripción como
  datos versionados.
  _Aceptación:_ validación de esquema del guion y trazas a la fuente.
- **RF-MED-02** · F5 · planificado — Renderizado Remotion asíncrono, fuera de WebFlux, con límite de concurrencia y
  registro de trabajo y errores.
  _Aceptación:_ lanzar más renders que el límite los encola sin degradar la API.
- **RF-MED-03** · F5 · planificado — Un vídeo se da por terminado solo si se renderizó y validó (duración,
  resolución, subtítulos), con previsualización.
  _Aceptación:_ un render fallido o incompleto no se ofrece para publicar.
- **RF-MED-04** · F5 · planificado — Lo publicado funciona sin el proveedor de IA.
  _Aceptación:_ prueba E2E del aula con el proveedor desactivado.

### RF-REP — Perfiles, reputación y feedback (§8)

- **RF-REP-01** · F6 · planificado — Valoración de docentes por estudiantes matriculados en su edición, con
  dimensiones configurables; una por estudiante y edición, editable con registro de cambios.
  _Aceptación:_ un no matriculado o un visitante recibe `403`; un duplicado se rechaza.
- **RF-REP-02** · F6 · planificado — Valoración de recursos por claridad, utilidad, actualidad y calidad.
  _Aceptación:_ mismas reglas de RF-REP-01 aplicadas a recursos.
- **RF-REP-03** · F6 · planificado — Feedback contextual por unidad o lección con estado de resolución.
  _Aceptación:_ el responsable cambia el estado y el autor del feedback lo ve.
- **RF-REP-04** · F6 · planificado — Moderación y privacidad de valoraciones y feedback.
  _Aceptación:_ contenido moderado no visible; la identidad del valorador no se muestra al valorado.
- **RF-REP-05** · F6 · planificado — Indicadores agregados solo con criterio publicado y muestra mínima configurable;
  cantidad o velocidad no se presentan como calidad.
  _Aceptación:_ por debajo de la muestra mínima el indicador no se muestra.
- **RF-REP-06** · F6 · planificado — Perfiles de estudiante, docente, creador y revisor con su historial; un puntaje
  solo si está definido y es explicable.
  _Aceptación:_ cada cifra del perfil enlaza a su definición.

### RF-ADM — Administración, auditoría e informes (§13, §14, §15)

- **RF-ADM-01** · F1 · implementado ([#48](https://github.com/wasichai/elearning-backend/pull/48), [#13 web](https://github.com/wasichai/elearning-frontend/pull/13)) — El nombre del producto es configurable (`lms.product-name`, `VITE_APP_NAME`) y
  no aparece en el dominio.
  _Aceptación:_ prueba de arquitectura sin el literal del nombre en el código de dominio.
- **RF-ADM-02** · F3 · planificado — Usuarios, roles y categorías se administran con las pantallas de Wasichai.
  _Aceptación:_ recorrido manual documentado; no existen pantallas LMS paralelas para ello.
  _Pendiente de F2 (#103):_ falta el recorrido documentado.
- **RF-ADM-03** · F3 · planificado — Auditoría de contenidos, calificaciones, roles y publicaciones con la auditoría
  de Wasichai.
  _Aceptación:_ cada operación sensible deja un registro consultable por ADMIN.
- **RF-ADM-04** · F6 · planificado — Informes de progreso, asistencia y resultados por edición, respetando el
  alcance de quien consulta.
  _Aceptación:_ un docente solo obtiene datos de sus ediciones.
- **RF-ADM-05** · F6 · planificado — Vista de salud de procesos (trabajos asíncronos, fallos, reintentos).
  _Aceptación:_ un trabajo fallido aparece con su error y su número de intentos.

## Requisitos no funcionales

### RNF-ARQ — Arquitectura y stack (stack, §5, §15)

- **RNF-ARQ-01** · F1 · implementado ([#48](https://github.com/wasichai/elearning-backend/pull/48)) — Kotlin 2.4.21, Spring Boot 4.1.1 WebFlux, JDK 25, PostgreSQL 18 y la BOM de
  Wasichai 1.6.0; frontend React 19, Vite 8, Tailwind 4 y `@wasichai/*` 1.3.0.
  _Aceptación:_ `./gradlew build` y `yarn build` pasan con esas versiones fijadas.
- **RNF-ARQ-02** · F1 · implementado ([#53](https://github.com/wasichai/elearning-backend/pull/53)) — Sin JPA, Hibernate, Spring Data ni JDBC bloqueante en flujos reactivos.
  _Aceptación:_ la prueba de arquitectura falla ante esas importaciones en `src/main`.
- **RNF-ARQ-03** · F1 · implementado ([#53](https://github.com/wasichai/elearning-backend/pull/53)) — Monolito modular con paquete raíz `elearning` y paquetes por capacidad sin
  ciclos (ADR-0003).
  _Aceptación:_ prueba de arquitectura del grafo de paquetes.
- **RNF-ARQ-04** · F1 · implementado ([#48](https://github.com/wasichai/elearning-backend/pull/48), [#64](https://github.com/wasichai/elearning-backend/pull/64)) — No se duplican autenticación, autorización, metadatos, formularios, navegación,
  documentos ni permisos que Wasichai ya provee; se extiende por SPI (ADR-0004).
  _Aceptación:_ sin tablas propias de usuarios, roles o permisos; revisión en cada PR.
- **RNF-ARQ-05** · F1 · implementado ([#14 web](https://github.com/wasichai/elearning-frontend/pull/14)) — Toda la UI se registra como `WasichaiModule`; sin segundo router ni segunda
  biblioteca visual (ADR-0005).
  _Aceptación:_ `boundaries.test.ts` por módulo y ausencia de `createBrowserRouter` propio.
- **RNF-ARQ-06** · F1 · implementado ([#57](https://github.com/wasichai/elearning-backend/pull/57)) — API LMS bajo `/api/lms/**` documentada con OpenAPI o, si springdoc no es
  compatible, a mano (ADR-0008).
  _Aceptación:_ el contrato publicado coincide con las pruebas de contrato.
- **RNF-ARQ-07** · F1 · implementado ([#51](https://github.com/wasichai/elearning-backend/pull/51)) — Cada dependencia de infraestructura nueva responde a un requisito concreto y
  queda justificada en un ADR.
  _Aceptación:_ `compose.yaml` del primer incremento solo contiene PostgreSQL.
- **RNF-ARQ-08** · F4 · implementado ([#138](https://github.com/wasichai/elearning-backend/pull/138)) — El núcleo no depende de proveedores concretos de IA, almacenamiento o
  videoconferencia.
  _Aceptación:_ prueba de arquitectura sobre los paquetes de dominio.
- **RNF-ARQ-09** · F4 · planificado — Las tareas bloqueantes o largas (Asciidoctor, PDF, render) corren como
  trabajos persistentes fuera del bucle de eventos.
  _Aceptación:_ prueba de que la API responde mientras un trabajo largo está en curso.

### RNF-SEG — Seguridad, privacidad y auditoría (§13; ver [`docs/security/README.md`](../security/README.md))

- **RNF-SEG-01** · F1 · implementado ([#54](https://github.com/wasichai/elearning-backend/pull/54), [#62](https://github.com/wasichai/elearning-backend/pull/62)) — Todo `/api/lms/**` exige sesión salvo `GET /api/lms/public/**`, servido por una
  cadena de seguridad acotada (ADR-0006).
  _Aceptación:_ prueba anónima a una ruta privada → `401`.
- **RNF-SEG-02** · F1 · implementado ([#54](https://github.com/wasichai/elearning-backend/pull/54)) — Métodos distintos de `GET` en `/api/lms/public/**` se rechazan.
  _Aceptación:_ `POST` anónimo → `401` o `403`, probado.
- **RNF-SEG-03** · F2 · implementado ([#95](https://github.com/wasichai/elearning-backend/pull/95), [#98](https://github.com/wasichai/elearning-backend/pull/98), [#100](https://github.com/wasichai/elearning-backend/pull/100), [#101](https://github.com/wasichai/elearning-backend/pull/101)) — Pruebas de autorización negativa (IDOR) en cada endpoint que recibe un
  identificador.
  _Aceptación:_ cambiar el id por uno ajeno nunca devuelve datos; la respuesta no revela si el recurso existe.
- **RNF-SEG-04** · F1 · implementado ([#56](https://github.com/wasichai/elearning-backend/pull/56)) (catálogo público; la API autenticada la aísla Wasichai por `organization_id`) — Separación de datos por organización.
  _Aceptación:_ prueba con dos organizaciones: ningún dato cruza.
- **RNF-SEG-05** · F1 · implementado ([#38](https://github.com/wasichai/elearning-backend/pull/38), [#69](https://github.com/wasichai/elearning-backend/pull/69)) — Sin secretos en el repositorio; tokens y claves solo desde el entorno.
  _Aceptación:_ `.env.example` sin valores reales; escaneo de secretos en CI (herramienta a decidir en E9).
- **RNF-SEG-06** · F1 · implementado ([#55](https://github.com/wasichai/elearning-backend/pull/55), [#63](https://github.com/wasichai/elearning-backend/pull/63)) — Validación de entradas y límites en parámetros de consulta.
  _Aceptación:_ texto de búsqueda o página fuera de rango → `400` con `ProblemDetail`.
- **RNF-SEG-07** · F4 · planificado — Archivos con límites de tamaño y tipo, nombres saneados y descarga solo para
  quien tiene acceso.
  _Aceptación:_ pruebas de tipo prohibido, tamaño excedido y descarga ajena.
  _Pendiente de F2 (#103):_ tamaño y descarga ajena probados (#98); no hay lista de tipos permitidos (Wasichai deja HTML y vídeo como `octet-stream`; ver #98).
- **RNF-SEG-08** · F4 · planificado — Control de URLs externas contra SSRF (importador, enlaces de laboratorio).
  _Aceptación:_ URLs a direcciones internas o con redirecciones hacia ellas se rechazan.
- **RNF-SEG-09** · F5 · planificado — Claves de proveedores de IA protegidas; nunca en registros ni en `AgentRun`.
  _Aceptación:_ prueba que busca la clave de prueba en registros y persistencia.
- **RNF-SEG-10** · F6 · planificado — Límites de abuso y spam en endpoints públicos y de valoración.
  _Aceptación:_ superar el límite configurado → `429`. Si Wasichai ofrece limitación de tasa está a verificar.
- **RNF-SEG-11** · F6 · planificado — Privacidad por defecto, conservación y eliminación de datos personales.
  _Aceptación:_ política escrita y prueba de eliminación de un usuario de demostración.
- **RNF-SEG-12** · F6 · planificado — Copias de seguridad con restauración verificada.
  _Aceptación:_ ensayo de restauración documentado con fecha y resultado.

### RNF-UX — Experiencia, accesibilidad e idioma (§14)

- **RNF-UX-01** · F1 · implementado ([#15 web](https://github.com/wasichai/elearning-frontend/pull/15)) — Tema y componentes de `@wasichai/ui`; tono sobrio, jerarquía clara y pocas
  acciones principales.
  _Aceptación:_ revisión de capturas de Playwright en cada PR con UI.
- **RNF-UX-02** · F1 · implementado ([#15 web](https://github.com/wasichai/elearning-frontend/pull/15), [#16 web](https://github.com/wasichai/elearning-frontend/pull/16)) — Estados de carga, vacío, error y éxito en cada vista.
  _Aceptación:_ pruebas de componente por estado.
- **RNF-UX-03** · F1 · implementado ([#18 web](https://github.com/wasichai/elearning-frontend/pull/18)) — Responsive en escritorio y móvil.
  _Aceptación:_ E2E en un viewport móvil y uno de escritorio, sin desplazamiento horizontal.
- **RNF-UX-04** · F1 · implementado ([#18 web](https://github.com/wasichai/elearning-frontend/pull/18)) (teclado y roles probados en e2e; el contraste es el del tema de Wasichai y no se ha medido) — Accesible por teclado, con etiquetas y roles; contraste del tema.
  _Aceptación:_ pruebas por rol y etiqueta en Testing Library y recorrido por teclado en E2E; auditoría completa en F6.
- **RNF-UX-05** · F1 · implementado ([#17 web](https://github.com/wasichai/elearning-frontend/pull/17)) — Textos con i18next; español inicial y estructura lista para más idiomas.
  _Aceptación:_ sin textos literales en componentes; claves `es` completas.
- **RNF-UX-06** · F3 · planificado — Experiencias separadas por audiencia (catálogo, aula, docente, estudio,
  revisor, administración), no una pantalla universal.
  _Aceptación:_ un módulo de frontend por experiencia con su navegación filtrada por permisos.
  _Pendiente de F2 (#103):_ catálogo y aula son módulos propios; falta filtrar la navegación por permisos y los módulos de docente, estudio y revisor.
- **RNF-UX-07** · F2 · implementado ([front#16](https://github.com/wasichai/elearning-frontend/pull/16), [front#28](https://github.com/wasichai/elearning-frontend/pull/28)) — Fechas mostradas en la zona de la edición, indicándola.
  _Aceptación:_ prueba de componente con zona distinta a la del navegador.
- **RNF-UX-08** · F3 · planificado — Confirmación en acciones destructivas y errores comprensibles a partir de
  `ProblemDetail`.
  _Aceptación:_ pruebas de componente del diálogo y del mapeo de errores.
  _Pendiente de F2 (#103):_ aún no hay acciones destructivas en las experiencias LMS.

### RNF-OSS — Proyecto abierto y datos de demostración (§18, §19; ver [`docs/legal/licensing.md`](../legal/licensing.md))

- **RNF-OSS-01** · F1 · implementado ([#58](https://github.com/wasichai/elearning-backend/pull/58)) — Instalación local reproducible y documentada en
  [`docs/development/getting-started.md`](../development/getting-started.md).
  _Aceptación:_ una persona sin contexto previo arranca backend, frontend y datos de demostración siguiendo la guía.
- **RNF-OSS-02** · F1 · implementado ([#51](https://github.com/wasichai/elearning-backend/pull/51)) — `.env.example` con todas las variables y sin valores secretos.
  _Aceptación:_ la aplicación arranca en perfil `dev` copiando el ejemplo y aportando solo el token de paquetes.
- **RNF-OSS-03** · F1 · implementado ([#41](https://github.com/wasichai/elearning-backend/pull/41), [#52](https://github.com/wasichai/elearning-backend/pull/52)) — Modelo y migraciones reproducibles e idempotentes.
  _Aceptación:_ aplicar `model/apply.py` dos veces deja el mismo estado; las pruebas de integración lo ejecutan.
- **RNF-OSS-04** · F1 · implementado ([#47](https://github.com/wasichai/elearning-backend/pull/47), [#64](https://github.com/wasichai/elearning-backend/pull/64)) — Datos de demostración sintéticos (cursos A SRTM, B SGSPE, C IA aplicada),
  marcados como ilustrativos, sin datos personales reales y sin copiar el cuerpo de los manuales.
  _Aceptación:_ cada curso de demostración cita sus referencias `repositorio@sha:ruta#ancla`.
- **RNF-OSS-05** · F1 · implementado ([#38](https://github.com/wasichai/elearning-backend/pull/38)) — Política de seguridad y guía de contribución.
  _Aceptación:_ `SECURITY.md` y `CONTRIBUTING.md` presentes y enlazados desde el README.
- **RNF-OSS-06** · F3 · planificado — La semilla de demostración incluye perfiles de todos los roles, matrículas,
  intentos y calificaciones; valoraciones en F6.
  _Aceptación:_ los recorridos E2E de §20 corren sobre esa semilla.
- **RNF-OSS-07** · F6 · planificado — Licencia del proyecto elegida y publicada solo con autorización expresa
  (ADR-0009).
  _Aceptación:_ archivo `LICENSE` fusionado por decisión registrada del titular.

### RNF-OPS — Operación y calidad continua (infraestructura, §20, §21)

- **RNF-OPS-01** · F1 · implementado ([#51](https://github.com/wasichai/elearning-backend/pull/51), [#53](https://github.com/wasichai/elearning-backend/pull/53)) — Entorno local con Docker Compose solo en `127.0.0.1`; ninguna prueba usa el
  puerto 5432.
  _Aceptación:_ `docker compose up` y las pruebas de integración conviven sin conflicto.
- **RNF-OPS-02** · F1 · implementado ([#59](https://github.com/wasichai/elearning-backend/pull/59), [#20 web](https://github.com/wasichai/elearning-frontend/pull/20)) — CI con build, formato, pruebas unitarias, de integración y de arquitectura en
  backend, y lint, tipos, pruebas, build y E2E en frontend.
  _Aceptación:_ un PR con una prueba rota queda en rojo.
- **RNF-OPS-03** · F3 · planificado — Correo configurable, con un buzón local de pruebas en desarrollo.
  _Aceptación:_ las notificaciones de RF-AUL-09 se observan en el buzón local.
- **RNF-OPS-04** · F6 · planificado — Despliegue en Kubernetes/k3s con manifiestos versionados.
  _Aceptación:_ despliegue real en un clúster de prueba con los recorridos E2E pasando contra él.
- **RNF-OPS-05** · F6 · planificado — Objetivos de rendimiento y disponibilidad fijados a partir de mediciones reales,
  nunca supuestos.
  _Aceptación:_ cada objetivo cita la medición y el entorno que lo respaldan.

## Definition of Done (§22)

Un cambio está terminado cuando:

1. Sigue las convenciones del repositorio (`.editorconfig`, ktlint, Conventional Commits).
2. Tiene pruebas del comportamiento nuevo, escritas antes que el código (rojo → verde), incluidas pruebas de
   autorización negativa si toca permisos.
3. Los permisos se comprueban en el servidor; la UI solo oculta.
4. Los errores son explícitos (`ProblemDetail` con tipo y detalle comprensibles).
5. No contiene secretos ni datos personales reales.
6. Cada dependencia nueva está justificada (ADR si es de infraestructura).
7. Las migraciones y el modelo son reproducibles.
8. API y pantallas están alineadas con el contrato.
9. Funciona en escritorio y móvil.
10. La documentación afectada (este archivo incluido: estado del requisito) está al día.
11. Build, formato y pruebas pasan **ejecutándolos de verdad**, en local y en CI; el PR cita los comandos y su
    resultado.
12. No afirma cifras de rendimiento o disponibilidad que no se hayan medido.

## Fuera de alcance

Explícitamente excluido; reabrirlo exige un requisito concreto y un ADR:

- **Pasarela de pago** y cobro: los cursos son gratuitos; un precio futuro se documentará como extensión, sin
  flujos de pago simulados.
- **Sandbox de ejecución de código** en laboratorios. Si llegara, sería una función separada y aislada, con límites,
  diseñada para código no confiable.
- **Videollamada propia**: solo adaptadores a proveedores externos (RF-SES-04).
- **Redis, Kafka, microservicios, motores de búsqueda o bases de datos adicionales** por defecto (RNF-ARQ-07).
- **Conversión entre sistemas de créditos** (ECTS↔SCH) y cualquier presentación de créditos como reconocimiento
  oficial (RF-CRE-05, RF-CRE-06).
- **Publicación automática** de contenido generado o importado: siempre hay aprobación humana (RF-IMP-05, RF-IA-06).
- **Sistemas paralelos** de autenticación, permisos, metadatos, formularios, navegación o documentos (RNF-ARQ-04).
