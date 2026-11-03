package elearning.ai

import elearning.common.listAll
import elearning.course.CourseDuration
import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.PermanentJobFailure
import elearning.media.ArtifactFormat
import elearning.media.DocumentRenderer
import elearning.media.GeneratedHtml
import elearning.media.RenderException
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.files.FileDescriptor
import wasichai.files.FileService
import wasichai.files.FileUpload
import java.util.UUID

// el trabajo ai.lesson_draft: lee el diseño por su ejecución y vuelve a leer las fuentes del brief (el mismo commit exacto),
// llama al redactor del modelo encolado (o al doble sin modelo), comprueba la salida por código y, con AiStepRunner.runWriting,
// deja el borrador como version_curso BORRADOR (LessonDraftWriter) en la transacción que cierra la ejecución con
// output.versionId (docs/ai §13: es lo que la marca como generada). Lo demás (permisos al ejecutar, idempotencia, reintentos,
// cancelación, presupuesto y AgentRun) es de AiStepRunner. Una salida inválida queda INVALID_OUTPUT, sin versión
@Component
class LessonDraftJobHandler(
    private val runner: AiStepRunner,
    private val store: AgentExecutionStore,
    private val reader: SourceMaterialReader,
    private val model: ModelLessonDrafter,
    private val double: DeterministicLessonDrafter,
    private val writer: LessonDraftWriter,
) : JobHandler {
    override val type = AiJobs.typeOf(AgentStep.LESSON_DRAFT)

    override suspend fun handle(job: Job): String =
        runner.runWriting(
            job,
            read = { request ->
                val inputs = LessonDraftInputs.of(request.inputs)
                val design = design(job, inputs)
                val sources = reader.read(inputs.sources)
                AiStepInput(LessonDraftMaterial.of(design, sources.material), sources.sources)
            },
            generator = { prompt, material ->
                val drafter: LessonDrafter = if (prompt.model == null) double else model
                val reply = drafter.draft(prompt, material)
                LessonDraftCheck.checked(reply.value, material, LessonDraftInputs.of(prompt.inputs).language, reply.usage)
            },
            writer = writer,
        )

    // el diseño del que parte, que al encolar ya era SUCCEEDED: una ejecución terminada no cambia, así que si falta o no lo es,
    // reintentar no lo arregla
    private suspend fun design(
        job: Job,
        inputs: LessonDraftInputs,
    ): DesignedCourse {
        val id = UUID.fromString(inputs.designExecutionId)
        val design = store.find(job.organizationId, id)
        val output = design?.output
        if (design == null || design.step != AgentStep.COURSE_DESIGN || design.status != AgentExecutionStatus.SUCCEEDED || output == null) {
            throw PermanentJobFailure("The course design $id is not a succeeded execution")
        }
        return AiJobs.MAPPER.treeToValue(output, DesignedCourse::class.java)
    }
}

// los archivos subidos de una lección: su fuente AsciiDoc y su HTML saneado
data class StagedLesson(
    val source: FileDescriptor,
    val html: FileDescriptor,
)

// escribe el borrador comprobado como la plataforma, a nombre de quien lo pidió (autor_id), como la importación (flujo de F4,
// ADR-0018): una version_curso BORRADOR con el número siguiente del curso, una unidad por unidad del diseño y, por lección, su
// leccion y su recurso HTML: la fuente canónica AsciiDoc (fuente_recurso, RF-CON-03) y una version_recurso BORRADOR con el HTML
// que genera DocumentRenderer, saneado con la lista blanca de ADR-0022 (GeneratedHtml) antes de guardarse, enlazada a la
// lección (recurso_leccion); detrás, por evaluación del diseño, su lección EVALUACION con su evaluacion y sus preguntas. Nada
// cambia de estado: aprobar y publicar son de personas (EditorialWorkflowGuard). La plantilla queda en plantilla_ia y los
// enlaces que no están en las fuentes, en referencias de la versión del recurso, además de en el informe de la ejecución.
//
// Los archivos se generan y se suben antes (prepare, fuera de la transacción: el render bloquea); lo que quede sin adjuntar lo
// borra la limpieza de wasichai-files. Todo lo demás va en la transacción que cierra la ejecución y el trabajo
@Component
class LessonDraftWriter(
    private val records: RecordService,
    private val files: FileService,
    private val renderer: DocumentRenderer,
) : AiDraftWriter<List<List<StagedLesson>>> {
    // dentro de asPlatform: solo la escritura de este mismo bloque puede adjuntar lo subido (ADR-068 de Wasichai)
    override suspend fun <T> scope(
        job: Job,
        block: suspend () -> T,
    ): T = records.asPlatform(job.organizationId, "ai:${job.id}") { block() }

    override suspend fun prepare(
        request: AiStepPayload,
        reply: AiReply,
    ): List<List<StagedLesson>> {
        val output = reply.output as LessonDraftOutput
        return output.units.mapIndexed { u, unit ->
            unit.lessons.mapIndexed { l, lesson ->
                val name = "leccion-${u + 1}-${l + 1}"
                val source = lesson.asciidoc.toByteArray(Charsets.UTF_8)
                val html =
                    try {
                        GeneratedHtml.sanitize(renderer.render(source, ArtifactFormat.HTML).bytes)
                    } catch (e: RenderException) {
                        if (e.permanent) throw PermanentJobFailure(e.message ?: "The lesson could not be rendered", e)
                        throw e
                    }
                StagedLesson(
                    source = stage(SOURCE, "$name.adoc", TEXT, source),
                    html = stage(RESOURCE_VERSION, "$name.html", ArtifactFormat.HTML.contentType, html),
                )
            }
        }
    }

    override suspend fun write(
        request: AiStepPayload,
        reply: AiReply,
        prepared: List<List<StagedLesson>>,
    ): Any {
        val output = reply.output as LessonDraftOutput
        val report = reply.validations as LessonDraftReport
        val inputs = LessonDraftInputs.of(request.inputs)
        val course =
            try {
                records.get(COURSE, request.courseId)
            } catch (_: NotFoundException) {
                throw PermanentJobFailure("The course ${request.courseId} no longer exists")
            }
        val versions = records.listAll(VERSION, mapOf(COURSE to course.id))
        val number = (versions.maxOfOrNull { number(it) } ?: 0) + 1
        val modality =
            versions
                .maxByOrNull { number(it) }
                ?.attributes
                ?.get(MODALITY)
                ?.toString() ?: ASYNCHRONOUS
        val weekly = output.load.weekly
        val version =
            records.create(
                VERSION,
                RecordRequest(
                    mapOf(
                        COURSE to course.id,
                        "numero" to number,
                        "autor_id" to request.requester.toString(),
                        STATE to DRAFT,
                        MODALITY to modality,
                        "duracion" to durationOf(output.load.weeks),
                        "semanas" to output.load.weeks,
                        "horas_teoria_semana" to plainHours(weekly.theory).toPlainString(),
                        "horas_practica_semana" to plainHours(weekly.practice).toPlainString(),
                        "horas_supervisadas_semana" to plainHours(weekly.supervised).toPlainString(),
                        "horas_autonomas_semana" to plainHours(weekly.autonomous).toPlainString(),
                        "sistema_credito" to "NINGUNO",
                        "resultados_aprendizaje" to output.outcomes.joinToString("\n"),
                        "sistema_evaluacion" to output.assessmentSystem.joinToString("\n"),
                        "fuentes" to
                            request.sources
                                .map { it.ref }
                                .distinct()
                                .joinToString("\n"),
                        TEMPLATE to output.template,
                    ),
                ),
            )
        output.units.forEachIndexed { u, unit ->
            val created =
                records.create(
                    UNIT,
                    RecordRequest(
                        mapOf(
                            VERSION to version.id,
                            ORDER to u + 1,
                            TITLE to unit.title,
                            "descripcion" to unit.summary.ifBlank { null },
                            "objetivos" to unit.objectives.joinToString("\n").ifBlank { null },
                            "horas_estimadas" to weekly.total().takeIf { it.signum() > 0 }?.toPlainString(),
                            SOURCE_REF to (unit.lessons.firstNotNullOfOrNull { it.source } ?: unit.assessments.firstNotNullOfOrNull { it.source }),
                        ),
                    ),
                )
            unit.lessons.forEachIndexed { l, lesson ->
                val at = "units[$u].lessons[$l]"
                val staged = prepared[u][l]
                val lessonRecord =
                    records.create(
                        LESSON,
                        RecordRequest(
                            mapOf(
                                UNIT to created.id,
                                ORDER to l + 1,
                                TITLE to lesson.title,
                                "tipo" to "LECTURA",
                                "duracion_minutos" to lesson.durationMinutes,
                                "resumen" to lesson.summary.ifBlank { null },
                                "objetivo" to lesson.objective,
                                SOURCE_REF to lesson.source,
                            ),
                        ),
                    )
                val resource =
                    records.create(
                        RESOURCE,
                        RecordRequest(
                            mapOf(
                                "codigo" to "ia-${version.id}-${u + 1}-${l + 1}",
                                TITLE to lesson.title,
                                "descripcion" to lesson.summary.ifBlank { null },
                                "tipo" to "HTML",
                                "idioma" to (inputs.language?.takeIf { it.isNotBlank() } ?: "es"),
                            ),
                        ),
                    )
                records.create(SOURCE, RecordRequest(mapOf(RESOURCE to resource.id, "numero" to 1, FILE to staged.source.id.toString())))
                val resourceVersion =
                    records.create(
                        RESOURCE_VERSION,
                        RecordRequest(
                            mapOf(
                                RESOURCE to resource.id,
                                "numero" to 1,
                                "autor_id" to request.requester.toString(),
                                STATE to DRAFT,
                                FILE to staged.html.id.toString(),
                                "origen" to lesson.source,
                                "referencias" to references(lesson, report.linksToReview.filter { it.at == at }),
                            ),
                        ),
                    )
                records.create(RESOURCE_LINK, RecordRequest(mapOf(LESSON to lessonRecord.id, RESOURCE_VERSION to resourceVersion.id, ORDER to 1)))
            }
            unit.assessments.forEachIndexed { a, assessment -> assessment(version.id, created.id, unit.lessons.size + a + 1, "${u + 1}-${a + 1}", assessment) }
        }
        return output.copy(versionId = version.id, versionNumber = number)
    }

    // una evaluación del diseño (#367): su lección EVALUACION detrás de las lecturas de la unidad, alineada por su objetivo con
    // los resultados que comprueba, su evaluacion con valores prudentes (un intento, 60 %, sin soluciones, el peso por defecto:
    // lo ajusta quien prepara el curso) y sus preguntas con sus opciones y sus respuestas aceptadas, la clave que solo leen quienes preparan,
    // revisan o corrigen (ADR-0019). Todo BORRADOR, con su versión
    private suspend fun assessment(
        versionId: String,
        unitId: String,
        order: Int,
        code: String,
        assessment: DraftedAssessment,
    ) {
        val lesson =
            records.create(
                LESSON,
                RecordRequest(
                    mapOf(
                        UNIT to unitId,
                        ORDER to order,
                        TITLE to assessment.title,
                        "tipo" to "EVALUACION",
                        "duracion_minutos" to assessment.durationMinutes,
                        "resumen" to assessment.summary.ifBlank { null },
                        "objetivo" to assessment.objective,
                        SOURCE_REF to assessment.source,
                    ),
                ),
            )
        val evaluation =
            records.create(
                EVALUATION,
                RecordRequest(
                    mapOf(
                        LESSON to lesson.id,
                        "codigo" to "ia-$versionId-e$code",
                        TITLE to assessment.title,
                        "instrucciones" to assessment.instructions.ifBlank { null },
                        "intentos_max" to 1,
                        "nota_minima" to PASSING_PERCENT,
                        "mostrar_soluciones" to "NUNCA",
                    ),
                ),
            )
        assessment.questions.forEachIndexed { q, question ->
            val created =
                records.create(
                    QUESTION,
                    RecordRequest(
                        mapOf(
                            EVALUATION to evaluation.id,
                            ORDER to q + 1,
                            "tipo" to question.type,
                            "enunciado" to question.statement,
                            "puntos" to question.points.toPlainString(),
                            "respuestas_aceptadas" to question.acceptedAnswers.joinToString("\n").ifEmpty { null },
                        ),
                    ),
                )
            question.options.forEachIndexed { o, option ->
                records.create(OPTION, RecordRequest(mapOf(QUESTION to created.id, ORDER to o + 1, "texto" to option.text, "correcta" to option.correct)))
            }
        }
    }

    private suspend fun stage(
        objectName: String,
        name: String,
        contentType: String,
        bytes: ByteArray,
    ): FileDescriptor {
        val content: Flux<DataBuffer> = Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))
        return files.stage(objectName, FILE, FileUpload(name, contentType, content))
    }

    private fun number(version: RecordResponse) = (version.attributes["numero"] as? Number)?.toInt() ?: 0

    companion object {
        const val COURSE = "curso"
        const val VERSION = "version_curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val RESOURCE = "recurso"
        const val SOURCE = "fuente_recurso"
        const val RESOURCE_VERSION = "version_recurso"
        const val RESOURCE_LINK = "recurso_leccion"
        const val EVALUATION = "evaluacion"
        const val QUESTION = "pregunta"
        const val OPTION = "opcion"

        // la plantilla y su versión con que se juntó el borrador (la version_plantilla de su ejecución)
        const val TEMPLATE = "plantilla_ia"
        const val STATE = "estado"
        const val DRAFT = "BORRADOR"
        private const val MODALITY = "modalidad"
        private const val ASYNCHRONOUS = "ASINCRONA"
        private const val ORDER = "orden"
        private const val TITLE = "titulo"
        private const val SOURCE_REF = "referencia_fuente"
        private const val FILE = "archivo"
        private const val TEXT = "text/plain"
        private const val PASSING_PERCENT = 60

        // RF-ACA-05: hasta 8 semanas, por semanas; de 12 a 26, un semestre. Lo demás no es una duración válida (null)
        fun durationOf(weeks: Int): String? = listOf(CourseDuration.WEEKS, CourseDuration.SEMESTER).firstOrNull { CourseDuration.problem(it, weeks) == null }

        // la procedencia de la lección y los enlaces que quien revisa tiene que mirar, en el texto de la versión del recurso
        fun references(
            lesson: DraftedLesson,
            links: List<LinkToReview>,
        ): String? {
            val lines = listOfNotNull(lesson.source) + links.map { "REVISAR (${it.reason}): ${it.url}" }
            return lines.joinToString("\n").ifEmpty { null }
        }
    }
}

data class LessonDraftRequest(
    // la ejecución SUCCEEDED del diseño pedagógico (el executionId de su trabajo)
    val executionId: UUID? = null,
)

// encolar el borrador de un diseño SUCCEEDED (docs/api/lms.md). Exige GENERAR (AiJobs) y ver el curso del diseño como quien
// llama (404, como su lectura); su brief tiene que seguir LISTO y sus semanas, ser una duración válida (409). Las fuentes de la
// ejecución son las del brief, que el trabajo vuelve a leer. Con proveedor, el modelo de borrador y su max_tokens; sin él, el
// doble
@Service
class LessonDraftService(
    private val permissions: AiPermissions,
    private val records: RecordService,
    private val store: AgentExecutionStore,
    private val jobs: AiJobs,
    private val models: AiStepModels,
) {
    suspend fun enqueue(request: LessonDraftRequest): AiJob {
        val user = permissions.requireToEnqueue(AiAction.GENERAR)
        val executionId = request.executionId ?: throw ValidationException("executionId is required", "executionId", "required")
        val design =
            store.find(user.organizationId, executionId)?.takeIf { it.step == AgentStep.COURSE_DESIGN }
                ?: throw NotFoundException("Course design $executionId not found")
        try {
            records.get(LessonDraftWriter.COURSE, design.courseId)
        } catch (_: NotFoundException) {
            throw NotFoundException("Course design $executionId not found")
        } catch (_: ForbiddenException) {
            throw NotFoundException("Course design $executionId not found")
        }
        val output = design.output
        if (design.status != AgentExecutionStatus.SUCCEEDED || output == null) {
            throw ConflictException("The course design $executionId is ${design.status}: only a succeeded design is drafted")
        }
        val designed = AiJobs.MAPPER.treeToValue(output, DesignedCourse::class.java)
        if (LessonDraftWriter.durationOf(designed.workload.weeks) == null) {
            throw ConflictException(
                "The course design $executionId has ${designed.workload.weeks} weeks: a course lasts 1 to 8 weeks or a semester of 12 to 26",
            )
        }
        val designInputs = CourseDesignInputs.of(design.inputs)
        val brief = records.get(BriefGuard.BRIEF, UUID.fromString(designInputs.briefId)).attributes
        if (brief[BriefGuard.STATE]?.toString() != BriefGuard.READY) {
            throw ConflictException("The brief ${designInputs.briefId} is not ${BriefGuard.READY}: only the design of a ready brief is drafted")
        }
        val analysis =
            store.find(user.organizationId, UUID.fromString(designInputs.analysisExecutionId))
                ?: throw ConflictException("The source analysis of the course design $executionId no longer exists")
        val inputs =
            LessonDraftInputs(
                designExecutionId = executionId.toString(),
                analysisExecutionId = designInputs.analysisExecutionId,
                briefId = designInputs.briefId,
                topic = designInputs.topic,
                language = designInputs.language,
                sources = SourceAnalysisInputs.of(analysis.inputs).sources,
            )
        val id =
            jobs.enqueue(
                AiStepRequest(
                    step = AgentStep.LESSON_DRAFT,
                    courseId = design.courseId,
                    objective = "Draft the lessons of the course «${designInputs.topic.take(MAX_TOPIC)}» from its design",
                    inputs = inputs,
                    sources = design.sources,
                    model = models.choice(MODEL_KEY),
                    promptVersion = LessonDraftPrompt.VERSION,
                    templateVersion = LessonDraftPrompt.TEMPLATE,
                ),
            )
        return jobs.find(id)
    }

    private companion object {
        // la clave del modelo y de max_tokens del paso en lms.ai.models y lms.ai.max-tokens
        const val MODEL_KEY = "draft"
        const val MAX_TOPIC = 200
    }
}

@RestController
@RequestMapping("/api/lms/ai/lesson-draft")
class LessonDraftController(
    private val drafts: LessonDraftService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun enqueue(
        @RequestBody body: LessonDraftRequest,
    ): AiJob = drafts.enqueue(body)
}
