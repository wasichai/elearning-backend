# Historial de cambios

Lo más nuevo primero. El razonamiento de arquitectura vive en `docs/adr/`; este archivo registra lo que se entregó.

## 2026-10-28 — Fase 2: el aula del estudiante

Criterio de la Fase 2 cumplido ([roadmap](product/roadmap.md)): un estudiante inicia sesión, se matricula desde la ficha,
recorre su curso por unidades, abre lecturas, PDF y vídeos, y conserva su progreso en el servidor. Épicas E12–E16
([#78](https://github.com/wasichai/elearning-backend/issues/78) … [#90](https://github.com/wasichai/elearning-backend/issues/90));
diseño en [ADR-0012](adr/0012-aula-del-estudiante.md) y [ADR-0013](adr/0013-estructura-publicada-visible-recursos-por-matricula.md). Los
requisitos F2 que aún no cumplen su aceptación se siguen en [#103](https://github.com/wasichai/elearning-backend/issues/103).

- **Matrícula** (`matricula`, `apiOnly`) solo por el comando `POST /api/lms/enrollments`, como la persona: ventana en la
  zona de la edición, versión publicada, cupo en `lms.ocupacion_edicion` bloqueado con `FOR UPDATE` en la misma
  transacción (probado con una carrera determinista que falla sin el cerrojo), idempotente.
- **Alcance por matrícula, no por rol**: el estudiante lee sus matrículas y su progreso; la estructura de lo publicado
  (ADR-0013, porque una relación escrita debe apuntar a algo legible); los recursos y sus archivos, solo de lo que cursa.
- **Recursos versionados** (`recurso`, `version_recurso`, `recurso_leccion`) con su archivo en `wasichai-files` (local en
  desarrollo, S3 por configuración), el mismo flujo editorial que el curso e inmutables con su archivo al publicarse.
- **Progreso con evidencia** (`progreso_leccion`, `apiOnly`): solo avanza; lectura, presentación y laboratorio al
  marcarlas, vídeo al terminar, evaluación y sesión en F3; eventos crudos en `lms.evento_aprendizaje`, solo inserción
  (disparadores) e idempotentes por `clientEventId`, serializados por lección con un cerrojo consultivo.
- **API del aula**: mis cursos y el aula de una matrícula propia (404 también para un docente), con progreso por unidad.
- **Demo del aula**: lectura HTML propia, guía PDF y vídeo sintéticos, una lección de vídeo, el estudiante demo
  matriculado por el comando y fechas relativas al día de aplicación (#96); `apply.py` sube archivos y matricula.
- **Revisión de seguridad** del cierre (E16.3): sin IDOR ni saltos de la guarda; se corrigieron dos hallazgos medios
  ([ADR-0014](adr/0014-docente-sin-ver-todas-y-recurso-congelado.md)): el docente ya no ve matrículas ni progreso de
  todos (hasta el alcance por asignación de F3) y un recurso que salió del borrador no cambia de título ni de tipo. El
  resto queda registrado: coste del alcance por petición y otros bajos en
  [#104](https://github.com/wasichai/elearning-backend/issues/104); endurecimiento de blobs y del HTML en
  [elearning-frontend#34](https://github.com/wasichai/elearning-frontend/issues/34).
- **Frontend**: `classroomModule` («Mis cursos», aula por unidades con HTML en `iframe` con `sandbox=""`, progreso,
  matrícula desde la ficha) y recorridos e2e contra el backend real en CI (trabajo `e2e-real`).

REST change: nuevo `POST /api/lms/enrollments`, `GET /api/lms/classroom/enrollments`,
`GET /api/lms/classroom/enrollments/{id}` y `POST /api/lms/classroom/enrollments/{id}/lessons/{lessonId}/events` (con
sesión). Contrato en [docs/api/lms.md](api/lms.md).
New tests: backend 133 (antes 93), `model/` 75 (antes 58), frontend 146 unitarias (antes 68), 22 e2e con API simulada
y 8 contra el backend real (5 del catálogo, 3 del aula), también en CI.

## 2026-10-28 — Primer incremento: catálogo público de punta a punta

Fase 1 completa ([roadmap](product/roadmap.md)): un visitante anónimo busca, filtra y ordena los cursos publicados y abre
su ficha con temario, ediciones en su zona horaria, docentes, carga formativa y créditos **propuestos**. Épicas E2–E10
([#11](https://github.com/wasichai/elearning-backend/issues/11) … [#33](https://github.com/wasichai/elearning-backend/issues/33)),
un PR por paso en este repositorio y en [elearning-frontend](https://github.com/wasichai/elearning-frontend).

- **Plataforma**: aplicación Spring Boot 4.1.1 WebFlux sobre Wasichai 1.6.0 (GitHub Packages), PostgreSQL 18 en Compose,
  prueba de arquitectura (sin JPA, sin internos de Wasichai, sin literales de rol, DAG de capacidades).
- **Modelo** como Custom Objects (`model/model.json`, 8 objetos) aplicado por `model/apply.py`, idempotente y contrastado
  con el servidor real; roles ESTUDIANTE, DOCENTE, CREADOR y REVISOR; datos demo A (SRTM), B (SGSPE) y C (IA aplicada),
  marcados como ilustrativos y con referencias verificadas a los manuales.
- **Carga y créditos**: `workload` (dominio puro) con políticas ECTS (25–30 h) y SCH (34 CFR 600.2); siempre propuesta.
- **Catálogo**: cadena de seguridad pública acotada (ADR-0006) y proyección `lms.catalogo_publicado` mantenida en segundo
  plano (ADR-0011, porque `RecordService.asPlatform` lanza dentro de una petición); contrato OpenAPI con springdoc 3.2.0.
- **Flujo editorial** (`EditorialWorkflowGuard`): transiciones con su acción, sin autoaprobación, contenido y temario
  solo en borrador y por su autor. Lo destapó la revisión de seguridad: antes, un CREADOR podía publicar por la API de
  registros.
- **Frontend**: `catalogModule` registrado en `<WasichaiApp>` (rutas públicas), Zod para el contrato, es/en, e2e con
  Playwright (API simulada y humo contra el backend real), CI en verde en ambos repositorios.

REST change: nuevo `GET /api/lms/public/catalog/courses`, `GET /api/lms/public/catalog/courses/{slug}` y
`GET /api/lms/public/openapi` (anónimos, solo lectura). Contrato en [docs/api/lms.md](api/lms.md).
New tests: backend 93 (unitarias, arquitectura e integración con Testcontainers), `model/` 58, frontend 68 unitarias,
10 e2e y 5 de humo contra el backend real.

## 2026-10-27 — Fase 0: investigación y gobierno del repositorio

Arranque del proyecto sobre Wasichai 1.6.0 ([épica E0](https://github.com/wasichai/elearning-backend/issues/1),
[épica E1](https://github.com/wasichai/elearning-backend/issues/4)). Wrapper de Gradle 9.8.2 con sumas verificadas;
análisis verificado de los repositorios de referencia (`docs/research/reference-repositories.md`); reglas del proyecto
(`CLAUDE.md`), `.editorconfig`, commitlint, `.env.example`, política de seguridad y de contribución. Cambio REST:
ninguno. Pruebas nuevas: ninguna (aún no hay código).
