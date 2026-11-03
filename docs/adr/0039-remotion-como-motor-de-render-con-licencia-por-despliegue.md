# ADR-0039: Remotion como motor de render de vídeo; licencia gratuita para el titular y responsabilidad de cada despliegue

**Estado**: aceptada · 2026-11-03 · decisión del titular sobre [#267](https://github.com/wasichai/elearning-backend/issues/267);
precisa [`docs/ai/README.md`](../ai/README.md#12-remotion-sección-12) §12 y RF-MED-02

## Contexto

RF-MED-02 pide renderizar vídeo con Remotion de forma asíncrona, en un trabajador propio fuera de WebFlux. El proyecto es
LGPL-3.0-or-later ([ADR-0030](0030-lgpl-definitiva-y-conservacion-segun-los-terminos.md)) y Remotion no es software libre
OSI: tiene licencia propia. El issue #267 reunió sus términos (detalle, fuentes y fechas en
[`docs/legal/licensing.md`](../legal/licensing.md#remotion)); lo que importa para decidir:

- **Free License** (Remotion 4.x, la vigente): particulares, organizaciones con ánimo de lucro de **hasta 3 empleados**,
  organizaciones sin ánimo de lucro y evaluación. Los demás necesitan una **Company License**; para un trabajador que
  llama a `renderMedia()`, el plan «Remotion for Automators» (0,01 USD por render, mínimo 100 USD/mes).
- **Remotion 5.0** (anunciado, no publicado): el umbral pasa a «una organización o equipo de hasta 3 personas» y **los
  contratistas cuentan**; centros educativos, organismos públicos y entidades gubernamentales quedan fuera de la exención
  de las organizaciones sin ánimo de lucro salvo permiso escrito; Automators exige una clave de licencia que envía un
  evento por render (telemetría).
- La licencia es **de quien usa Remotion**, no del proyecto: cada organización que despliegue wasichai-learning con el
  render activo responde de la suya.
- Prohibido en todos los casos vender o relicenciar un derivado del código de Remotion, y que los usuarios finales aporten
  su propio código Remotion; sí se permite que cambien parámetros de composiciones alojadas o que una IA escriba el guion.
- Desarrollar la Fase 5 en serio ya no es «evaluar».

El issue planteó tres caminos: (a) Remotion como motor de F5; (b) ffmpeg o Revideo por defecto y Remotion opcional;
(c) descartar Remotion. Y dos casos de tamaño del titular: A (3 personas o menos) o B (4 o más).

**No es asesoría legal.** Lo que sigue registra la decisión del titular y su lectura de los términos; la interpretación
vinculante corresponde a Remotion y, si hace falta, a asesoría.

## Decisión

El titular decidió el 2026-11-03:

1. **Remotion es el motor de render de la Fase 5** (opción a). No se mantiene en paralelo un segundo motor (ffmpeg con
   diapositivas rasterizadas, Revideo): una abstracción necesita un segundo usuario concreto y hoy no lo hay. ffmpeg
   (`ffprobe`) se usa solo para **validar** el artefacto (RF-MED-03), no para producirlo.
2. **Caso A: la organización titular tiene 3 personas o menos, contratistas incluidos.** Le basta la **Free License** de
   Remotion, sin pagar ni registrarse. No se guarda ninguna clave de licencia, porque no la hay. Si el total de personas
   del titular (contratistas incluidos, use o no Remotion cada una) pasa de 3, el titular contrata una Company License
   (Automators) antes de seguir usando el trabajador, también para desarrollar, y lo registra en un ADR que reemplace a
   este.
3. **En el producto open source el render es opcional y está desactivado por defecto.** Con la configuración por
   defecto, el backend y el aula funcionan sin Remotion, la build del backend no instala Remotion y nada del núcleo
   depende de él.
4. **Trabajador separado.** El render corre en un **programa Node aparte**, fuera de la JVM y del hilo de WebFlux. Remotion
   es **solo una dependencia npm de ese trabajador** (su propio `package.json`): ni el backend ni el frontend lo declaran,
   y el repositorio no incluye ni modifica código de Remotion. El detalle técnico (paquete, entrada, comunicación con la
   cola, almacenamiento, validación) es de [E37.1, #296](https://github.com/wasichai/elearning-backend/issues/296).
5. **El trabajador acepta solo datos**: el guion de RF-MED-01 como props JSON validados contra su esquema, nunca código
   (componentes, expresiones, `import`, HTML para inyectar). Las composiciones son las del proyecto; el usuario o la IA
   cambian sus parámetros, no su código (§RenderingService de los términos).
6. **Cada despliegue de terceros es responsable de su propia licencia de Remotion.** Una empresa de más de 3 personas, una
   universidad, un centro educativo o un organismo público que active el render necesitará una Company License o un
   permiso escrito de Remotion. La guía de despliegue lo avisa con enlace a
   [remotion.dev/license](https://www.remotion.dev/license) y [remotion.pro/license](https://www.remotion.pro/license)
   ([operación](../operations/README.md#render-de-vídeo-opcional)).
7. **Sin excepción de enlace en la LGPL, por ahora.** El backend LGPL y el trabajador son programas separados que se
   comunican con datos JSON (y el vídeo resultante): el backend no se combina con Remotion. El código propio del
   trabajador (sus composiciones) es LGPL y usa Remotion como dependencia que **instala quien despliega** desde npm; el
   proyecto no redistribuye Remotion, ni en el repositorio ni en una imagen publicada. Si algún día el proyecto publica una
   imagen que lo incluya, o el trabajador deja de ser un programa aparte, se revisa con asesoría y, si hace falta, se
   añade un permiso adicional (GPL-3.0 §7) con un ADR nuevo. Esta lectura es **a verificar con asesoría** si un tercero
   pregunta antes de redistribuir.
8. **Remotion 5.0 se revisa al subir.** Pasar el trabajador a 5.0 es una decisión con su propia revisión: recontar a las
   personas del titular con contratistas, comprobar la exclusión de centros educativos y organismos públicos y la clave de
   licencia con telemetría (con Free License no debería hacer falta; a verificar entonces), y actualizar
   `docs/legal/licensing.md` y este ADR (con otro que lo reemplace) antes de mezclar la subida.

## Alternativas consideradas

- **(b) ffmpeg o Revideo por defecto y Remotion opcional** (la recomendación del issue). Evitaría depender de una licencia
  no libre en el camino por defecto, pero obliga a mantener dos motores y dos juegos de composiciones. El titular prefiere
  un motor; como el render ya es opcional y desactivado por defecto, un despliegue sin licencia simplemente no lo activa.
  Si un segundo usuario concreto pide render sin Remotion, se reabre con un ADR.
- **(c) Descartar Remotion.** Rechazada: RF-MED-02 lo pide, SRTM ya tiene guiones Remotion como precedente
  ([IA §12](../ai/README.md#12-remotion-sección-12)) y la licencia gratuita cubre al titular.
- **Remotion Lambda u otro servicio alojado.** Rechazada (RF-MED-02): servidor propio y Lambda cuestan lo mismo en la
  licencia, y el trabajador propio no saca el guion fuera del despliegue.
- **Remotion como dependencia del backend o del frontend** (por ejemplo, el Player para previsualizar). Rechazada: haría
  obligatoria la licencia a cualquier despliegue y mezclaría código LGPL con Remotion en el mismo programa.
- **Añadir ya una excepción de enlace a la LGPL.** Innecesaria mientras el trabajador sea un programa separado y el
  proyecto no redistribuya Remotion; añadirla después es posible porque el titular tiene los derechos de su código.

## Consecuencias

- El titular usa Remotion sin coste mientras siga en el caso A. Si crece, la Company License es un gasto recurrente
  (desde 100 USD/mes) que se decide antes de seguir usando el trabajador.
- Quien despliegue wasichai-learning sin activar el render no necesita nada de Remotion. Quien lo active lee el aviso de
  la guía de despliegue y decide su licencia; el proyecto no puede comprobarlo por él.
- Los criterios de #267 que piden código quedan para el trabajador ([E37.2, #297](https://github.com/wasichai/elearning-backend/issues/297)):
  build sin Remotion por defecto con su comprobación en CI, Remotion solo en el informe de licencias del trabajador,
  rechazo probado de una petición con código y la licencia del `ffprobe` que use el trabajador.
- `docs/legal/licensing.md` recoge los términos 4.x y 5.0; `docs/ai/README.md` §12 y `docs/operations` se alinean en el
  mismo cambio.

## Fuentes

Consultadas el 2026-11-03 (hallazgos del issue [#267](https://github.com/wasichai/elearning-backend/issues/267)):

- [`remotion-dev/remotion:LICENSE.md`](https://github.com/remotion-dev/remotion/blob/main/LICENSE.md) (Remotion 4.x)
- [Términos de Remotion v5.0](https://www.remotion.dev/docs/terms) y [Términos v4.0](https://www.remotion.pro/terms-4-0)
- [Preguntas frecuentes sobre la licencia](https://www.remotion.dev/docs/license/faq), [remotion.dev/license](https://www.remotion.dev/license)
  y [remotion.pro/license](https://www.remotion.pro/license)
- [remotion-dev/remotion#3750](https://github.com/remotion-dev/remotion/pull/3750) (cambio de licencia de 5.0, abierto)
