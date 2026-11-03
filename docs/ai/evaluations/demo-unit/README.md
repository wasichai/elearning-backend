# Unidad de demostración con un proveedor real (E38.1)

Guía para documentar la ejecución **manual** de la unidad de demostración de extremo a extremo de la Fase 5
([#300](https://github.com/wasichai/elearning-backend/issues/300)) con Anthropic y otra con OpenAI: brief → análisis →
diseño → borrador → validación → corrección → revisión → aprobación por una persona distinta de la autora → publicación →
la unidad en el aula de un estudiante matriculado. Es el mismo recorrido que prueban, sin claves, `DemoUnitApiTest` (doble
determinista) y `DemoUnitAnthropicApiTest`/`DemoUnitOpenAiApiTest` (Embabel contra el doble HTTP); aquí lo recorre la
aplicación contra la API real de cada proveedor, que cuesta dinero y no es determinista, así que no está en CI.

## Estado

| Proveedor | Fecha | Commit | Modelos | Resultado | Coste |
| --------- | ----- | ------ | ------- | --------- | ----- |
| Anthropic | —     | —      | —       | pendiente | —     |
| OpenAI    | —     | —      | —       | pendiente | —     |

**Pendiente: ejecución con credenciales.** Ninguna de las dos se ha ejecutado: el entorno donde se entregó #300 no tiene
claves de proveedor. No hay cifras ni salidas inventadas en este directorio; quien tenga claves sigue el procedimiento,
copia la plantilla a `ejecuciones/<AAAA-MM-DD>-<proveedor>.md`, la rellena con lo que de verdad salió y actualiza la tabla.
Hasta entonces el segundo criterio de #300 queda sin cumplir.

## Fuente

`fuente/` es una fuente propia y sintética (un manual con un `include::` y dos secciones con lista ordenada), en este
repositorio para que la ejecución sea reproducible: se lee en un commit exacto de `main` por el importador, como cualquier
fuente autorizada. Sin licencia de los manuales SRTM y SGSPE no se usa su texto ([licencias](../../../legal/licensing.md)).

## Procedimiento

Con el entorno de [getting-started](../../../development/getting-started.md) (PostgreSQL, `.env` con `WASICHAI_JWT_SECRET`,
`GITHUB_ACTOR`/`GITHUB_TOKEN` para compilar) y `jq`. Una ejecución por proveedor, cada una con la base vacía
(`docker compose down -v && docker compose up -d --wait`) para que el coste del curso sea solo el suyo.

1. Arrancar con **una sola** clave (la otra vacía) y la fuente autorizada. Las tarifas son las vigentes del proveedor el
   día de la ejecución, por millón de tokens, para los modelos de `lms.ai.models` (`extraction`, `design`, `draft`,
   `consistency`); sin tarifa el coste queda `null`. El presupuesto acota lo que puede gastar el curso.

   ```bash
   export SHA=$(git rev-parse origin/main)          # el commit cuya fuente/ se lee; queda en la ejecución
   export ANTHROPIC_API_KEY=...  OPENAI_API_KEY=     # o al revés para OpenAI
   export LMS_IMPORTER_ALLOWED_SOURCES=wasichai/elearning-backend
   export LMS_AI_COURSE_BUDGET=5 LMS_AI_BUDGET_CURRENCY=USD
   export LMS_AI_RATES_0_PROVIDER=anthropic LMS_AI_RATES_0_MODEL=claude-haiku-5-5 \
          LMS_AI_RATES_0_INPUT_PER_MILLION=... LMS_AI_RATES_0_OUTPUT_PER_MILLION=... \
          LMS_AI_RATES_0_CURRENCY=USD LMS_AI_RATES_0_SINCE=$(date +%F)
   # …una entrada LMS_AI_RATES_<n>_* por modelo (claude-sonnet-5-5; o gpt-6-luna y gpt-6.1-sol)
   ./gradlew bootRun --args='--spring.profiles.active=dev'
   ```

   El registro de arranque de `AiStatus` dice qué proveedor quedó: debe ser el de la clave, no «doble determinista».

2. En otra terminal, el modelo y la demo (usuarios `creador`, `revisor` y `estudiante` de la organización `demo`):

   ```bash
   export WASICHAI_EMAIL=admin@wasichai.local WASICHAI_PASSWORD=admin WASICHAI_DEMO_PASSWORD='la-de-desarrollo'
   python3 model/apply.py --demo
   API=http://localhost:8090
   login() { curl -s $API/api/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"$2\"}" | jq -r .token; }
   ADMIN=$(login admin@wasichai.local admin)
   AUTHOR=$(login creador@demo.wasichai-learning.test "$WASICHAI_DEMO_PASSWORD")
   REVIEWER=$(login revisor@demo.wasichai-learning.test "$WASICHAI_DEMO_PASSWORD")
   STUDENT=$(login estudiante@demo.wasichai-learning.test "$WASICHAI_DEMO_PASSWORD")
   call() { local t=$1 m=$2 p=$3; shift 3; curl -s -X "$m" "$API$p" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' "$@"; }
   # espera a que un trabajo termine y devuelve su executionId
   wait_job() { while :; do j=$(call "$1" GET "$2"); s=$(jq -r .status <<<"$j"); [ "$s" = QUEUED ] || [ "$s" = RUNNING ] || break; sleep 5; done; echo "$j" >&2; jq -r .executionId <<<"$j"; }
   ```

3. Brief, análisis, diseño y borrador, como la autora. Cada paso parte de la ejecución del anterior:

   ```bash
   COURSE=$(call $AUTHOR POST /api/objects/curso/records -d '{"attributes":{"codigo":"DEMO-F5","slug":"demo-f5","titulo":"Administración de anuncios"}}' | jq -r .id)
   SRC="wasichai/elearning-backend@$SHA:docs/ai/evaluations/demo-unit/fuente/manual.adoc"
   BRIEF=$(call $AUTHOR POST /api/objects/brief/records -d "{\"attributes\":{\"curso\":\"$COURSE\",\"tematica\":\"Administración de anuncios\",
     \"publico\":\"Personal administrativo\",\"objetivos\":\"Publicar un anuncio\nRetirarlo\",\"duracion_horas\":6,\"idioma\":\"es\",
     \"nivel\":\"INICIAL\",\"expertos\":\"Responsable de comunicación\",\"fuentes\":\"$SRC\",\"estado\":\"LISTO\"}}" | jq -r .id)
   J=$(call $AUTHOR POST /api/lms/ai/source-analysis -d "{\"briefId\":\"$BRIEF\"}" | jq -r .id); ANALYSIS=$(wait_job $AUTHOR /api/lms/ai/jobs/$J)
   J=$(call $AUTHOR POST /api/lms/ai/course-design -d "{\"executionId\":\"$ANALYSIS\"}" | jq -r .id); DESIGN=$(wait_job $AUTHOR /api/lms/ai/jobs/$J)
   J=$(call $AUTHOR POST /api/lms/ai/lesson-draft -d "{\"executionId\":\"$DESIGN\"}" | jq -r .id); DRAFT=$(wait_job $AUTHOR /api/lms/ai/jobs/$J)
   VERSION=$(call $AUTHOR GET /api/lms/ai/runs/$DRAFT | jq -r .output.versionId)
   ```

   Un paso que no termina en `DONE` es un resultado que se documenta tal cual (estado, `error`, `validations`), no se
   repite hasta que salga bien.

4. Validación (la lanza el revisor, nunca la autora), corrección y otra validación. El borrador de lecciones no escribe
   evaluaciones, así que la primera validación bloquea con `NO_ASSESSMENT`; la autora añade la evaluación en el estudio
   (una lección `EVALUACION` con su evaluación, una pregunta y sus opciones) y se valida otra vez:

   ```bash
   validate() { J=$(call $REVIEWER POST /api/lms/ai/validations -d "{\"versionId\":\"$VERSION\"}" | jq -r .id)
     wait_job $REVIEWER /api/lms/ai/validations/jobs/$J >/dev/null; call $REVIEWER GET "/api/lms/ai/validations?versionId=$VERSION"; }
   validate | tee validacion-1.json | jq '.report.blocking, .report.problemsBySeverity, .opinion.verdict'
   # … la corrección en el estudio (o por /api/objects/{leccion,evaluacion,pregunta,opcion}/records como la autora) …
   validate | tee validacion-2.json | jq '.report.blocking, .opinion.verdict'
   ```

5. Revisión, aprobación y publicación. Las lecturas generadas son versiones de recurso en `BORRADOR`: la autora las envía,
   el revisor las aprueba y quien publica las publica, como la versión del curso:

   ```bash
   call $AUTHOR POST /api/lms/studio/versions/$VERSION/submit -d '{}' | jq .status          # EN_REVISION
   for L in $(call $AUTHOR GET "/api/objects/unidad/records?version_curso=$VERSION&size=200" | jq -r '.content[].id' |
              xargs -I{} sh -c "curl -s -H 'Authorization: Bearer $AUTHOR' '$API/api/objects/leccion/records?unidad={}&size=200' | jq -r '.content[].id'"); do
     for RV in $(call $AUTHOR GET "/api/objects/recurso_leccion/records?leccion=$L" | jq -r '.content[].attributes.version_recurso'); do
       call $AUTHOR PATCH /api/objects/version_recurso/records/$RV -d '{"attributes":{"estado":"EN_REVISION"}}' >/dev/null
       call $REVIEWER PATCH /api/objects/version_recurso/records/$RV -d '{"attributes":{"estado":"APROBADA"}}' >/dev/null
       call $ADMIN PATCH /api/objects/version_recurso/records/$RV -d '{"attributes":{"estado":"PUBLICADA"}}' | jq .attributes.estado
     done
   done
   call $AUTHOR POST /api/lms/review/versions/$VERSION/decision -d '{"decision":"APROBAR"}' -o /dev/null -w '%{http_code}\n'  # 403
   call $REVIEWER POST /api/lms/review/versions/$VERSION/decision -d '{"decision":"APROBAR"}' | jq .status                     # APROBADA
   call $ADMIN POST /api/lms/review/versions/$VERSION/publish -d '{}' | jq .status                                            # PUBLICADA
   ```

6. Una edición con la inscripción abierta y el estudiante en el aula:

   ```bash
   TODAY=$(date +%F)
   call $ADMIN POST /api/objects/edicion_curso/records -d "{\"attributes\":{\"codigo\":\"DEMO-F5-A\",\"version_curso\":\"$VERSION\",
     \"estado\":\"INSCRIPCION_ABIERTA\",\"fecha_inicio\":\"$TODAY\",\"fecha_fin\":\"$(date -d +30days +%F)\",\"zona_horaria\":\"America/Lima\",
     \"inscripcion_desde\":\"$TODAY\",\"inscripcion_hasta\":\"$(date -d +5days +%F)\"}}" | jq .id
   ENROLLMENT=$(call $STUDENT POST /api/lms/enrollments -d '{"offering":"DEMO-F5-A"}' | jq -r .id)
   call $STUDENT GET /api/lms/classroom/enrollments/$ENROLLMENT | jq '.units[] | {title, lessons: [.lessons[].title]}'
   ```

7. Lo que se conserva: las ejecuciones del curso (`call $AUTHOR GET "/api/lms/ai/runs?courseId=$COURSE"` y cada
   `/api/lms/ai/runs/{id}`), los dos informes de validación y el aula del estudiante, en `ejecuciones/<AAAA-MM-DD>-<proveedor>/`
   (JSON tal cual: no llevan claves, `AgentExecutionLog` las tapa). La lectura pedagógica la hace una persona con la rúbrica
   de la plantilla; las comprobaciones automáticas las dice el propio informe.

## Plantilla de una ejecución

```markdown
# Unidad de demostración con <Anthropic|OpenAI> (<AAAA-MM-DD>)

- Commit de la aplicación y de `fuente/`: <sha>
- Proveedor y modelos por paso (de `GET /api/lms/ai/runs`): análisis <modelo>, diseño <modelo>, borrador <modelo>, opinión <modelo>
- Versiones de instrucciones y plantilla: <source-analysis@1, course-design@1, lesson-draft@1, consistency-review@1>
- Tarifas usadas (por millón de tokens, con su fecha): <…>
- Quién ejecutó y quién hizo de revisor: <personas>

| Paso               | Estado | Tokens entrada/salida | Coste | Duración | Descartes y avisos (`validations`) |
| ------------------ | ------ | --------------------- | ----- | -------- | ---------------------------------- |
| Análisis           |        |                       |       |          |                                    |
| Diseño             |        |                       |       |          |                                    |
| Borrador           |        |                       |       |          |                                    |
| Validación 1       |        | —                     | —     |          | bloqueantes:                       |
| Opinión 1          |        |                       |       |          | veredicto:                         |
| Validación 2       |        | —                     | —     |          | bloqueantes:                       |
| Opinión 2          |        |                       |       |          | veredicto:                         |
| Revisión y publica |        | —                     | —     |          | intervenciones en la ejecución:    |

- Coste total del curso (suma de `cost`): <…>
- Correcciones humanas antes de revisión (qué y por qué): <…>
- Enlaces marcados para revisión (`linksToReview`) y qué se hizo con ellos: <…>
- El aula del estudiante muestra las unidades y lecciones publicadas: <sí/no, con el JSON>
- Lectura humana (rúbrica: fidelidad a la fuente, objetivos medibles, evaluación alineada, claridad): <…>
- Incidencias (errores del proveedor, `INVALID_OUTPUT`, reintentos): <…>
```
