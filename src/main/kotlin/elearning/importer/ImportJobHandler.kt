package elearning.importer

import elearning.common.listAll
import elearning.importer.ImportService.Companion.COURSE
import elearning.importer.ImportService.Companion.LESSON
import elearning.importer.ImportService.Companion.MAPPER
import elearning.importer.ImportService.Companion.UNIT
import elearning.importer.ImportService.Companion.VERSION
import elearning.jobs.Job
import elearning.jobs.JobHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.module.kotlin.readValue
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import java.util.UUID

// la importación como trabajo de la cola (ADR-0010 etapas 1 y 2, ADR-0018 §6): lee la fuente, arma el grafo y propone un
// borrador de versión del curso con una unidad por capítulo y una lección por sección, cada lección con su traza
// repo@sha:ruta#ancla y sin el cuerpo del manual. nunca publica: la versión nace BORRADOR y la sigue quien la pidió.
//
// corre como la plataforma de su organización (no hay petición ni token en la cola), con el autor puesto en autor_id y
// `import:<trabajo>` en la auditoría; los permisos de quien la pidió se comprobaron al encolar. todo el borrador va en una
// transacción: un reintento nunca encuentra medio borrador
@Component
class ImportJobHandler(
    private val fetcher: SourceFetcher,
    private val records: RecordService,
    private val transactions: TransactionalOperator,
    private val properties: ImporterProperties,
) : JobHandler {
    override val type = ImportService.JOB_TYPE

    override suspend fun handle(job: Job): String? {
        val payload = MAPPER.readValue<ImportPayload>(job.payload)
        val outcome =
            try {
                import(job, payload)
            } catch (e: ImportFailure) {
                ImportOutcome(failure = e.message)
            } catch (e: SourceFetchException) {
                // pasajero: que la cola lo reintente. lo demás no cambia por reintentar
                if (e.reason == SourceFetchException.Reason.UNAVAILABLE) throw e
                ImportOutcome(failure = e.message)
            }
        return MAPPER.writeValueAsString(outcome)
    }

    private suspend fun import(
        job: Job,
        payload: ImportPayload,
    ): ImportOutcome {
        val root = SourceRef.parse(payload.source)
        val collected = SourceCollector(fetcher, properties.maxFiles).collect(root)
        if (collected.analysis.files.isEmpty()) throw ImportFailure("$root could not be read")
        val units = DraftProposal.of(root, collected.analysis)
        val versionId =
            records.asPlatform(job.organizationId, "import:${job.id}") {
                val course =
                    try {
                        records.get(COURSE, UUID.fromString(payload.courseId))
                    } catch (_: NotFoundException) {
                        throw ImportFailure("the course ${payload.courseId} no longer exists")
                    }
                transactions.executeAndAwait { draft(course, payload.requester, root, units) }
            }
        return ImportOutcome(versionId = versionId, report = report(root, collected, units))
    }

    private suspend fun draft(
        course: RecordResponse,
        author: String,
        root: SourceRef,
        units: List<ProposedUnit>,
    ): String {
        val versions = records.listAll(VERSION, mapOf(COURSE to course.id))
        // la duración y la modalidad de la última versión, que el creador ajusta; sin versiones, las mínimas válidas
        val latest = versions.maxByOrNull { number(it) }
        val inherited = latest?.attributes.orEmpty().filter { (k, v) -> k in INHERITED && v != null && v !is Map<*, *> }
        val attributes =
            DEFAULTS + inherited +
                mapOf(
                    COURSE to course.id,
                    "numero" to (versions.maxOfOrNull { number(it) } ?: 0) + 1,
                    "autor_id" to author,
                    "estado" to "BORRADOR",
                    "fuentes" to root.toString(),
                )
        val version = records.create(VERSION, RecordRequest(attributes))
        units.forEachIndexed { u, unit ->
            val created = records.create(UNIT, RecordRequest(mapOf(VERSION to version.id, ORDER to u + 1, TITLE to unit.title)))
            unit.lessons.forEachIndexed { l, lesson ->
                records.create(
                    LESSON,
                    RecordRequest(
                        mapOf(UNIT to created.id, ORDER to l + 1, TITLE to lesson.title, "tipo" to "LECTURA", "referencia_fuente" to lesson.trace),
                    ),
                )
            }
        }
        return version.id
    }

    private fun report(
        root: SourceRef,
        collected: CollectedSource,
        units: List<ProposedUnit>,
    ): ImportReport {
        val analysis = collected.analysis
        val problems =
            analysis.defects.map {
                ImportProblem(it.kind.name, DraftProposal.origin(root, it.location.path), it.location.line, it.detail.take(MAX_DETAIL))
            } + collected.oversized.map { ImportProblem(TOO_LARGE, DraftProposal.origin(root, it), 0, it.take(MAX_DETAIL)) }
        return ImportReport(
            source = root.toString(),
            files = analysis.files.size,
            includes = analysis.includes.size,
            sections = analysis.sections.size,
            images = analysis.images.size,
            units = units.size,
            lessons = units.sumOf { it.lessons.size },
            problemCount = problems.size,
            problems = problems.take(MAX_PROBLEMS),
        )
    }

    private fun number(version: RecordResponse) = (version.attributes["numero"] as? Number)?.toInt() ?: 0

    private companion object {
        const val ORDER = "orden"
        const val TITLE = "titulo"
        const val TOO_LARGE = "FILE_TOO_LARGE"
        const val MAX_DETAIL = 500
        const val MAX_PROBLEMS = 500
        val INHERITED = setOf("modalidad", "duracion", "semanas")
        val DEFAULTS = mapOf("modalidad" to "ASINCRONA", "duracion" to "SEMANAS", "semanas" to 1, "sistema_credito" to "NINGUNO")
    }
}
