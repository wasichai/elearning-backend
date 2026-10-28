# Historial de cambios

Lo más nuevo primero. El razonamiento de arquitectura vive en `docs/adr/`; este archivo registra lo que se entregó.

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
