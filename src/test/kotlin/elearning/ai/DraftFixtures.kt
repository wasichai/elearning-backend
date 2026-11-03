package elearning.ai

import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.runBlocking
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import java.util.UUID

// el borrador de las pruebas de la validación (E34.1) y de la opinión de consistencia (E34.2), puesto por la plataforma: una
// versión BORRADOR con una unidad, una lección de evaluación y una pregunta con sus opciones. Que es generado lo dice una
// ejecución LESSON_DRAFT con la versión en salida.versionId, como la dejará #283: aquí se siembra a mano
class DraftFixtures(
    private val records: RecordService,
    private val log: AgentExecutionLog,
) {
    private val sha = "c".repeat(40)
    private val trace = "wasichai/manual@$sha:capitulo.adoc#intro"

    class Draft(
        val course: String,
        val version: String,
        val lesson: String,
    )

    // un borrador de un curso nuevo (code: su código, único) escrito por authorId, como lo dejaría la plataforma. defective: un
    // defecto por categoría; generated: con el vínculo de un paso de borrador, hecho por generator
    fun draft(
        code: String,
        authorId: String,
        defective: Boolean = false,
        generated: Boolean = true,
        generator: Generator = Generator.DETERMINISTIC,
    ): Draft {
        val draft =
            runBlocking {
                records.asPlatform(ClassroomApiTestSupport.ORG) {
                    val course =
                        records.create(
                            "curso",
                            RecordRequest(
                                mapOf(
                                    "codigo" to code,
                                    "slug" to code.lowercase(),
                                    "titulo" to "Curso generado",
                                    "idioma" to "es",
                                    "nivel" to "INICIAL",
                                ),
                            ),
                        )
                    val outcomes =
                        if (defective) {
                            emptyMap()
                        } else {
                            mapOf("objetivos" to "Usar el padrón", "resultados_aprendizaje" to "Consulta el padrón", "sistema_evaluacion" to "Una evaluación")
                        }
                    val version =
                        records.create(
                            "version_curso",
                            RecordRequest(
                                mapOf(
                                    "curso" to course.id,
                                    "numero" to 1,
                                    "estado" to "BORRADOR",
                                    "modalidad" to "ASINCRONA",
                                    "semanas" to 1,
                                    "sistema_credito" to "NINGUNO",
                                    "contenido_ilustrativo" to true,
                                    "autor_id" to authorId,
                                    "fuentes" to "wasichai/manual@$sha:manual.adoc",
                                ) + outcomes,
                            ),
                        )
                    val unit =
                        records.create(
                            "unidad",
                            RecordRequest(
                                mapOf(
                                    "version_curso" to version.id,
                                    "orden" to 1,
                                    "titulo" to "Unidad 1",
                                    "objetivos" to "Explicar el padrón",
                                    "referencia_fuente" to trace,
                                ) +
                                    (if (defective) emptyMap() else mapOf("horas_estimadas" to 2)),
                            ),
                        )
                    val summary =
                        if (defective) {
                            "= Padrón\n\n=== Detalle\n\nimage::flujo.png[]\n\nVer http://docs.example.org/guia"
                        } else {
                            "== Padrón\n\n=== Detalle\n\nimage::flujo.png[Flujo de la consulta]\n\nVer https://docs.example.org/guia"
                        }
                    val lesson =
                        records.create(
                            "leccion",
                            RecordRequest(
                                mapOf(
                                    "unidad" to unit.id,
                                    "orden" to 1,
                                    "titulo" to "Lección 1",
                                    "tipo" to "EVALUACION",
                                    "duracion_minutos" to 30,
                                    "resumen" to summary,
                                    "referencia_fuente" to trace,
                                ),
                            ),
                        )
                    if (defective) {
                        records.create(
                            "leccion",
                            RecordRequest(
                                mapOf(
                                    "unidad" to unit.id,
                                    "orden" to 2,
                                    "titulo" to "Lección 2",
                                    "tipo" to "LECTURA",
                                    "duracion_minutos" to 30,
                                    "resumen" to summary,
                                    "referencia_fuente" to "no es una traza",
                                ),
                            ),
                        )
                    }
                    val evaluation =
                        records.create(
                            "evaluacion",
                            RecordRequest(
                                mapOf(
                                    "leccion" to lesson.id,
                                    "titulo" to "Evaluación",
                                    "intentos_max" to 2,
                                    "nota_minima" to 60,
                                    "mostrar_soluciones" to "AL_ENVIAR",
                                ),
                            ),
                        )
                    val question =
                        records.create(
                            "pregunta",
                            RecordRequest(
                                mapOf(
                                    "evaluacion" to evaluation.id,
                                    "orden" to 1,
                                    "tipo" to "UNICA",
                                    "enunciado" to "¿Qué es el padrón?",
                                    "puntos" to 2,
                                ),
                            ),
                        )
                    records.create("opcion", RecordRequest(mapOf("pregunta" to question.id, "orden" to 1, "texto" to "Un registro", "correcta" to !defective)))
                    records.create("opcion", RecordRequest(mapOf("pregunta" to question.id, "orden" to 2, "texto" to "Otra cosa", "correcta" to false)))
                    Draft(course.id, version.id, lesson.id)
                }
            }
        if (generated) seedGenerated(draft, UUID.fromString(authorId), generator)
        return draft
    }

    // el vínculo que dejará el paso de borrador (#283): una ejecución LESSON_DRAFT que nombra la versión en su salida. Devuelve
    // la ejecución, a la que van las intervenciones de las personas (E34.3)
    fun seedGenerated(
        draft: Draft,
        author: UUID,
        generator: Generator = Generator.DETERMINISTIC,
    ): UUID =
        runBlocking {
            val run =
                log.start(
                    NewAgentExecution(
                        organizationId = ClassroomApiTestSupport.ORG,
                        jobId = UUID.randomUUID(),
                        step = AgentStep.LESSON_DRAFT,
                        requestedBy = author,
                        courseId = UUID.fromString(draft.course),
                        objective = "Redactar el borrador",
                        inputs = mapOf("brief" to "padrón"),
                        generator = generator,
                        model = if (generator == Generator.MODEL) ModelChoice("anthropic", "claude-test-model", ModelSettings(maxTokens = 1000)) else null,
                        promptVersion = "test",
                        templateVersion = "test",
                        idempotencyKey = UUID.randomUUID().toString(),
                        attempt = 1,
                    ),
                )
            log.finish(ClassroomApiTestSupport.ORG, run, AgentExecutionOutcome(AgentExecutionStatus.SUCCEEDED, output = mapOf("versionId" to draft.version)))
            run
        }
}
