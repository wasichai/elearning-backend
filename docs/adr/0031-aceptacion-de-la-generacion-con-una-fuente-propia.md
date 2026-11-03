# ADR-0031: La generación de HTML y PDF se acepta con una fuente propia, con Asciidoctor real en CI

**Estado**: aceptada · 2026-11-03 · cambia la aceptación de RF-CON-07 y precisa
[ADR-0018 §7](0018-fase-4-estudio-revision-e-importador.md)

## Contexto

RF-CON-07 pedía que «una lección importada produzca ambos artefactos validados sin bloquear peticiones HTTP». La
importación no copia el texto de los manuales: propone la estructura con trazas `repo@sha:ruta#ancla` y nada más
([ADR-0018 §6](0018-fase-4-estudio-revision-e-importador.md), [ADR-0015](0015-licencia-lgpl-provisional.md)). Una lección
importada no tiene, por tanto, un AsciiDoc que generar, y la aceptación no se podía cumplir sin cambiar esa decisión. La
dirección decidió el 2026-11-03 cambiar la aceptación, no la importación.

La generación ya existe (`RenderJobHandler`, `AsciidoctorRenderer`), pero hasta ahora se probaba con un generador doble y
el adaptador con guiones de shell: nada en CI demostraba que Asciidoctor de verdad produce algo válido con las opciones
que le pasamos. Comprobado a mano con Asciidoctor 2.0.26 y Asciidoctor PDF 2.3.27 en modo `secure`, sobre la carpeta
temporal que crea el adaptador y que solo contiene la fuente:

- `include::` no se resuelve: se convierte en un enlace a su destino, también si el archivo existe en la carpeta y
  también para `include::/etc/passwd[]`. No se lee nada.
- Una imagen incrustada como URI `data:` llega al PDF como imagen. Una ruta relativa no existe en la carpeta: el PDF
  pone su texto alternativo, con un aviso que no hace fallar la generación.
- En el HTML, Asciidoctor deja `<img src="…">`; el saneado (`GeneratedHtml`, ADR-0022) solo admite `http` y `https` en
  `src`, así que de una imagen `data:` o relativa queda el elemento con su texto alternativo.
- Las tablas salen en ambos formatos.

## Decisión

1. **Aceptación nueva de RF-CON-07.** Una `fuente_recurso` AsciiDoc propia, con `include::`, una imagen y una tabla,
   produce en la cola un HTML y un PDF validados, y la petición que la encola responde `202` sin haber generado nada:
   - HTML: el título, el texto y la tabla; la imagen por su texto alternativo; el `include::` como enlace, sin el
     contenido de su destino; sin scripts (saneado).
   - PDF: empieza por `%PDF-`, termina en `%%EOF`, tiene al menos una página y la imagen incrustada.
   - Cada artefacto nombra al generador real (`asciidoctor`, `asciidoctor-pdf`) y su versión (RF-CON-03).
2. **La fuente es un solo archivo autosuficiente.** Seguimos en modo `secure` (ADR-0018 §7): `include::` nunca lee del
   disco ni de la red y queda como enlace. Componer fragmentos es cosa del autor o de la importación, que analiza el
   grafo de `include::` (ADR-0010), no del generador. Una imagen que deba verse en el PDF va incrustada como URI `data:`.
3. **La prueba corre con Asciidoctor real en CI.** `RenderApiTest` prueba el recorrido con el doble siempre y, en otra
   prueba, con el adaptador real cuando `asciidoctor` y `asciidoctor-pdf` están en el `PATH`; sin ellos se salta. CI los
   instala con `gem install` en versiones fijas antes de las pruebas de integración y define
   `LMS_TEST_ASCIIDOCTOR=required`, con lo que su falta es un fallo y no una prueba saltada.
4. **El binario en la imagen de despliegue queda en F6** (RNF-OPS-04): esta aceptación prueba el generador, no el
   despliegue. Sin el binario el trabajo sigue fallando con un error claro.

## Alternativas consideradas

- **Mantener la aceptación con una lección importada.** Exigiría copiar el cuerpo de los manuales, rechazado mientras no
  tengan licencia (ADR-0018 §6).
- **Modo `safe` para que `include::` lea fragmentos de la carpeta de trabajo.** Rechazada: la fuente es un único archivo,
  así que no habría fragmentos que leer, y `safe` abre la lectura de archivos a cambio de nada. Si algún día la fuente
  lleva sus fragmentos e imágenes (un paquete), un ADR nuevo lo decide con su modelo de amenazas.
- **Admitir `data:` en `src` de las imágenes al sanear el HTML.** Fuera de esta aceptación: cambia la lista blanca de
  §5.2 de seguridad y pide su propia prueba. Queda como mejora: hoy el HTML generado no muestra imágenes.
- **La imagen oficial `asciidoctor/docker-asciidoctor` como servicio del trabajo de CI.** Las pruebas lanzan el binario
  desde la JVM, en el mismo anfitrión: un contenedor aparte pediría un envoltorio que el adaptador de producción no usa.
- **Probar el PDF con PDFBox.** Una dependencia nueva solo para pruebas; la estructura y la imagen incrustada bastan
  para validar lo que generamos (regla 8 de `CLAUDE.md`).

## Consecuencias

- RF-CON-07 pasa a implementado: la prueba corre de verdad en CI y no se puede saltar allí.
- El trabajo de CI tarda unos segundos más en instalar las gemas; las versiones fijas se suben a mano, como las acciones.
  Las dependencias de las gemas (Prawn y otras) las resuelve `gem` sin fijar.
- En local, sin Asciidoctor la prueba real se salta; con él (`gem install --user-install asciidoctor asciidoctor-pdf`)
  corre.
- Las imágenes del HTML generado quedan por su texto alternativo hasta que otro ADR decida cómo servirlas.
