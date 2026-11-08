# ADR-0045: La lección de lectura se pinta en la página como documento de bloques, sin iframe

**Estado**: aceptada · 2026-11-08 · reemplaza en parte a [ADR-0012](0012-aula-del-estudiante.md) (el `iframe` del punto 3
y la alternativa «Sanear el HTML»; el resto sigue vigente) ·
[#384](https://github.com/wasichai/elearning-backend/issues/384), épica [#385](https://github.com/wasichai/elearning-backend/issues/385)

## Contexto

- ADR-0012 §3 muestra el HTML de una lectura en un `iframe` con `sandbox` sin `allow-scripts` y rechazó sanearlo para
  pintarlo en la página, por no añadir un saneador y su riesgo residual de XSS.
- La auditoría de la interfaz ([documento](https://claude.ai/artifact/2vSwNNERREDaTSCPQMbU1C)) señala dos problemas
  de impacto alto que nacen de ese `iframe`:
  - **1.** En el aula el contenido no manda: el menú global y el temario siguen visibles mientras se lee y no hay modo
    foco. El `iframe` es una caja de alto fijo que no sigue la medida ni el ritmo de la página.
  - **5.** Hay dos tipografías en la misma lección: el título va en serif y el cuerpo en la sans del sistema, porque la
    política de seguridad del `iframe` bloquea las fuentes.
- Colofón ([ADR-0044](0044-sistema-de-diseno-colofon.md)) pide una lectura con sistema propio: Newsreader 20/33, medida
  de 66ch y el tema sepia `colofon-lectura` como subárbol de la lección. Nada de eso alcanza el interior del `iframe`.
- Desde ADR-0022 el HTML que genera Asciidoctor ya se guarda saneado con lista blanca (`GeneratedHtml`, jsoup): el
  saneador que ADR-0012 no quería añadir ya está en el servidor.

## Decisión

1. **El servidor convierte la lectura en un documento de bloques de vocabulario cerrado** y la interfaz lo pinta con sus
   componentes. `GET /api/lms/classroom/enrollments/{enrollmentId}/resources/{resourceVersionId}/reading` devuelve
   `ReadingDocument` (contrato en [docs/api/lms.md](../api/lms.md)): bloques `heading`, `paragraph`, `list`, `table`,
   `code`, `quote`, `admonition`, `figure` y `rule`; en línea, `text`, `em`, `strong`, `code`, `link` y `break`. Sin HTML,
   clases, estilos ni atributos del autor: React escapa el texto y nunca usa `dangerouslySetInnerHTML`.
2. **Se calcula en cada petición** desde el archivo de la versión publicada (inmutable), sin tabla ni migración. El archivo
   se lee de `wasichai-files` como quien llama y pasa otra vez por `GeneratedHtml` antes de convertirse: defensa en
   profundidad para un HTML subido a mano o guardado antes del saneado. La conversión (jsoup, sin dependencias nuevas)
   corre en `Dispatchers.Default`, fuera del hilo de WebFlux.
3. **Reglas de la conversión** (`ReadingDocuments`):
   - lo que no es del vocabulario desaparece y su texto queda como párrafo; script, estilo, marcos, objetos, SVG y
     formularios desaparecen con su texto;
   - el `h1` va a `title`; `h5` y `h6` se aplanan a 4; los `id` de Asciidoctor se sanean a `[A-Za-z0-9_-]` y se
     deduplican;
   - enlaces solo `http`/`https` con servidor, `mailto` y `#ancla`; cualquier otro, texto;
   - imágenes solo `data:image/(png|jpeg|gif|webp);base64`; cualquier otra (remota, de la plataforma, SVG), su `alt` como
     párrafo. Nunca se emite un `src` arbitrario: la lectura no hace peticiones al abrirse;
   - las admoniciones de Asciidoctor van a `admonition` y los `pre` a `code`, con su lenguaje si es un nombre;
   - el índice y el pie de Asciidoctor son envoltorio y se descartan; un anidamiento más hondo que el tope se aplana.
4. **Mismo alcance que la descarga del archivo.** La matrícula es propia y no retirada, la versión de recurso está
   publicada y enlazada desde una lección de su versión de curso, y el archivo se lee con el alcance de `ContentReadScope`.
   Lo ajeno, lo inexistente, otro tipo de recurso o un recurso sin archivo son `404`.
5. **PDF**: visor y descarga. **DOCX**: solo descarga. **Markdown** queda fuera hasta que sea un formato fuente real;
   entonces entrará `commonmark-java` con el HTML desactivado y un ADR propio.

## Alternativas consideradas

- **Mantener el `iframe` con `font-src data:`** para que lleguen las fuentes. Rechazada: resuelve el problema 5 pero no
  el 1; la lectura sigue siendo una caja aparte que no hereda tema, medida ni modo foco, y las fuentes en `data:` engordan
  cada lección.
- **Sanear en el cliente (DOMPurify) e insertar el HTML.** Rechazada: una dependencia más en el frontend y un HTML
  arbitrario en el DOM de la aplicación, con su riesgo residual (mXSS, clobbering, CSS del autor). El vocabulario cerrado
  lo elimina por construcción: lo que no es un bloque conocido no llega al navegador.
- **Markdown como formato de lectura, con commonmark.** Aplazada: hoy la fuente es AsciiDoc; se decide cuando Markdown
  sea un formato fuente real.
- **DOCX en el visor.** Aplazada: convertirlo exige otra canalización; por ahora se descarga.

## Consecuencias

- La tarea R2 del frontend pinta `ReadingDocument` y borra el `iframe` y su política de seguridad (`reading.ts`).
- Un PDF tiene visor y descarga; un DOCX, solo descarga.
- Un HTML que use lo que el vocabulario no tiene (colores, columnas, imágenes remotas) pierde esa forma y conserva su
  texto: el material se diseña para el vocabulario.
- El HTML de Asciidoctor de hoy no lleva imágenes `data:` y `GeneratedHtml` quita los `src` que no son `http`/`https`,
  así que una figura se muestra como su `alt` hasta que el render incruste imágenes y el saneado las admita.
- Cada lectura cuesta leer el archivo y convertirlo; si se mide caro, se guarda el documento junto al artefacto.
