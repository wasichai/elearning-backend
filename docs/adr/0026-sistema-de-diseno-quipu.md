# ADR-0026: La interfaz se viste con el sistema de diseño Quipu como tema de app sobre `@wasichai/ui`

**Estado**: propuesta · 2026-11-01 · desarrolla [ADR-0005](0005-lms-en-el-registro-de-wasichai-ui.md) para el aspecto de
la interfaz y no cambia el aislamiento de la lectura de [ADR-0012](0012-aula-del-estudiante.md)

## Contexto

- El sistema de diseño «Quipu» vive en `elearning-frontend/docs/design-system/` (voz y tono, patrones, accesibilidad,
  responsive, `tokens.json` y guías por componente). Sus vistas previas son HTML con una hoja propia (`bundle.css`, clases
  `wq-*`) y su sección de Tailwind está escrita para Tailwind 3.
- La interfaz es una única `<WasichaiApp>` con módulos (ADR-0005): sin segunda librería visual ni segundo router; los
  componentes vienen de `@wasichai/ui` y el estilo de Tailwind 4 con los tokens de su tema.
- `@wasichai/ui` 1.5.0 define el contrato de temas: tokens CSS por `[data-theme='<id>']`, temas de app en
  `config.themes`/`config.systemThemes` y hojas opcionales que pintan sus componentes por `data-slot` públicos en
  `@scope` ([ADR-034](https://github.com/wasichai/wasichai/blob/main/docs/adr/0034-user-preferences-and-themes.md) y
  [ADR-035](https://github.com/wasichai/wasichai/blob/main/docs/adr/0035-theme-extension-tokens-slots-and-optional-sheets.md)
  de Wasichai; README de `@wasichai/ui` 1.5.0, «Theme tokens» y «Styling hooks»). Los temas portal-tributario y
  ant-design son hojas así.
- Los criterios del sistema son WCAG 2.2 AA (texto 4,5:1, bordes y foco 3:1), el color nunca como único indicador y
  objetivos de 44px, móvil primero en el aula y escritorio en docencia.
- La lectura HTML de una lección se pinta en un `iframe` con `sandbox` vacío y una CSP propia en el `srcdoc`
  (`default-src 'none'; img-src data:; style-src 'unsafe-inline'`): ni ejecuta código ni pide nada fuera (ADR-0012).

## Decisión

1. **Quipu es un tema de app sobre el contrato de `@wasichai/ui`, no una librería.** `src/theme/quipu.css` pone todos los
   tokens de Wasichai en dos bloques, `quipu` (claro) y `quipu-dark` (oscuro), traducidos de `tokens.json`. La app los
   ofrece en `config.themes`; con `config.systemThemes`, un usuario nuevo entra en Quipu claro u oscuro según su sistema,
   y el claro y el oscuro de Wasichai siguen en el selector. El script de arranque de `index.html` aplica el tema guardado
   antes del bundle. Las clases `wq-*` y la hoja de las vistas previas no se usan en la app.
2. **Los controles se ajustan con una hoja aparte, solo por los `data-slot` públicos.** `quipu-controls.css` va en
   `@scope` de los temas Quipu y no entra en un subárbol con otro tema. Fija:
   - botones `md` e `icon` de 44px; el `sm` mide 36px a la vista y tiene un área táctil de 44px exactos, y solo se usa en
     las tablas densas de docencia (el catálogo y el aula no lo usan, y una prueba de frontera lo vigila);
   - campos de 44px que escriben a 16px; un campo inválido lleva 2px de `danger` sin saltar;
   - deshabilitados con `cursor: not-allowed`, pestañas de 44px subrayadas en `brand` y alertas con `radius-lg`.

   Sin colores propios: solo tokens. Lo que la hoja no alcanza se pide a Wasichai y no se parchea: hoy, los `data-slot` de
   los ítems del menú del shell y de su selector de tema
   ([wasichai/wasichai-ui#116](https://github.com/wasichai/wasichai-ui/issues/116)).

3. **El modo lectura es un subárbol, no un tema de página.** El tercer bloque, `quipu-lectura` (papel sepia, mismos pares
   AA), no está en `config.themes`. La lección lo pone con `data-theme` en su contenedor cuando el estudiante pulsa
   «Modo lectura», junto al tamaño de la lectura (17–23px). Sobre un tema oscuro, la lección sigue oscura y la lectura
   abre el interlineado. La elección se guarda en el navegador con el `storagePrefix` (`<prefix>.reading`), como el tema
   y el idioma antes de sincronizarse: es una comodidad de lectura, no un dato del estudiante, y no necesita API.
4. **La lectura HTML recibe el tema sin aflojar su aislamiento.** El `iframe` tiene origen opaco y no ve las variables CSS
   de la página: la app le pasa los colores resueltos y validados como color (nada que cierre la regla), el tamaño y el
   interlineado. Todo va en un `<style data-reading>` detrás de la CSP, dentro de `:where()` (el estilo del autor siempre
   gana). **La CSP no cambia.**
5. **Las fuentes las sirve la propia app**, sin peticiones a terceros: Figtree para la interfaz y Literata para leer
   (enunciados, respuestas en la corrección y el certificado, con la utilidad `font-read`). Las dos tienen licencia SIL
   OFL, que va junto a los archivos; son cortes latin con el eje de peso (Literata, sin cursiva). **Dentro del
   `iframe` no hay Literata**: la CSP no deja cargar fuentes, y la lectura cae en Georgia u otra serifa del sistema.
6. **Componentes de Quipu solo cuando una pantalla los necesita**, sobre `@wasichai/ui`:
   - el progreso en nudos, siempre con su texto (Mis cursos y el temario);
   - las insignias de estado con icono y palabra, sobre `Badge`;
   - el sello de maíz de la finalización: una sola animación, quieta con movimiento reducido;
   - el certificado en A4 apaisado, fijado al tema claro de Quipu.

   «Vencido», los toasts y el resto de las guías esperan a su pantalla.

7. **Las etiquetas de los temas viven en el i18n del aula** (`classroom:theme.*`) mientras Wasichai no tenga textos de app
   ([wasichai/wasichai-ui#115](https://github.com/wasichai/wasichai-ui/issues/115)).
8. **AA se prueba, no se supone.** `quipu.test.ts` comprueba en cada bloque que están todos los tokens del contrato y que
   los pares de texto llegan a 4,5:1 y los de bordes, foco y marcas gráficas a 3:1. La hoja de controles tiene pruebas
   de scope y de hooks, como las hojas de `@wasichai/ui`, y e2e que miden los controles en el navegador.

## Alternativas consideradas

- **Llevar `bundle.css` y sus clases `wq-*` a la app.** Rechazada: es una segunda librería visual en paralelo a
  `@wasichai/ui` (regla 2 de elearning-frontend) y está escrita para Tailwind 3.
- **Un fork o un parche de los componentes de `@wasichai/ui`.** Rechazada: rompe «Wasichai primero». El contrato de temas
  y los `data-slot` bastan, y lo que falta se pide (#115, #116).
- **Sustituir los temas de Wasichai por Quipu.** Rechazada: el claro y el oscuro de Wasichai siguen en el selector sin
  coste, y quien los prefiere los conserva.
- **Ofrecer el modo lectura como un tema más del selector.** Rechazada: pintaría toda la aplicación de sepia; el modo es
  de la lección y se activa desde ella.
- **Guardar la preferencia de lectura en el servidor.** Rechazada por ahora: no hay API de preferencias propia y la de
  Wasichai cubre tema e idioma. Si se pide que viaje entre dispositivos, se valora con un requisito.
- **Fuentes de Google Fonts.** Rechazada: es una petición a un tercero con los datos de navegación del estudiante y
  obligaría a abrir la CSP de la app a otro origen.
- **Incrustar Literata en la lectura del `iframe` con `font-src data:`.** No se decide aquí. La CSP de la lectura pasaría
  a `default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'`, con la fuente en base64 dentro del
  `srcdoc` (unos 70 KB por lectura). No abre ningún origen, pero cambia una pieza de seguridad de ADR-0012 y pesa en
  cada lectura: si se quiere, pide su propio ADR con medición.

## Consecuencias

- Un token nuevo en el contrato de Wasichai obliga a añadirlo en los tres bloques Quipu: `quipu.test.ts` falla hasta
  entonces.
- Subir de versión `@wasichai/ui` exige revisar que los `data-slot` que usa `quipu-controls.css` siguen existiendo; las
  pruebas solo admiten los hooks documentados.
- El modo lectura y el tamaño de texto se pierden al cambiar de navegador. Es aceptable mientras sean una comodidad.
- La lectura del `iframe` no se ve en Literata salvo que el equipo la tenga instalada.
- Cuando se publiquen #115 y #116, la interfaz mueve las etiquetas de tema al mecanismo nuevo y extiende la hoja de
  controles al menú del shell.
- El menú lateral del shell ya se pliega en móvil en `@wasichai/core` 1.5.0: no hace falta pedirlo.
