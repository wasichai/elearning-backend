# wasichai-learning

Plataforma e-learning open source construida sobre [Wasichai](https://github.com/wasichai/wasichai): catálogo de
cursos, ediciones con calendario, aula del estudiante, evaluaciones, estudio editorial con revisión humana y una
canalización de preparación de contenido asistida por IA que propone pero no aprueba.

Este repositorio es el **backend** y la casa de la documentación. La interfaz está en
[elearning-frontend](https://github.com/wasichai/elearning-frontend).

> **Estado**: Fases 1 y 2 completadas (2026-10-28) — catálogo público y aula del estudiante de punta a punta. Siguiente: Fase 3, evaluaciones y operación docente. Lo que existe y lo que falta está en
> [docs/product/roadmap.md](docs/product/roadmap.md) y en [docs/HISTORY.md](docs/HISTORY.md).
>
> **Licencia**: [LGPL-3.0-or-later](LICENSE), provisional ([ADR-0015](docs/adr/0015-licencia-lgpl-provisional.md)). Wasichai, del
> que depende, aún no tiene licencia: ver [docs/legal/licensing.md](docs/legal/licensing.md).

## Arquitectura en una línea

Monolito modular Kotlin + Spring Boot 4.1 WebFlux que consume Wasichai 1.8.0 como librerías: las entidades del LMS son
Custom Objects de Wasichai (`model/model.json`) y las reglas propias del dominio viven en paquetes por capacidad bajo
`elearning.*`. Detalle en [docs/architecture/overview.md](docs/architecture/overview.md).

## Puesta en marcha

Requisitos: JDK 25, Docker, Python 3, Node 26 + yarn 1 (solo herramientas), y un token de GitHub con `read:packages`
(los paquetes de Wasichai solo están en GitHub Packages). Pasos completos en
[docs/development/getting-started.md](docs/development/getting-started.md).

```bash
cp .env.example .env
docker compose up -d
export GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token)
./gradlew bootRun --args='--spring.profiles.active=dev'
```

## Documentación

| Documento                                                                          | Para qué                                                     |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| [docs/research/reference-repositories.md](docs/research/reference-repositories.md) | Qué hay en Wasichai y en los manuales SRTM/SGSPE, verificado |
| [docs/architecture/](docs/architecture/overview.md)                                | Límites, capacidades y modelo de dominio                     |
| [docs/adr/](docs/adr/README.md)                                                    | Decisiones de arquitectura                                   |
| [docs/product/](docs/product/requirements.md)                                      | Requisitos, criterios de aceptación y roadmap                |
| [docs/security/](docs/security/README.md)                                          | Autenticación, autorización, amenazas y controles            |
| [docs/ai/](docs/ai/README.md)                                                      | Agentes, trazabilidad, costes y revisión humana              |
| [docs/development/](docs/development/getting-started.md)                           | Entorno de desarrollo                                        |
| [docs/operations/](docs/operations/README.md)                                      | Configuración, copias de seguridad, diagnóstico              |
| [docs/testing/](docs/testing/README.md)                                            | Estrategia y comandos de prueba                              |

## Contribuir

[CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md)
