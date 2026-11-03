package elearning.reputation

import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.NotFoundException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// el criterio publicado y versionado de los indicadores (ADR-0038 §11, RF-REP-06): cada cifra enlaza aquí
object RatingCriterion {
    const val VERSION = "media-simple@1"
    const val PATH = "/api/lms/public/ratings/criterion"
}

data class RatingCriterionView(
    val version: String,
    val minSample: Int,
    val scale: String,
    val formula: String,
    val release: String,
    val notQuality: String,
)

// una dimensión de un indicador. sin muestra suficiente, solo eso: ni media ni n
data class IndicatorView(
    val key: String,
    val name: String?,
    val sufficient: Boolean,
    val mean: BigDecimal?,
    val n: Int?,
)

// el resumen del docente en una edición: nada de quién, cuándo ni ids. released falso: sin cifras ni comentarios
data class TeacherRatingSummary(
    val released: Boolean,
    val windowClosesOn: LocalDate?,
    val releasedAtLatestOn: LocalDate?,
    val criterion: String,
    val criterionVersion: String,
    val dimensions: List<IndicatorView>,
    val comments: List<RatingComment>,
)

// un comentario publicado, sin autor ni fecha; token: su ficha opaca, para denunciarlo
data class RatingComment(
    val token: String?,
    val text: String,
)

// el resumen de una versión de recurso para su autor, de todas las ediciones, recalculado cada día
data class ResourceRatingSummary(
    val resourceVersionId: String,
    val title: String?,
    val number: Int?,
    val criterion: String,
    val criterionVersion: String,
    val calculatedAt: Instant?,
    val dimensions: List<IndicatorView>,
)

// los resúmenes que ve el valorado (ADR-0038 §7–§11): con VER_RESUMEN y solo de lo que lo tiene como destino. el docente,
// los de su asignación en una edición cuando se liberan (regla 8); el autor, los de sus versiones de recurso. por debajo
// de la muestra mínima, «muestra insuficiente». nunca la identidad de nadie
@Service
class RatingSummaries(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val facts: RatingFacts,
    private val indicators: RatingIndicators,
    private val transactions: TransactionalOperator,
    private val properties: ReputationProperties,
    private val clock: Clock,
) {
    fun criterion(): RatingCriterionView =
        RatingCriterionView(
            version = RatingCriterion.VERSION,
            minSample = properties.sample,
            scale = "Enteros de 1 a 5 por dimensión",
            formula = "Media aritmética simple de los puntajes de cada dimensión, con dos decimales, y su número n",
            release =
                "El docente ve los de una edición cuando cerró la ventana de valoración y todas las matrículas tienen resultado, " +
                    "o pasados ${properties.releaseAfterEnd.toDays()} días del fin; entonces se congelan",
            notQuality = "La cantidad de valoraciones no es una medida de calidad",
        )

    suspend fun teacher(offeringCode: String): TeacherRatingSummary {
        val user = summarizer()
        val org = user.organizationId
        val offering = facts.offeringByCode(org, offeringCode) ?: throw NotFoundException(NOT_FOUND)
        val assignment = facts.assignments(org, offering.id).firstOrNull { it.user == user.userId } ?: throw NotFoundException(NOT_FOUND)
        if (!indicators.released(org, offering.id) && releasable(org, offering)) freeze(org, offering.id)
        val released = indicators.released(org, offering.id)
        val summary =
            TeacherRatingSummary(
                released = false,
                windowClosesOn = offering.end?.let { RatingWindow.closesOn(it, properties.windowAfterEnd) },
                releasedAtLatestOn = offering.end?.let { RatingWindow.closesOn(it, properties.releaseAfterEnd) },
                criterion = RatingCriterion.PATH,
                criterionVersion = RatingCriterion.VERSION,
                dimensions = emptyList(),
                comments = emptyList(),
            )
        if (!released) return summary
        val stored = indicators.stored(org, RatingWriteGuard.TEACHER, listOf(assignment.id))
        val ratings = stored.maxOfOrNull { it.n } ?: 0
        return summary.copy(
            released = true,
            dimensions = views(org, RatingWriteGuard.TEACHER, stored),
            comments = if (ratings >= properties.sample) indicators.publishedComments(org, offering.id, assignment.id) else emptyList(),
        )
    }

    suspend fun resources(): List<ResourceRatingSummary> {
        val user = summarizer()
        val org = user.organizationId
        val authored = facts.authoredResourceVersions(org, user.userId)
        val stored = indicators.stored(org, RatingWriteGuard.RESOURCE, authored.map { it.id }).groupBy { it.subject }
        val calculatedAt = indicators.resourcesCalculatedAt(org)
        return authored.map { version ->
            ResourceRatingSummary(
                resourceVersionId = version.id.toString(),
                title = version.title,
                number = version.number,
                criterion = RatingCriterion.PATH,
                criterionVersion = RatingCriterion.VERSION,
                calculatedAt = calculatedAt,
                dimensions = views(org, RatingWriteGuard.RESOURCE, stored[version.id].orEmpty()),
            )
        }
    }

    // la regla 8: ventana cerrada y todas las matrículas no retiradas con resultado, o pasado release-after-end del fin
    suspend fun releasable(
        org: UUID,
        offering: RatingFacts.Offering,
    ): Boolean {
        val end = offering.end ?: return false
        val today = RatingWindow.today(offering.zone, clock)
        if (today.isAfter(RatingWindow.closesOn(end, properties.releaseAfterEnd))) return true
        if (!today.isAfter(RatingWindow.closesOn(end, properties.windowAfterEnd))) return false
        return facts.pendingResults(org, offering.id) == 0L
    }

    // la congela una vez; false si otra petición o el trabajo ya lo hizo
    suspend fun freeze(
        org: UUID,
        offering: UUID,
    ): Boolean = transactions.executeAndAwait { indicators.freeze(org, offering, Instant.now(clock)) }

    // por cada dimensión del destino (las activas y las que ya tienen cifras), con muestra suficiente o sin cifras
    private suspend fun views(
        org: UUID,
        target: String,
        stored: List<Indicator>,
    ): List<IndicatorView> {
        val dimensions = facts.dimensions(org).filter { it.target == target }
        val byKey = stored.associateBy { it.dimension }
        val keys = dimensions.filter { it.active || it.key in byKey }.map { it.key } + (byKey.keys - dimensions.map { it.key }.toSet())
        val names = dimensions.associate { it.key to it.name }
        return keys.map { key ->
            val found = byKey[key]
            val sufficient = found != null && found.n >= properties.sample
            IndicatorView(key, names[key], sufficient, if (sufficient) found?.mean else null, if (sufficient) found?.n else null)
        }
    }

    private suspend fun summarizer(): AuthenticatedUser {
        val user = currentUser.require()
        currentUser.requirePermission(user, SUMMARY, metadata.loadDefinition(user.organizationId, RatingFacts.RATING).obj.id)
        return user
    }

    companion object {
        const val SUMMARY = "VER_RESUMEN"
        const val NOT_FOUND = "Edición no encontrada"
    }
}
