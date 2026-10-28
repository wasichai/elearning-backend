# Licencias

Hechos verificados sobre las licencias de lo que este proyecto usa, sus consecuencias prácticas y las opciones para
decidir. **No es asesoría legal**: recoge hechos y opciones; la decisión y cualquier interpretación vinculante
corresponden a los titulares de los derechos, con asesoría si la necesitan. El proyecto usa la licencia provisional
**LGPL-3.0-or-later** ([ADR-0015](../adr/0015-licencia-lgpl-provisional.md)).

## Estado verificado

Comprobado el 2026-10-27 con `gh api` sobre los commits de
[`docs/research/reference-repositories.md`](../research/reference-repositories.md#6-licencias) y con los registros
públicos (Maven Central, npm).

| Componente                                                                    | Licencia                                                                        | Cómo se verificó                                                                                                     |
| ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `wasichai/wasichai`                                                           | **Ninguna**                                                                     | Sin archivo de licencia en el árbol, `GET /repos/wasichai/wasichai/license` → 404, POM sin `licenses`, sin cabeceras |
| `wasichai/wasichai-ui`                                                        | **Ninguna**                                                                     | Sin archivo; ningún `package.json` declara `license`                                                                 |
| `wasichai/sgspe`                                                              | **Ninguna**                                                                     | Sin archivo; `slides/package.json` dice `"license": "UNLICENSED"`                                                    |
| `wasichai/sgspe-ui`                                                           | **Ninguna**                                                                     | Sin archivo                                                                                                          |
| `wasichai/srtm-backend` (manuales SRTM)                                       | **Ninguna** para el proyecto; solo las de sus fuentes (OFL, DejaVu, Liberation) | Árbol del repositorio                                                                                                |
| Embabel Agent 1.5.3                                                           | Apache-2.0                                                                      | Repositorio `embabel/embabel-agent`                                                                                  |
| Spring Boot 4.1.1, Spring AI 2.0.1, Kotlin 2.4.21                             | Apache-2.0                                                                      | POM en Maven Central                                                                                                 |
| React, Vite, Tailwind CSS, React Router, TanStack Query, Zod, i18next, Vitest | MIT                                                                             | Campo `license` en npm                                                                                               |
| Playwright                                                                    | Apache-2.0                                                                      | Campo `license` en npm                                                                                               |
| Remotion 4.0.x                                                                | Licencia propia de Remotion, no OSI                                             | npm: `SEE LICENSE IN LICENSE.md`; `remotion-dev/remotion:LICENSE.md`                                                 |

El resto del árbol de dependencias se verifica con un informe automático de licencias en CI (planificado F1; la
herramienta se elige al configurarlo).

El usuario es administrador de la organización `wasichai` en GitHub. Ser administrador permite añadir archivos de
licencia a esos repositorios; que esa persona u organización sea titular de **todos** los derechos sobre su contenido
(aportaciones de terceros, textos y capturas de los manuales) no está verificado aquí.

## Consecuencias prácticas

Sin licencia, el contenido de un repositorio queda con todos los derechos reservados por su autor. Los términos de
servicio de GitHub permiten verlo y bifurcarlo dentro de GitHub, pero no conceden por sí mismos derecho a copiarlo,
modificarlo ni redistribuirlo fuera de ese uso. Para este proyecto significa:

1. **No se copia código de Wasichai, sgspe ni sgspe-ui.** Se consumen sus artefactos publicados (Maven y npm en GitHub
   Packages) y se siguen sus guías y patrones; código propio escrito desde cero (por ejemplo `model/apply.py`).
2. **No se copia texto ni capturas de los manuales SRTM y SGSPE.** Los cursos de demostración usan solo títulos,
   resúmenes redactados aquí y referencias `repo@sha:ruta#ancla` al original; se marcan como ilustrativos.
3. **Publicar este proyecto como open source está bloqueado en la práctica**: aunque su código tuviera licencia, depende
   de artefactos sin licencia, y distribuir una imagen o un paquete que los incluya no tiene permiso explícito.
4. La IA (F5) no genera materiales publicables a partir de los manuales mientras no haya permiso de uso del texto; las
   evaluaciones de modelos trabajan con referencias y datos sintéticos.

## Dependencias con condiciones especiales

- **Remotion**: según su `LICENSE.md`, es gratuito para particulares, organizaciones con ánimo de lucro de hasta 3
  empleados, organizaciones sin ánimo de lucro y evaluación; el resto necesita una licencia de empresa. Prohíbe vender o
  sublicenciar derivados de su código. Consecuencia: el renderizado de vídeo (F5) es un componente opcional, separado
  del núcleo, y quien lo despliegue revisa si necesita licencia de empresa ([IA §12](../ai/README.md#12-remotion-sección-12)).
- **Fuentes tipográficas**: la OFL permite usar y redistribuir las fuentes con la aplicación, sin venderlas por
  separado y conservando su aviso de licencia.
- **Embabel y Spring** (Apache-2.0): exigen conservar los avisos de licencia y `NOTICE` al redistribuir.

## Opciones para este proyecto

Para el **código**:

| Licencia   | A favor                                                                     | En contra                                                       |
| ---------- | --------------------------------------------------------------------------- | --------------------------------------------------------------- |
| Apache-2.0 | La de Spring, Kotlin y Embabel; concesión expresa de patentes; muy conocida | Permite forks cerrados y servicios sin devolver cambios         |
| AGPL-3.0   | Quien ofrezca el LMS modificado como servicio en red publica sus cambios    | Muchas organizaciones la evitan; exige dependencias compatibles |
| MIT        | La más simple y permisiva; máxima adopción                                  | Sin concesión expresa de patentes; permite forks cerrados       |

Para los **materiales de curso** (texto, imágenes, vídeos), una licencia de contenido separada es habitual, por ejemplo
CC BY 4.0 o CC BY-SA 4.0; cada material puede llevar la suya en sus metadatos (especificación §9). Aplica solo al
contenido propio; el derivado de los manuales necesita antes el permiso del titular.

## Decisión

1. **Licencia del proyecto: LGPL-3.0-or-later, provisional** ([ADR-0015](../adr/0015-licencia-lgpl-provisional.md)),
   decidida por la dirección el 2026-10-28. `LICENSE` (LGPL-3.0) y `COPYING` (GPL-3.0) en ambos repositorios;
   `package.json` la declara. Cubre el código, la documentación y los materiales propios de la demo.
2. **Pendiente: licencia de Wasichai y de los manuales.** Sin ella sigue bloqueado distribuir binarios o imágenes que
   incluyan Wasichai, y reutilizar el texto o las capturas de los manuales. La licencia que se dé a Wasichai debe ser
   compatible con la LGPL (por ejemplo Apache-2.0, MIT o la propia LGPL).
3. **Pendiente: términos de contribución** (DCO o CLA) antes de aceptar la primera aportación externa, y, si se quiere,
   una licencia de contenido aparte para los materiales de curso.
