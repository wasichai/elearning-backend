# ADR-0018: Fase 4 — estudio, revisión con decisiones, importador y generación en cola propia

**Estado**: aceptada · 2026-10-31

## Contexto

La Fase 4 ([roadmap](../product/roadmap.md#f4--estudio-editorial)) pide preparar, revisar, aprobar y publicar sin alterar lo
que ya se consume, e importar AsciiDoc por etapas (ADR-0010). Hechos que condicionan:

- `EditorialWorkflowGuard` ya impone transiciones, inmutabilidad y la no autoaprobación. Falta lo de alrededor: crear una
  versión nueva a partir de otra, la bandeja, las diferencias, los comentarios y las decisiones.
- `wasichai-workflow` no aporta aquí: las transiciones ya están en la guarda y duplicarlas daría dos fuentes de verdad.
- `wasichai-automation` responde a reglas sobre registros; no es una cola general de trabajos.
- `asciidoctor` es un proceso externo y bloqueante; la regla 2 de CLAUDE.md lo saca del hilo de WebFlux.
- Los manuales no tienen licencia: no se copia su texto (ADR-0015).

## Decisión

1. **Versión nueva por comando.** `POST /api/lms/studio/courses/{id}/versions` copia la última versión, o la indicada, a un
   borrador del autor: unidades, lecciones, evaluaciones y enlaces a recursos. La versión anterior y quien la cursa no se
   tocan.
2. **Revisión.** `version_curso.revisor_id` asigna revisor (RF-ROL-04); si está puesto, solo ese revisor aprueba o decide.
   Comentarios por sección en `comentario_revision`, ligados a su versión. Decisiones (`APROBAR`, `PEDIR_CAMBIOS`, `RECHAZAR`,
   siempre con motivo salvo aprobar) en `lms.decision_revision`, de solo inserción. `RECHAZAR` lleva a `RETIRADA`.
3. **Diferencias calculadas.** Se comparan dos versiones por identidad estable (orden de unidad y lección) y el resultado no
   se guarda.
4. **Cola propia.** `lms.trabajo` con `FOR UPDATE SKIP LOCKED`, un trabajo `@Scheduled` y reintentos acotados. Sirve al
   importador y a la generación. Un trabajo guarda su estado, su error y su resultado.
5. **Importador sin HTTP arbitrario.** Un puerto `SourceFetcher` lee de una **lista configurada de repositorios en un commit
   exacto** (`owner/repo@sha:ruta`) por la API de GitHub, a un host fijo y sin seguir redirecciones: ninguna URL viene del
   usuario, lo que corta el SSRF por construcción. El analizador del grafo `include::` es dominio puro en Kotlin y se prueba
   con fragmentos propios.
6. **La importación propone.** Crea un borrador con trazas `repo@sha:ruta#ancla` y un informe; **no copia el cuerpo** del
   manual, y nunca publica. El creador lo modifica.
7. **Generación.** Una `fuente_recurso` (AsciiDoc propio) produce artefactos HTML y PDF por un puerto `DocumentRenderer`,
   cuyo adaptador ejecuta `asciidoctor` en modo seguro con tiempo límite. Cada artefacto guarda la versión de la fuente, el
   generador y su hash (RF-CON-03). Publicar no sobrescribe: un artefacto nuevo es una versión nueva.
8. **Almacenamiento y tipos.** Lista de tipos permitidos por el tipo del recurso, comprobada en `SubmissionGuard` y en la guarda
   editorial sobre lo que `wasichai-files` detecta (RNF-SEG-07). MinIO en Compose y prueba con S3 compatible (RF-CON-02).

## Alternativas consideradas

- **Copiar el cuerpo de los manuales a la lección.** Rechazada mientras no tengan licencia.
- **Docker dentro del servidor para ejecutar Asciidoctor.** Rechazada: dar acceso a Docker a la aplicación es peor que el
  problema. La imagen de despliegue (F6) incluye el binario; sin él, el trabajo falla con un error claro.
- **Validar el texto de una URL libre contra SSRF.** Rechazada frente a no aceptar URLs.

## Consecuencias

- Los comentarios y las decisiones de una versión siguen ligados a ella tras una corrección, que crea otra versión o vuelve a
  borrador (RF-CON-06).
- El reparto de `revisor_id` lo hace quien publica. Sin revisor asignado, aprueba cualquier `REVISOR` distinto del autor, como
  hasta ahora.
- La prueba del importador con los manuales reales es una comprobación manual documentada, no una prueba del repositorio.
