# Importación real de SRTM y SGSPE (criterio de F4)

Parte del cierre de la Fase 4 ([#179](https://github.com/wasichai/elearning-backend/issues/179)): «importación de un capítulo
de SRTM y uno de SGSPE con informe y trazas `repositorio@sha:ruta#ancla`» ([roadmap](../product/roadmap.md#f4--estudio-editorial)).
`ImportApiTest` prueba lo mismo con un origen simulado; esto es la ejecución contra los manuales reales, por la API de
contenidos de GitHub.

## Cómo se ejecutó (2026-11-08)

- Backend en local (`bootRun`, perfil `dev`), con solo esos dos commits permitidos y el token por entorno, nunca en un archivo:

  ```bash
  LMS_IMPORTER_GITHUB_TOKEN=$(gh auth token) \
  LMS_IMPORTER_ALLOWED_SOURCES=wasichai/srtm-backend@1225b4f5501b4e0526b3824976299c9372691e17,wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c \
  ./gradlew bootRun --args='--spring.profiles.active=dev'
  ```

- Como `creador@demo.wasichai-learning.test`: un curso nuevo por manual (`POST /api/objects/curso/records`), después
  `POST /api/lms/studio/import` con la fuente y `GET /api/lms/studio/import/{id}` hasta `HECHO`. Cada importación dejó una
  versión `BORRADOR` sin publicar y sin el texto del manual (ADR-0018 §6).

## `SRTM-ACCESO`: `wasichai/srtm-backend@1225b4f5501b4e0526b3824976299c9372691e17:docs/manuales/comun/acceso.adoc`

`HECHO` en 2026-11-08T17:08:20.852078Z → 2026-11-08T17:08:26.248112Z. Informe: 1 archivo, 0 inclusiones, 5 secciones (la del capítulo y 4 de nivel 2), 0 imágenes → 1 unidad y 4 lecciones; 6 problemas (`GAP` 3, `CHECK` 3).

| Gravedad | Tipo                   | Línea | Detalle                              |
| -------- | ---------------------- | ----- | ------------------------------------ |
| `GAP`    | `UNRESOLVED_ATTRIBUTE` | 49    | `{capturas}/comun/ingreso.png`       |
| `GAP`    | `UNRESOLVED_ATTRIBUTE` | 53    | `{capturas}/comun/ingreso-error.png` |
| `GAP`    | `UNRESOLVED_ATTRIBUTE` | 86    | `{capturas}/comun/menu-sesion.png`   |
| `CHECK`  | `BROKEN_REFERENCE`     | 9     | `permisos-usuario`                   |
| `CHECK`  | `BROKEN_REFERENCE`     | 36    | `fig-inicio`                         |
| `CHECK`  | `BROKEN_REFERENCE`     | 81    | `fichas-abiertas`                    |

Temario con su traza (`sourceRef`, en `referencia_fuente`):

- **Acceso al sistema**: `wasichai/srtm-backend@1225b4f5501b4e0526b3824976299c9372691e17:docs/manuales/comun/acceso.adoc#acceso`
  - Qué necesita: `wasichai/srtm-backend@1225b4f5501b4e0526b3824976299c9372691e17:docs/manuales/comun/acceso.adoc#requisitos-acceso`
  - Ingresar: `wasichai/srtm-backend@1225b4f5501b4e0526b3824976299c9372691e17:docs/manuales/comun/acceso.adoc#ingresar`
  - Duración de la sesión: `wasichai/srtm-backend@1225b4f5501b4e0526b3824976299c9372691e17:docs/manuales/comun/acceso.adoc#sesion`
  - Cerrar la sesión: `wasichai/srtm-backend@1225b4f5501b4e0526b3824976299c9372691e17:docs/manuales/comun/acceso.adoc#cerrar-sesion`

## `SGSPE-USUARIO`: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/manual-usuario.adoc`

`HECHO` en 2026-11-08T17:08:30.281863Z → 2026-11-08T17:09:04.753281Z. Informe: 12 archivos, 11 inclusiones, 70 secciones, 46 imágenes → 12 unidades y 45 lecciones; 0 problemas (`GAP` 0, `CHECK` 0).

Temario con su traza (`sourceRef`, en `referencia_fuente`):

- **Antes de empezar**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/01-presentacion.adoc#usr-presentacion`
  - Para qué sirve SGSPE: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/01-presentacion.adoc#para-qué-sirve-sgspe`
  - Público y alcance: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/01-presentacion.adoc#público-y-alcance`
  - Qué necesita para entrar: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/01-presentacion.adoc#qué-necesita-para-entrar`
  - Convenciones de este manual: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/01-presentacion.adoc#convenciones-de-este-manual`
- **Primeros pasos**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#usr-primeros-pasos`
  - Iniciar sesión: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#usr-login`
  - Cerrar sesión y sesión vencida: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#cerrar-sesión-y-sesión-vencida`
  - Recorrido por la interfaz: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#recorrido-por-la-interfaz`
  - Trabajar dentro de un proyecto: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#usr-proyecto-360`
  - Listas, vistas, filtros y páginas: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#usr-listas`
  - Fichas de registro: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#usr-fichas`
  - Cambiar el estado de un registro: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#usr-transiciones`
  - Mensajes del sistema: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/02-primeros-pasos.adoc#usr-mensajes`
- **Proyectos y territorio**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/03-proyectos-territorio.adoc#usr-proyectos`
  - Consultar los proyectos: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/03-proyectos-territorio.adoc#consultar-los-proyectos`
  - El resumen del proyecto: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/03-proyectos-territorio.adoc#el-resumen-del-proyecto`
  - Mapas: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/03-proyectos-territorio.adoc#usr-mapas`
  - Fichas de localidad y comunidad: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/03-proyectos-territorio.adoc#fichas-de-localidad-y-comunidad`
  - Áreas de influencia: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/03-proyectos-territorio.adoc#usr-areas`
  - Importar datos geográficos: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/03-proyectos-territorio.adoc#usr-importar`
  - Exportar una capa: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/03-proyectos-territorio.adoc#usr-exportar-capa`
- **Grupos de interés y relacionamiento**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/04-grupos-relacionamiento.adoc#usr-grupos`
  - Consultar los grupos de interés: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/04-grupos-relacionamiento.adoc#consultar-los-grupos-de-interés`
  - La ficha del grupo de interés: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/04-grupos-relacionamiento.adoc#la-ficha-del-grupo-de-interés`
  - Interacciones: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/04-grupos-relacionamiento.adoc#usr-relacionamiento`
- **Compromisos y acuerdos**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/05-compromisos.adoc#usr-compromisos`
  - Consultar compromisos: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/05-compromisos.adoc#consultar-compromisos`
  - Registrar un compromiso: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/05-compromisos.adoc#registrar-un-compromiso`
  - La ficha del compromiso: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/05-compromisos.adoc#la-ficha-del-compromiso`
  - Candidatos a compromiso: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/05-compromisos.adoc#candidatos-a-compromiso`
  - Acuerdos y convenios: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/05-compromisos.adoc#acuerdos-y-convenios`
- **Quejas, consultas y solicitudes (QCS)**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/06-qcs.adoc#usr-qcs`
  - La bandeja de QCS: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/06-qcs.adoc#la-bandeja-de-qcs`
  - Registrar una queja, consulta o solicitud: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/06-qcs.adoc#usr-registrar-qcs`
  - Atender una queja: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/06-qcs.adoc#atender-una-queja`
  - Responder una consulta y decidir una solicitud: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/06-qcs.adoc#responder-una-consulta-y-decidir-una-solicitud`
- **Riesgos, alertas e incidentes**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/07-riesgos-inversion.adoc#usr-riesgos`
  - Registro y matriz de riesgos: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/07-riesgos-inversion.adoc#registro-y-matriz-de-riesgos`
  - La ficha del riesgo y su revisión: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/07-riesgos-inversion.adoc#la-ficha-del-riesgo-y-su-revisión`
  - Alertas tempranas e incidentes: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/07-riesgos-inversion.adoc#alertas-tempranas-e-incidentes`
- **Programas sociales y aportes**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/07-riesgos-inversion.adoc#usr-inversion`
  - Registrar y entregar un aporte: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/07-riesgos-inversion.adoc#registrar-y-entregar-un-aporte`
- **Documentos, tareas, avisos y reportes**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/08-documentos-tareas-reportes.adoc#usr-documentos`
  - Evidencias: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/08-documentos-tareas-reportes.adoc#evidencias`
  - Tareas: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/08-documentos-tareas-reportes.adoc#tareas`
  - Aprobaciones: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/08-documentos-tareas-reportes.adoc#usr-aprobaciones`
  - Avisos: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/08-documentos-tareas-reportes.adoc#avisos`
  - Tableros e indicadores: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/08-documentos-tareas-reportes.adoc#usr-tableros`
  - Reportes y exportaciones: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/08-documentos-tareas-reportes.adoc#usr-reportes`
- **Roles y diferencias de acceso**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/09-roles-problemas.adoc#usr-roles`
  - Roles y diferencias de acceso: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/09-roles-problemas.adoc#usr-roles`
- **Problemas frecuentes y preguntas**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/09-roles-problemas.adoc#usr-problemas`
  - Errores frecuentes: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/09-roles-problemas.adoc#errores-frecuentes`
  - Preguntas frecuentes: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/09-roles-problemas.adoc#preguntas-frecuentes`
- **Glosario esencial**: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/manual-usuario.adoc#usr-glosario`
  - Glosario esencial: `wasichai/sgspe@c957a1a2168b30de3c0583ca9bbc8ed1e0a2ab5c:docs/manuals/user/manual-usuario.adoc#usr-glosario`

## Lectura

- **SRTM** se importó como capítulo suelto (`acceso.adoc`, sin su maestro). Los problemas son los que eso supone y el
  informe los dice bien: `{capturas}` lo define el maestro, así que las tres capturas quedan `GAP` (`UNRESOLVED_ATTRIBUTE`),
  y las tres referencias cruzadas apuntan a otros capítulos (`CHECK`, `BROKEN_REFERENCE`). El temario no pierde lecciones:
  una unidad y una lección por sección de nivel 2.
- **SGSPE** se importó desde su maestro (`manual-usuario.adoc`), con `02-primeros-pasos.adoc` entre sus capítulos: los 11
  `include::` y las 46 imágenes resuelven y el informe sale limpio.
- Cada unidad y cada lección lleva su traza `repositorio@sha:ruta#ancla`, con la ruta del capítulo incluido (no la del
  maestro) y el ancla de su sección.
