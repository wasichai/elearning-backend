package elearning.ai

import elearning.common.listByIds
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordService
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

// los indicadores de la canalización de IA (RF-IA-10, docs/ai §10). Miden el PROCESO editorial, nunca la calidad
// pedagógica: un borrador aceptado sin cambios o barato no está por eso bien enseñado. Lo dicen la respuesta (kind = PROCESS
// en ella y en cada indicador) y la documentación. La calidad percibida (indicador 9) es de las valoraciones (F6) y no se
// sirve aquí

// PROCESS es el único tipo que sirve esta API, a propósito: no hay un QUALITY
enum class IndicatorKind { PROCESS, }

// AVAILABLE: calculado (value puede ser null si no hay muestra); UNAVAILABLE: el dato que lo alimenta no existe todavía
enum class IndicatorStatus { AVAILABLE, UNAVAILABLE }

// un cociente: value = numerator ÷ denominator, null sin denominador
data class RatioIndicator(
    val number: Int,
    val numerator: Long,
    val denominator: Long,
    val value: Double?,
    val kind: IndicatorKind = IndicatorKind.PROCESS,
    val status: IndicatorStatus = IndicatorStatus.AVAILABLE,
)

// una mediana en segundos sobre sample casos; excluded: los que no se pudieron medir (y por qué, en docs/ai §10)
data class DurationIndicator(
    val number: Int,
    val sample: Long,
    val excluded: Long,
    val medianSeconds: Double?,
    val kind: IndicatorKind = IndicatorKind.PROCESS,
    val status: IndicatorStatus = IndicatorStatus.AVAILABLE,
)

// problemas del informe de validación por borrador al entrar en revisión. drafts: los que tenían informe; withoutReport, los
// que entraron sin ninguno anterior
data class IssuesIndicator(
    val number: Int,
    val drafts: Long,
    val withoutReport: Long,
    val problems: Long,
    val blocking: Long,
    val warnings: Long,
    val perDraft: Double?,
    val kind: IndicatorKind = IndicatorKind.PROCESS,
    val status: IndicatorStatus = IndicatorStatus.AVAILABLE,
)

// el gasto de un curso (unitId null) o de una unidad en una moneda. currency null: ejecuciones sin tarifa, sin importe
data class CostLine(
    val courseId: UUID,
    val unitId: UUID?,
    val currency: String?,
    val amount: BigDecimal?,
    val runs: Long,
    val rates: List<RateUsed>,
)

data class CostIndicator(
    val number: Int,
    val perCourse: List<CostLine>,
    val perUnit: List<CostLine>,
    val kind: IndicatorKind = IndicatorKind.PROCESS,
    val status: IndicatorStatus = IndicatorStatus.AVAILABLE,
)

// un indicador definido que aún no se puede calcular: reason dice qué dato falta
data class UnavailableIndicator(
    val number: Int,
    val reason: String,
    val kind: IndicatorKind = IndicatorKind.PROCESS,
    val status: IndicatorStatus = IndicatorStatus.UNAVAILABLE,
)

data class PipelineIndicatorsView(
    val courseId: UUID?,
    val generator: Generator,
    val computedAt: Instant,
    val acceptedWithoutStructuralChange: RatioIndicator,
    val briefToValidatedDraft: DurationIndicator,
    val verifiedReferences: RatioIndicator,
    val issuesBeforeReview: IssuesIndicator,
    val humanReviewTime: DurationIndicator,
    val cost: CostIndicator,
    val publishedWithoutGenerationErrors: UnavailableIndicator,
    val issuesAfterPublishing: UnavailableIndicator,
    val kind: IndicatorKind = IndicatorKind.PROCESS,
    val notice: String = NOTICE,
) {
    companion object {
        const val NOTICE = "Process indicators of the AI pipeline (MODEL executions only). They do not measure pedagogical quality."
    }
}

// los calcula a petición: exige VER_INDICADORES_IA sobre version_curso (AiPermissions, 403), una acción declarada que solo
// tiene ADMIN en roles.json; es de la organización entera. Con courseId, el curso tiene que verse (404 si no)
@Service
class PipelineIndicators(
    private val permissions: AiPermissions,
    private val records: RecordService,
    private val store: PipelineIndicatorStore,
    private val clock: Clock,
) {
    suspend fun of(courseId: UUID?): PipelineIndicatorsView {
        val organization = permissions.require(AiAction.VER_INDICADORES_IA).organizationId
        if (courseId != null) records.get(COURSE, courseId)
        val acceptance = store.acceptance(organization, courseId)
        val references = store.references(organization, courseId)
        val issues = store.issuesBeforeReview(organization, courseId)
        val review = store.reviewTime(organization, courseId)
        val costs = store.costs(organization, courseId)
        return PipelineIndicatorsView(
            courseId = courseId,
            generator = Generator.MODEL,
            computedAt = clock.instant(),
            acceptedWithoutStructuralChange = ratio(1, acceptance),
            briefToValidatedDraft = briefToValidated(store.validatedDrafts(organization, courseId)),
            verifiedReferences = ratio(3, references),
            issuesBeforeReview =
                IssuesIndicator(
                    number = 4,
                    drafts = issues.drafts,
                    withoutReport = issues.withoutReport,
                    problems = issues.problems,
                    blocking = issues.blocking,
                    warnings = issues.warnings,
                    perDraft = if (issues.drafts == 0L) null else issues.problems.toDouble() / issues.drafts,
                ),
            humanReviewTime = DurationIndicator(5, review.sample, review.excluded, review.seconds),
            cost = CostIndicator(6, perCourse = costs.filter { it.unitId == null }.map(::line), perUnit = costs.filter { it.unitId != null }.map(::line)),
            publishedWithoutGenerationErrors = UnavailableIndicator(7, NO_FEEDBACK),
            issuesAfterPublishing = UnavailableIndicator(8, NO_FEEDBACK),
        )
    }

    // la creación del brief la sabe Wasichai: una lectura de todos los briefs a la vez, como quien llama. Un borrador cuya
    // ejecución no nombra brief, o cuyo brief ya no existe o no se ve, queda fuera (excluded)
    private suspend fun briefToValidated(drafts: List<ValidatedDraft>): DurationIndicator {
        val ids = drafts.mapNotNull { d -> d.briefId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } }
        val briefs =
            try {
                records.listByIds(BRIEF, ids)
            } catch (_: ForbiddenException) {
                // quien no lee briefs ve el indicador sin muestra, no un 403 de toda la respuesta
                emptyList()
            }
        val created = briefs.mapNotNull { r -> r.createdAt?.let { r.id.toString() to it } }.toMap()
        val seconds = drafts.mapNotNull { d -> created[d.briefId]?.let { Duration.between(it, d.validatedAt).toMillis() / 1000.0 } }
        return DurationIndicator(2, seconds.size.toLong(), (drafts.size - seconds.size).toLong(), median(seconds))
    }

    private fun ratio(
        number: Int,
        ratio: Ratio,
    ) = RatioIndicator(number, ratio.numerator, ratio.denominator, if (ratio.denominator == 0L) null else ratio.numerator.toDouble() / ratio.denominator)

    private fun line(row: CostRow) = CostLine(row.courseId, row.unitId, row.currency, row.amount, row.runs, row.rates)

    companion object {
        const val COURSE = "curso"
        const val BRIEF = "brief"
        const val NO_FEEDBACK = "Needs contextual feedback resolved as a content error (RF-REP-03, F6), which does not exist yet"

        // la mediana como percentile_cont(0.5) de PostgreSQL: con un número par de casos, la media de los dos centrales
        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }
    }
}

// los indicadores de proceso de la canalización (RF-IA-10, docs/api/lms.md); solo lectura
@RestController
@RequestMapping("/api/lms/ai/indicators")
class PipelineIndicatorController(
    private val indicators: PipelineIndicators,
) {
    @GetMapping
    suspend fun of(
        @RequestParam(required = false) courseId: UUID?,
    ): PipelineIndicatorsView = indicators.of(courseId)
}
