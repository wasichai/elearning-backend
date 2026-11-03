# Licencias

Hechos verificados sobre las licencias de lo que este proyecto usa, sus consecuencias prácticas y las opciones para
decidir. **No es asesoría legal**: recoge hechos y opciones; la decisión y cualquier interpretación vinculante
corresponden a los titulares de los derechos, con asesoría si la necesitan. El proyecto usa la licencia
**LGPL-3.0-or-later**, definitiva desde el 2026-11-03 ([ADR-0030](../adr/0030-lgpl-definitiva-y-conservacion-segun-los-terminos.md)).

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
| Embabel Agent 1.5.4                                                           | Apache-2.0                                                                      | Repositorio `embabel/embabel-agent`                                                                                  |
| Spring Boot 4.1.1, Spring AI 2.0.1, Kotlin 2.4.21                             | Apache-2.0                                                                      | POM en Maven Central                                                                                                 |
| React, Vite, Tailwind CSS, React Router, TanStack Query, Zod, i18next, Vitest | MIT                                                                             | Campo `license` en npm                                                                                               |
| Playwright                                                                    | Apache-2.0                                                                      | Campo `license` en npm                                                                                               |
| Remotion 4.0.x (`latest` en npm: 4.0.540, 2026-11-03)                         | Licencia propia de Remotion, no OSI ([detalle](#remotion))                      | npm: `SEE LICENSE IN LICENSE.md`; `remotion-dev/remotion:LICENSE.md`                                                 |

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

- **Remotion**: licencia propia, no OSI, gratuita solo en algunos casos y de pago en los demás; detalle en
  [Remotion](#remotion). Es el motor de render de la Fase 5, opcional y desactivado por defecto, en un trabajador separado
  ([ADR-0039](../adr/0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md)).
- **Fuentes tipográficas**: la OFL permite usar y redistribuir las fuentes con la aplicación, sin venderlas por
  separado y conservando su aviso de licencia.
- **Embabel y Spring** (Apache-2.0): exigen conservar los avisos de licencia y `NOTICE` al redistribuir.

## Remotion

Términos consultados el **2026-11-03** (issue [#267](https://github.com/wasichai/elearning-backend/issues/267)). El
`LICENSE.md` de 4.x se volvió a leer ese día en el repositorio de Remotion; `remotion.dev` no se pudo abrir desde la sesión
que escribió esta sección, así que lo citado de los términos v5.0 y de la FAQ es lo que recogió #267 el mismo día. **No es
asesoría legal**; la licencia es de quien usa Remotion y lo vinculante es lo que diga Remotion.

### Términos vigentes: Remotion 4.x

Fuente: [`remotion-dev/remotion:LICENSE.md`](https://github.com/remotion-dev/remotion/blob/main/LICENSE.md) (último
cambio 2026-01-01) y [Términos v4.0](https://www.remotion.pro/terms-4-0).

- **Free License** para: un particular; «a for-profit organization with **up to 3 employees**»; «a non-profit or
  not-for-profit organization»; y quien evalúa Remotion «and [is] not yet using it in a commercial way». El texto de 4.x no
  menciona a los contratistas ni excluye a centros educativos u organismos públicos.
- Todos los demás necesitan una **Company License**.
- En todos los casos está prohibido copiar o modificar el código de Remotion para venderlo, alquilarlo o licenciarlo, y
  relicenciar o sublicenciar un derivado propio de Remotion.
- La evaluación acaba con la decisión de usar Remotion en la propia tecnología: desarrollar la Fase 5 en serio ya no es
  evaluar.

### Términos anunciados: Remotion 5.0

Fuente: [Términos v5.0](https://www.remotion.dev/docs/terms), [FAQ](https://www.remotion.dev/docs/license/faq) y la PR
[remotion-dev/remotion#3750](https://github.com/remotion-dev/remotion/pull/3750) (abierta; 5.0 no estaba publicado el
2026-11-03).

- **Umbral de 3 personas**: la Free License pasa a «an organization or team of individuals with up to 3 people»
  ([§TeamSize](https://www.remotion.dev/docs/terms#TeamSize)): cuenta todo el personal, use o no Remotion.
- **Los contratistas cuentan** en ese total.
- **Centros educativos y organismos públicos**: la exención de las organizaciones sin ánimo de lucro
  ([§NonProfitOrganization](https://www.remotion.dev/docs/terms#NonProfitOrganization)) excluye «governmental entities,
  public-sector bodies, educational institutions» salvo **permiso escrito** de Remotion (hi@remotion.dev). Que esta
  exclusión rija ya para 4.x es **a verificar**: los términos v4.0 no la recogen.
- **El usuario no aporta código** ([§RenderingService](https://www.remotion.dev/docs/terms#RenderingService)): se permite que
  los usuarios cambien parámetros de composiciones alojadas o que una IA escriba el guion; está prohibido que aporten su
  propio código Remotion. La licencia es por usuario
  ([§EndUserCodeAccess](https://www.remotion.dev/docs/terms#EndUserCodeAccess)): cada despliegue decide la suya.
- **Telemetría**: con 5.0, el plan Automators **exige** una clave de licencia que envía un evento por render (o un informe
  mensual verificable). Si la Free License necesita clave o envía algo es a verificar al subir a 5.0.
- Usar Remotion sin la licencia que corresponde obliga a pagar lo adeudado (§Licensing obligations).

### Precios

Fuente: [FAQ](https://www.remotion.dev/docs/license/faq) y [remotion.pro/license](https://www.remotion.pro/license)
(compra en autoservicio con Stripe, sin reembolsos). Precios del 2026-11-03.

| Plan                                 | Para qué                                                   | Precio                                      |
| ------------------------------------ | ---------------------------------------------------------- | ------------------------------------------- |
| Free License                         | Los casos de arriba                                        | 0 USD, sin registro                         |
| Company: Remotion for Creators       | Vídeos hechos a mano                                       | 25 USD/mes por puesto                       |
| Company: **Remotion for Automators** | Automatizaciones (`renderMedia()`, `npx remotion render`…) | 0,01 USD por render, **mínimo 100 USD/mes** |
| Enterprise                           | Contrato a medida                                          | Desde 500 USD/mes                           |

El trabajador de render de este proyecto encaja en **Automators**; servidor propio y Lambda cuestan lo mismo y las
previsualizaciones en Studio o Player no cuentan. La licencia no cubre patentes de códecs (H.264, AAC).

### Qué significa aquí

Decisión del titular ([ADR-0039](../adr/0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md)):

- Remotion es el motor de render de la Fase 5. El titular tiene **3 personas o menos, contratistas incluidos**: Free
  License, sin pagar. Se revisa al subir a 5.0 o si el total pasa de 3.
- En el producto open source el render es **opcional y desactivado por defecto**, en un trabajador Node separado; Remotion
  es solo dependencia npm de ese trabajador y el proyecto no lo redistribuye. Sin excepción de enlace en la LGPL mientras
  siga así.
- **Cada despliegue de terceros es responsable de su propia licencia**: una organización de más de 3 personas, una
  universidad o un organismo público que active el render necesita una Company License o un permiso escrito
  ([guía de despliegue](../operations/README.md#render-de-vídeo-opcional)).
- Cómo es el trabajador (paquete `render-worker/` con su propio `package.json`, entrada solo de datos, comunicación con la
  cola, almacenamiento y validación): [ADR-0040](../adr/0040-renderizador-de-video-en-un-trabajador-node.md).

### El trabajador: Remotion, ffmpeg y fuentes

Lo que lleva [`render-worker/`](../../render-worker) (E37.2, [#297](https://github.com/wasichai/elearning-backend/issues/297)),
comprobado el 2026-11-03 con Remotion 4.0.540:

- **Remotion solo aquí**: `remotion`, `@remotion/bundler`, `@remotion/renderer` y `@remotion/fonts` fijados a 4.0.540 en
  `render-worker/package.json`; arrastran otros `@remotion/*` (`studio`, `player`, `web-renderer`, `compositor-*`…). Ni el
  `package.json` raíz (que no tiene _workspaces_) ni el backend lo declaran: CI falla si el `yarn.lock` raíz, su
  `node_modules` o el classpath del backend (`./gradlew noRemotion`) lo traen.
- **Informe de licencias** (`yarn licenses` en `render-worker/`, trabajo `render-worker` de CI, artefacto
  `render-worker-licencias`): sobre la instalación de producción, lista Remotion aparte (licencia propia) y falla si otro
  paquete no tiene una licencia libre de la lista (MIT, ISC, Apache-2.0, BSD, MPL-2.0…) o si Remotion no aparece. Al
  escribir esto: 172 paquetes, 23 de Remotion; el resto MIT, ISC, BSD-2/3, Apache-2.0, MPL-2.0, Unlicense, 0BSD y
  CC-BY-4.0.
- **Sin clave ni telemetría**: el trabajador no pasa `licenseKey` a `renderMedia`; en 4.0.540 (`@remotion/renderer`,
  `render-media.js`) el evento de uso solo se envía con clave. Con la Free License del titular no hay clave que poner.
- **ffmpeg y ffprobe**: Remotion trae su propia compilación en `@remotion/compositor-<plataforma>` y el trabajador usa ese
  `ffprobe` para el informe (`FFPROBE` nombra otro). `ffprobe -version` dice `n7.1` con `--enable-gpl`, `--enable-libx264`,
  `--enable-libx265` y `--enable-libfdk-aac`, sin `--enable-nonfree`. Es decir, es una compilación **GPL**, no LGPL: el
  H.264 que produce Remotion sale de libx264 (GPL-2.0 o posterior), así que una compilación LGPL no sirve para este motor.
  Además, la licencia de libfdk-aac se considera incompatible con la GPL (ffmpeg pide `--enable-nonfree` para combinarlas):
  **a verificar con asesoría** antes de redistribuir una imagen que la incluya. Lo que hace el proyecto: no redistribuye
  esos binarios (llegan de npm a quien construye la imagen; ADR-0039 §7) y los ejecuta como programas aparte
  (`execFile`, sin enlazarlos). Quien publique una imagen del trabajador redistribuye un ffmpeg GPL (con sus obligaciones,
  como ofrecer la fuente) y es quien tiene que resolver lo de libfdk-aac. El backend no lleva ffmpeg.
- **Fuentes**: Inter, Source Serif 4 y JetBrains Mono (subconjunto latino, de los paquetes `@fontsource` 5.x), con
  licencia **SIL OFL 1.1**, en `render-worker/fonts/` con su texto de licencia (`OFL-*.txt`). Van en el paquete: nunca se
  descargan al renderizar.

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

1. **Licencia del proyecto: LGPL-3.0-or-later**, provisional desde el 2026-10-28
   ([ADR-0015](../adr/0015-licencia-lgpl-provisional.md)) y definitiva desde el 2026-11-03
   ([ADR-0030](../adr/0030-lgpl-definitiva-y-conservacion-segun-los-terminos.md)). `LICENSE` (LGPL-3.0) y `COPYING` (GPL-3.0) en ambos repositorios;
   `package.json` la declara. Cubre el código, la documentación y los materiales propios de la demo.
2. **Pendiente: licencia de Wasichai y de los manuales.** Sin ella sigue bloqueado distribuir binarios o imágenes que
   incluyan Wasichai, y reutilizar el texto o las capturas de los manuales. La licencia que se dé a Wasichai debe ser
   compatible con la LGPL (por ejemplo Apache-2.0, MIT o la propia LGPL).
3. **Pendiente: términos de contribución** (DCO o CLA) antes de aceptar la primera aportación externa, y, si se quiere,
   una licencia de contenido aparte para los materiales de curso.
4. **Remotion para el render de vídeo** (2026-11-03, [ADR-0039](../adr/0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md)):
   motor de la Fase 5 con la Free License del titular (3 personas o menos, contratistas incluidos); opcional y desactivado
   por defecto, en un trabajador separado; cada despliegue responde de su propia licencia ([Remotion](#remotion)). El
   renderizador técnico (trabajador `render-worker/`, entrada, cola, almacenamiento y validación) es [ADR-0040](../adr/0040-renderizador-de-video-en-un-trabajador-node.md).
