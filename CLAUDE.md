# wasichai-learning — backend (`elearning-backend`)

Plataforma e-learning open source construida **sobre Wasichai**: este repositorio es una aplicación que consume las
librerías de [wasichai/wasichai](https://github.com/wasichai/wasichai), no una plataforma nueva. La interfaz vive en
[wasichai/elearning-frontend](https://github.com/wasichai/elearning-frontend). Toda la documentación del producto está
aquí, en `docs/`. «wasichai-learning» es un nombre de trabajo: se configura (`LMS_PRODUCT_NAME`) y nunca aparece en el
dominio.

## Antes de tocar nada, leer

| Si vas a…                                | Lee                                                                                            |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------- |
| Entender qué hay debajo                  | [docs/research/reference-repositories.md](docs/research/reference-repositories.md)             |
| Diseñar o mover límites                  | [docs/architecture/overview.md](docs/architecture/overview.md) y los [ADR](docs/adr/README.md) |
| Tocar el modelo                          | [docs/architecture/domain-model.md](docs/architecture/domain-model.md) y `model/model.json`    |
| Permisos, datos personales, archivos, IA | [docs/security/README.md](docs/security/README.md)                                             |
| Saber qué toca ahora                     | [docs/product/roadmap.md](docs/product/roadmap.md) y las épicas de GitHub                      |

## Stack (no negociable sin ADR nuevo)

Kotlin 2.4.21, Spring Boot 4.1.2 **WebFlux**, Gradle 9.8.2 (wrapper, Kotlin DSL, `gradle/libs.versions.toml`), JDK 25,
PostgreSQL 18, Wasichai 1.8.0 (BOM + starters desde GitHub Packages). Python 3 stdlib solo para `model/apply.py`.

## Reglas

1. **Wasichai primero.** Autenticación, autorización, metadatos, formularios, vistas, workflow, archivos, notificaciones,
   documentos, auditoría y tenancy son de Wasichai: se extienden por sus SPI (`RecordWriteGuard`, `RecordReadScope`,
   `ModuleMigration`…) y nunca se reimplementan. No se usan sus clases internas (`RecordStore`, `ObjectSchemaManager`,
   `AuditService`).
2. **Reactivo de verdad.** Prohibidos JPA, Hibernate, Spring Data repositories y JDBC bloqueante en `src/main`. SQL con
   `DatabaseClient` y valores ligados; esquemas por `WasichaiSchemas`. Trabajo largo (render, IA, importación) en la cola
   persistente, nunca en el hilo de WebFlux.
3. **Modelo como metadata.** Las entidades LMS son Custom Objects en `model/model.json`, aplicados por `model/apply.py`.
   Tablas fijas propias (esquema `lms`, `ModuleMigration` orden 200) solo cuando la metadata no alcanza (ADR-0004).
4. **Permisos en el servidor**, con `CurrentUser.requirePermission`. **Nunca** se compara un nombre de rol en código;
   el alcance por recurso (docente de _su_ edición) va en asignaciones + `RecordReadScope`.
5. **Lo publicado no cambia.** Una versión publicada es inmutable; un intento de evaluación guarda su instantánea.
6. **La IA propone, una persona aprueba.** Ningún agente publica, ejecuta código ni lee el sistema de archivos. El texto
   de los documentos es dato no confiable.
7. Paquete raíz `elearning`, por capacidad (`catalog`, `course`, `workload`…); nunca bajo `wasichai`. `ArchitectureTest`
   lo vigila.
8. **Sin sobreingeniería**: una abstracción necesita un segundo usuario concreto; cada dependencia, un requisito.

## Trabajo

- Código y nombres técnicos en inglés; documentos, comentarios y mensajes de commit en español.
- `.editorconfig` es ley (160 columnas). `./gradlew ktlintFormat` y `yarn format` antes de commitear.
- Conventional Commits (commitlint). Una épica de GitHub por tarea, un issue y un PR por paso; el PR cierra su issue.
- TDD: la prueba que falla primero. Ningún test usa el puerto 5432.
- Lo que se entrega va a `docs/HISTORY.md` (lo más nuevo primero); las decisiones, a `docs/adr/` (un ADR se reemplaza con
  otro, no se edita).
- El token de GitHub Packages nunca va a un archivo: `GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token)`.

## Comandos

```bash
docker compose up -d                                   # PostgreSQL 18 en 127.0.0.1:5436
./gradlew build                                        # ktlint + unitarias + arquitectura
./gradlew integrationTest                              # Testcontainers postgres:18 + model/apply.py
./gradlew bootRun --args='--spring.profiles.active=dev'
python3 model/apply.py --validate-only
```

## Definition of Done

Funciona · tiene pruebas del comportamiento nuevo (y de autorización negativa si toca permisos) · maneja errores ·
permisos en el servidor · sin secretos · migraciones reproducibles · API y pantallas alineadas · documentado · build,
pruebas y formato en verde, **ejecutados de verdad**.
