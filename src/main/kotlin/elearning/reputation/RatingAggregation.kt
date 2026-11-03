package elearning.reputation

import elearning.jobs.Job
import elearning.jobs.JobHandler
import elearning.jobs.JobProperties
import elearning.jobs.JobQueue
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import wasichai.core.platform.TenantDirectory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// el trabajo de agregados de las valoraciones (ADR-0038 §11) en la cola persistente: cada lms.reputation.aggregate-every
// (un día) se encola uno por organización con valoraciones; la clave hace que no haya dos a la vez. congela las ediciones
// que ya se pueden liberar, rehace los indicadores de recurso y borra la cuota de los días pasados. repetirlo no cambia lo
// congelado. lo encola también la supresión de una persona (ADR-0037), para que sus puntajes dejen de contar
@Component
class RatingAggregation(
    private val tenants: TenantDirectory,
    private val queue: JobQueue,
    private val jobs: JobProperties,
    private val facts: RatingFacts,
    private val indicators: RatingIndicators,
    private val summaries: RatingSummaries,
    private val quota: RatingQuota,
    private val clock: Clock,
) : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = TYPE

    @Scheduled(fixedDelayString = "\${lms.reputation.aggregate-every:1d}", initialDelayString = "\${lms.jobs.initial-delay:10s}")
    suspend fun scheduled() {
        if (!jobs.scheduling) return
        try {
            tenants.organizationsWithObject(RatingFacts.RATING).forEach { queue.enqueue(it.id, TYPE, key = TYPE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("rating aggregates could not be enqueued", e)
        }
    }

    override suspend fun handle(job: Job): String {
        val (frozen, resources) = aggregate(job.organizationId)
        return """{"frozen": $frozen, "resourceIndicators": $resources}"""
    }

    // lo que hace el trabajo, para una organización: cuántas ediciones congeló y cuántas cifras de recurso escribió
    suspend fun aggregate(organization: UUID): Pair<Int, Int> {
        var frozen = 0
        for (offering in facts.offeringsWithTeacherRatings(organization)) {
            if (indicators.released(organization, offering)) continue
            val found = facts.offering(organization, offering) ?: continue
            if (summaries.releasable(organization, found) && summaries.freeze(organization, offering)) frozen++
        }
        val resources = indicators.replaceResources(organization, Instant.now(clock))
        quota.purgeBefore(LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC))
        return frozen to resources
    }

    companion object {
        const val TYPE = "reputation.aggregate"
    }
}
