# ADR-0010: La importación AsciiDoc avanza por etapas y nunca publica sola

**Estado**: aceptada · 2026-10-27

## Contexto

- La especificación pide un importador de AsciiDoc (SRTM y SGSPE primero): analizar el maestro y el grafo `include::`,
  extraer capítulos, procedimientos, capturas, diagramas, tablas y referencias, validar, proponer unidades y lecciones,
  dejar que el creador modifique la estructura, emitir un informe y **nunca publicar automáticamente**; y diferenciar
  extracción, diseño pedagógico y aprobación editorial.
- Los manuales son documentación de referencia y procedimientos, **no material didáctico**: no tienen objetivos de
  aprendizaje, ejercicios, cuestionarios ni duraciones (búsqueda verificada). Sí tienen una anatomía fija por
  procedimiento (_Objetivo_, _Requisitos previos_, pasos, _Resultado esperado_, _Verificación_).
- Grafo de includes: SRTM de profundidad 1, SGSPE de profundidad 2 con `leveloffset=+1`; sin `tag=` ni `lines=`. Los
  fragmentos **no** son autocontenidos: se referencian con `<<id>>` entre sí.
- Se construyen con Asciidoctor 2.0.26 y asciidoctor-pdf 2.3.15; en el entorno no hay `asciidoctor` instalado.

Ver [`reference-repositories.md` §5](../research/reference-repositories.md#5-manuales-srtm-y-sgspe).

## Decisión

**La importación es una canalización de cinco etapas; cada una produce un artefacto versionado que la siguiente
consume, y solo una persona hace avanzar el resultado a publicación.**

1. **Extracción** (automática): resuelve el grafo `include::`, extrae estructura y recursos, valida referencias,
   imágenes y enlaces. Produce el árbol del documento con procedencia (`repo@sha:ruta#ancla`) y un informe de
   importación con faltantes y errores.
2. **Propuesta de estructura** (automática): mapea capítulos y procedimientos a unidades y lecciones candidatas.
   Produce una propuesta editable, marcada como tal.
3. **Diseño pedagógico** (creador; con asistencia de IA en F5): objetivos medibles, secuencia, carga, actividades y
   evaluaciones que los manuales no traen. Produce un borrador de versión de curso.
4. **Revisión** (revisor): observaciones ligadas a la versión; pedir cambios o rechazar. Produce una decisión
   registrada.
5. **Aprobación** (persona autorizada, sin autoaprobación): aprueba la versión. Publicar es un acto aparte.

- **Ninguna etapa publica.** Las etapas 1 y 2 nunca cambian el estado de una versión de curso.
- **Sin dependencia en producción de los repositorios fuente.** La extracción toma una fuente permitida o un paquete
  autorizado en una revisión concreta y guarda una copia de lo que usa; el contenido publicado sigue funcionando aunque
  el repositorio fuente cambie o desaparezca.
- **Asciidoctor corre en un contenedor**, invocado como trabajo asíncrono fuera del hilo de WebFlux, con límites de
  tiempo y tamaño; la versión de la imagen queda registrada en el informe.
- El texto de los documentos es dato no confiable: no se ejecuta, no se interpreta como instrucción (en F5, tampoco por
  un modelo) y las referencias externas no se siguen sin control (SSRF).
- Implementación planificada para F4; la etapa 3 con asistencia de IA, para F5.

## Alternativas consideradas

- **Importar y publicar en un paso.** Rechazado: la especificación lo prohíbe y los manuales no son material
  didáctico; publicarlos tal cual produce un curso sin objetivos ni evaluación.
- **Leer los repositorios fuente en vivo al mostrar el curso.** Rechazado: acopla la disponibilidad del LMS a GitHub y
  a una rama que cambia; rompe la inmutabilidad de lo publicado.
- **Parser AsciiDoc propio en Kotlin.** Rechazado: reimplementa Asciidoctor y diverge de cómo se construyen los manuales.
- **AsciidoctorJ en el proceso de la JVM.** Posible; diferido. El contenedor aísla una carga pesada y no confiable y
  usa la misma versión que los manuales. Si el coste de arrancar el contenedor resulta un problema medido, un ADR nuevo
  lo revisa.

## Consecuencias

- Cada lección importada tiene procedencia trazable hasta la ruta, el ancla y el commit de origen.
- La parte cara (diseño pedagógico) sigue siendo humana; el importador ahorra la extracción y la validación, no el
  criterio experto.
- Requiere Docker (o un ejecutor de contenedores) en el entorno del trabajo de importación.
- Copiar el texto de los manuales para un curso real depende de su licencia ([ADR-0009](0009-licencia-diferida.md)).
- A verificar en implementación (F4): el formato intermedio de la extracción (AST de Asciidoctor o HTML anotado), cómo
  se resuelven los `<<id>>` que cruzan lecciones y el soporte de los atributos de `atributos.adoc` / `attributes.adoc`.
