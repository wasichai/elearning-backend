# Decisiones de arquitectura (ADR)

Una decisión por archivo, numerada en orden: `NNNN-titulo-en-kebab.md`. Cada ADR tiene una línea de estado y las
secciones Contexto, Decisión, Alternativas consideradas y Consecuencias. El formato sigue el de
[Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/README.md).

- **Un ADR aceptado no se edita para cambiar la decisión.** Un ADR nuevo la modifica o la reemplaza y lo dice en su
  línea de estado; el anterior pasa a «reemplazada por ADR-00NN». Corregir una errata o un enlace no es cambiar la
  decisión.
- **Estados:** _propuesta_ (en discusión), _aceptada_ (rige), _reemplazada por ADR-00NN_.
- **Decisiones de plataforma.** Las toma Wasichai y aquí no se repiten: se citan como «ADR-025 de Wasichai», con enlace
  a `wasichai/docs/adr/`. Cada ADR de este repositorio explica solo lo propio de wasichai-learning.
- **Hechos.** Lo que un ADR afirma de Wasichai, de los manuales o del entorno está verificado en
  [`docs/research/reference-repositories.md`](../research/reference-repositories.md); lo no verificado se marca
  «a verificar en implementación».

| ADR                                                                           | Decisión                                                                                       | Estado                       | Fecha      |
| ----------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | ---------------------------- | ---------- |
| [0001](0001-proceso-de-ingenieria-sin-superpowers.md)                         | El proceso de ingeniería no depende de Superpowers                                             | aceptada                     | 2026-10-27 |
| [0002](0002-wasichai-desde-github-packages.md)                                | Wasichai se consume como librerías desde GitHub Packages                                       | aceptada                     | 2026-10-27 |
| [0003](0003-monolito-modular-y-modulos-ui-por-experiencia.md)                 | Monolito modular: backend por capacidad, frontend por experiencia                              | aceptada                     | 2026-10-27 |
| [0004](0004-modelo-hibrido-custom-objects-y-tablas-fijas.md)                  | Modelo híbrido: Custom Objects y tablas fijas solo si hace falta                               | aceptada                     | 2026-10-27 |
| [0005](0005-lms-en-el-registro-de-wasichai-ui.md)                             | Todo el LMS se registra como módulos de `<WasichaiApp>`                                        | aceptada                     | 2026-10-27 |
| [0006](0006-catalogo-publico-con-cadena-de-seguridad-acotada.md)              | Catálogo público con cadena de seguridad acotada                                               | aceptada (lectura: ver 0011) | 2026-10-27 |
| [0007](0007-carga-academica-y-creditos-como-politica-versionada.md)           | Carga y créditos como política versionada que propone                                          | aceptada                     | 2026-10-27 |
| [0008](0008-openapi-con-springdoc.md)                                         | La API propia se documenta con OpenAPI vía springdoc 3.x                                       | aceptada                     | 2026-10-27 |
| [0009](0009-licencia-diferida.md)                                             | La licencia del proyecto se difiere                                                            | reemplazada por 0015         | 2026-10-27 |
| [0010](0010-importacion-asciidoc-por-etapas.md)                               | La importación AsciiDoc avanza por etapas y nunca publica sola                                 | aceptada                     | 2026-10-27 |
| [0011](0011-catalogo-publico-leido-de-una-proyeccion.md)                      | El catálogo público se lee de una proyección                                                   | aceptada                     | 2026-10-27 |
| [0012](0012-aula-del-estudiante.md)                                           | El aula lee por matrícula, aísla el contenido y exige evidencia                                | aceptada                     | 2026-10-28 |
| [0013](0013-estructura-publicada-visible-recursos-por-matricula.md)           | El estudiante ve lo publicado; los recursos, solo matriculado                                  | aceptada                     | 2026-10-28 |
| [0014](0014-docente-sin-ver-todas-y-recurso-congelado.md)                     | Sin «ver todas» del docente sobre matrículas y progreso; recurso congelado                     | aceptada                     | 2026-10-28 |
| [0015](0015-licencia-lgpl-provisional.md)                                     | Licencia provisional LGPL-3.0-or-later                                                         | aceptada                     | 2026-10-28 |
| [0016](0016-fase-3-evaluaciones-docente-sesiones-certificados.md)             | Fase 3: evaluaciones con instantánea, docente por asignación, sesiones y certificados          | aceptada                     | 2026-10-28 |
| [0017](0017-copia-del-contenido-de-evaluaciones.md)                           | Los intentos leen una copia del contenido de las evaluaciones                                  | aceptada                     | 2026-10-31 |
| [0018](0018-fase-4-estudio-revision-e-importador.md)                          | Fase 4: estudio, revisión con decisiones, importador y generación en cola propia               | aceptada (cola: ver 0022)    | 2026-10-31 |
| [0019](0019-docente-sin-claves-y-soluciones-al-terminar.md)                   | El docente no lee las claves, nadie se acredita a sí mismo y las soluciones llegan al terminar | aceptada                     | 2026-10-31 |
| [0020](0020-decisiones-de-las-revisiones-de-las-fases-2-y-3.md)               | Lo que las revisiones de las Fases 2 y 3 dejaron como está, y cuánto se guardan los eventos    | aceptada                     | 2026-10-31 |
| [0021](0021-la-revision-de-un-curso-se-decide-en-la-bandeja.md)               | La revisión de un curso se decide solo en la bandeja; la de un recurso, con motivo             | aceptada                     | 2026-10-31 |
| [0022](0022-limites-y-ciclo-de-vida-de-los-trabajos.md)                       | Límites y ciclo de vida de los trabajos de la cola                                             | aceptada                     | 2026-10-31 |
| [0023](0023-cupo-agotado-en-la-proyeccion-del-catalogo.md)                    | El cupo agotado llega al catálogo como un sí o un no, por la proyección                        | aceptada                     | 2026-11-01 |
| [0024](0024-prerrequisitos-como-campo-de-la-version.md)                       | Los prerrequisitos son un campo de la versión y los comprueba el comando de matrícula          | aceptada                     | 2026-11-01 |
| [0025](0025-alcances-de-lectura-con-subconsultas-sobre-tablas-de-wasichai.md) | Los alcances de lectura se resuelven con subconsultas sobre las tablas de Wasichai             | aceptada (provisional)       | 2026-11-01 |
| [0027](0027-laboratorio-en-la-leccion-y-entornos-por-matricula.md)            | Laboratorio en campos de la lección; sus entornos, un objeto que solo leen los matriculados    | aceptada                     | 2026-11-01 |
