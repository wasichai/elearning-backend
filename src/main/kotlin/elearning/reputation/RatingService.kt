package elearning.reputation

import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.ref
import elearning.learning.ClassroomReader
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

// valorar a los docentes y los recursos de una matrícula propia (RF-REP-01/02, ADR-0038). todo se escribe como la persona,
// que tiene VALORAR y lee solo lo suyo (RatingReadScope): una matrícula ajena no existe (404). RatingWriteGuard repite las
// invariantes para toda escritura; aquí se comprueba antes lo que solo el comando sabe (el progreso del aula) y se responde
// con el motivo que la persona puede entender. cada alta, edición o retirada cuenta en el límite diario, en su transacción
@Service
class RatingService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val facts: RatingFacts,
    private val quota: RatingQuota,
    private val classroom: ClassroomReader,
    private val transactions: TransactionalOperator,
    private val properties: ReputationProperties,
    private val clock: Clock,
) {
    // a quién puede valorar desde esta matrícula, con el motivo de lo que no
    suspend fun eligibility(enrollmentId: UUID): RatingEligibility {
        val user = rater()
        val context = context(user, enrollmentId)
        val dimensions = facts.dimensions(user.organizationId).filter { it.active }
        return RatingEligibility(
            enrollmentId = enrollmentId.toString(),
            open = context.open,
            closesOn = context.closesOn,
            progressPercent = context.progress,
            minProgress = properties.minProgress,
            teachers = context.teachers.map { context.teacherTarget(it) },
            resources = context.resources.distinctBy { it.resourceVersion }.map { context.resourceTarget(it) },
            dimensions =
                listOf(RatingWriteGuard.TEACHER, RatingWriteGuard.RESOURCE).associateWith { target ->
                    dimensions.filter { it.target == target }.map { RatingDimension(it.key, it.name) }
                },
        )
    }

    suspend fun rate(
        enrollmentId: UUID,
        request: RateRequest,
    ): RatingView {
        val user = rater()
        val context = context(user, enrollmentId)
        val type = request.type?.trim()
        val targetId = request.targetId?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: invalid("targetId", "must be a UUID")
        val target =
            when (type) {
                RatingWriteGuard.TEACHER ->
                    context.teachers.firstOrNull { it.id == targetId }?.let { context.teacherTarget(it) }
                RatingWriteGuard.RESOURCE ->
                    context.resources.firstOrNull { it.resourceVersion == targetId }?.let { context.resourceTarget(it) }
                else -> invalid("type", "must be ${RatingWriteGuard.TEACHER} or ${RatingWriteGuard.RESOURCE}")
            } ?: throw ForbiddenException("You can only rate the teachers and resources of your own offering")
        refuse(target)
        val scores = scores(user, type, request.scores)
        val comment = comment(request.comment)
        val created =
            transactions.executeAndAwait {
                quota.consume(user.organizationId, user.userId, QuotaAction.CAMBIO, properties.maxEditsPerDay)
                val rating =
                    records.create(
                        RatingFacts.RATING,
                        RecordRequest(
                            mapOf(
                                RatingFacts.STUDENT to user.userId.toString(),
                                RatingFacts.ENROLLMENT to enrollmentId.toString(),
                                RatingFacts.OFFERING_FIELD to context.offering.toString(),
                                RatingFacts.TARGET to type,
                                RatingFacts.ASSIGNMENT to if (type == RatingWriteGuard.TEACHER) targetId.toString() else null,
                                RatingFacts.RESOURCE_VERSION to if (type == RatingWriteGuard.RESOURCE) targetId.toString() else null,
                                RatingWriteGuard.COMMENT to comment,
                                RatingWriteGuard.MODERATION to comment?.let { RatingWriteGuard.PENDING },
                                // la ficha opaca con la que el valorado denuncia el comentario, sin saber de quién es
                                RatingWriteGuard.TOKEN to UUID.randomUUID().toString(),
                            ),
                        ),
                    )
                scores.forEach { (dimension, value) -> createScore(user, rating.id, dimension, value) }
                rating
            }
        return views(user, listOf(created)).single()
    }

    suspend fun update(
        ratingId: UUID,
        request: RatingUpdateRequest,
    ): RatingView {
        val user = rater()
        val rating = own(ratingId)
        requireOpen(user, rating)
        val scores = scores(user, rating.attributes[RatingFacts.TARGET]?.toString(), request.scores)
        val comment = comment(request.comment)
        val updated =
            transactions.executeAndAwait {
                quota.consume(user.organizationId, user.userId, QuotaAction.CAMBIO, properties.maxEditsPerDay)
                val before = RatingWriteGuard.text(rating.attributes, RatingWriteGuard.COMMENT)
                val current =
                    if (comment != before) {
                        records.patch(
                            RatingFacts.RATING,
                            ratingId,
                            RecordRequest(
                                mapOf(
                                    RatingWriteGuard.COMMENT to comment,
                                    RatingWriteGuard.MODERATION to comment?.let { RatingWriteGuard.PENDING },
                                ),
                            ),
                        )
                    } else {
                        rating
                    }
                val stored = records.listAll(RatingFacts.SCORE, mapOf(RatingFacts.RATING to ratingId.toString()))
                val byDimension = stored.associateBy { it.attributes.ref(RatingWriteGuard.DIMENSION) }
                for ((dimension, value) in scores) {
                    val found = byDimension[dimension.toString()]
                    when {
                        found == null -> createScore(user, ratingId.toString(), dimension, value)
                        (found.attributes[RatingWriteGuard.VALUE] as? Number)?.toInt() != value ->
                            records.patch(RatingFacts.SCORE, UUID.fromString(found.id), RecordRequest(mapOf(RatingWriteGuard.VALUE to value)))
                    }
                }
                stored
                    .filter { it.attributes.ref(RatingWriteGuard.DIMENSION)?.let(UUID::fromString) !in scores.keys }
                    .forEach { records.delete(RatingFacts.SCORE, UUID.fromString(it.id)) }
                current
            }
        return views(user, listOf(updated)).single()
    }

    // la persona retira su valoración mientras la ventana está abierta (regla 22): sus puntajes primero
    suspend fun delete(ratingId: UUID) {
        val user = rater()
        val rating = own(ratingId)
        requireOpen(user, rating)
        transactions.executeAndAwait {
            quota.consume(user.organizationId, user.userId, QuotaAction.CAMBIO, properties.maxEditsPerDay)
            records
                .listAll(RatingFacts.SCORE, mapOf(RatingFacts.RATING to ratingId.toString()))
                .forEach { records.delete(RatingFacts.SCORE, UUID.fromString(it.id)) }
            records.delete(RatingFacts.RATING, ratingId)
        }
    }

    // mis valoraciones, las más nuevas primero
    suspend fun mine(): List<RatingView> {
        val user = rater()
        val mine = records.listAll(RatingFacts.RATING, mapOf(RatingFacts.STUDENT to user.userId.toString()))
        return views(user, mine.sortedByDescending { it.createdAt })
    }

    private suspend fun rater(): AuthenticatedUser {
        val user = currentUser.require()
        currentUser.requirePermission(user, VALUE_ACTION, metadata.loadDefinition(user.organizationId, RatingFacts.RATING).obj.id)
        return user
    }

    // una valoración propia: la ajena no existe para quien llama (RatingReadScope)
    private suspend fun own(id: UUID): RecordResponse = records.get(RatingFacts.RATING, id)

    private suspend fun requireOpen(
        user: AuthenticatedUser,
        rating: RecordResponse,
    ) {
        val offering = rating.attributes.ref(RatingFacts.OFFERING_FIELD)?.let { facts.offering(user.organizationId, UUID.fromString(it)) }
        if (offering == null || !RatingWindow.isOpen(offering.end, offering.zone, properties.windowAfterEnd, clock)) {
            throw ConflictException(RatingWriteGuard.WINDOW_CLOSED)
        }
    }

    private fun refuse(target: RatingTarget) {
        when (target.reason) {
            null -> return
            CLOSED -> throw ConflictException(RatingWriteGuard.WINDOW_CLOSED)
            RATED -> throw ConflictException("You already rated this; edit your rating instead")
            ENROLLMENT -> throw ForbiddenException("Only an active or finished enrollment rates")
            PROGRESS -> throw ForbiddenException("You rate a teacher from ${properties.minProgress}% of progress or a finished enrollment")
            LESSON -> throw ForbiddenException("You rate a resource once you completed a lesson that uses it")
            else -> throw ForbiddenException("Nobody rates what is their own")
        }
    }

    // los puntajes por clave de dimensión, de 1 a 5, de dimensiones activas del destino; al menos uno
    private suspend fun scores(
        user: AuthenticatedUser,
        target: String?,
        sent: Map<String, Int>?,
    ): Map<UUID, Int> {
        if (sent.isNullOrEmpty()) invalid("scores", "rate at least one dimension")
        val dimensions = facts.dimensions(user.organizationId).filter { it.active && it.target == target }.associateBy { it.key }
        return sent.entries.associate { (key, value) ->
            val dimension = dimensions[key] ?: invalid("scores", "'$key' is not an active dimension for this target")
            if (value !in RatingWriteGuard.SCALE) invalid("scores", "'$key' must be from 1 to 5")
            dimension.id to value
        }
    }

    private fun comment(sent: String?): String? {
        val comment = sent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (comment.length > MAX_COMMENT) invalid("comment", "at most $MAX_COMMENT characters")
        return comment
    }

    private suspend fun createScore(
        user: AuthenticatedUser,
        rating: String,
        dimension: UUID,
        value: Int,
    ) {
        records.create(
            RatingFacts.SCORE,
            RecordRequest(
                mapOf(
                    RatingFacts.RATING to rating,
                    RatingWriteGuard.DIMENSION to dimension.toString(),
                    RatingFacts.STUDENT to user.userId.toString(),
                    RatingWriteGuard.VALUE to value,
                ),
            ),
        )
    }

    // lo que hace falta para decidir sobre una matrícula propia: su edición, su ventana, su progreso y sus destinos
    private inner class Context(
        val offering: UUID,
        val open: Boolean,
        val closesOn: LocalDate?,
        val progress: Int,
        val active: Boolean,
        val finished: Boolean,
        val teacherOfOffering: Boolean,
        val teachers: List<RatingFacts.Assignment>,
        val resources: List<RatingFacts.LinkedResource>,
        val completed: Set<UUID>,
        val rated: Map<String, String>,
        val me: UUID,
    ) {
        private fun common(): String? =
            when {
                !active -> ENROLLMENT
                !open -> CLOSED
                else -> null
            }

        fun teacherTarget(assignment: RatingFacts.Assignment): RatingTarget {
            val ratingId = rated["${RatingWriteGuard.TEACHER}:${assignment.id}"]
            val reason =
                common()
                    ?: when {
                        teacherOfOffering -> OWN
                        ratingId != null -> RATED
                        !finished && progress < properties.minProgress -> PROGRESS
                        else -> null
                    }
            return RatingTarget(RatingWriteGuard.TEACHER, assignment.id.toString(), assignment.name, reason == null, reason, ratingId)
        }

        fun resourceTarget(resource: RatingFacts.LinkedResource): RatingTarget {
            val ratingId = rated["${RatingWriteGuard.RESOURCE}:${resource.resourceVersion}"]
            val lessons = resources.filter { it.resourceVersion == resource.resourceVersion }.map { it.lesson }
            val reason =
                common()
                    ?: when {
                        resources.any { it.resourceVersion == resource.resourceVersion && it.author == me } -> OWN
                        ratingId != null -> RATED
                        lessons.none { it in completed } -> LESSON
                        else -> null
                    }
            return RatingTarget(RatingWriteGuard.RESOURCE, resource.resourceVersion.toString(), resource.title, reason == null, reason, ratingId)
        }
    }

    private suspend fun context(
        user: AuthenticatedUser,
        enrollmentId: UUID,
    ): Context {
        val org = user.organizationId
        // como la persona: la matrícula ajena no existe
        val enrollment = records.get(RatingFacts.ENROLLMENT, enrollmentId)
        val stored = facts.enrollment(org, enrollmentId) ?: throw NotFoundException("Enrollment not found")
        val offeringId = stored.offering ?: throw NotFoundException("Enrollment not found")
        val offering = facts.offering(org, offeringId)
        val assignments = facts.assignments(org, offeringId)
        val linked = stored.version?.let { facts.linkedResources(org, it) }.orEmpty()
        val rated =
            records
                .listAll(RatingFacts.RATING, mapOf(RatingFacts.ENROLLMENT to enrollmentId.toString()))
                .mapNotNull { r ->
                    val target = r.attributes[RatingFacts.TARGET]?.toString()
                    val id = RatingWriteGuard.targetOf(r.attributes)
                    id?.let { "$target:$it" to r.id }
                }.toMap()
        return Context(
            offering = offeringId,
            open = RatingWindow.isOpen(offering?.end, offering?.zone, properties.windowAfterEnd, clock),
            closesOn = offering?.end?.let { RatingWindow.closesOn(it, properties.windowAfterEnd) },
            progress = classroom.view(enrollment).progress.percent,
            active = stored.state in RatingWriteGuard.RATING_ENROLLMENTS,
            finished = stored.state == FINISHED,
            teacherOfOffering = assignments.any { it.user == user.userId },
            teachers = assignments,
            resources = linked.distinctBy { it.resourceVersion to it.lesson },
            completed = facts.completedLessons(org, enrollmentId),
            rated = rated,
            me = user.userId,
        )
    }

    // con el nombre del destino y los puntajes por clave de dimensión, en pocas lecturas
    private suspend fun views(
        user: AuthenticatedUser,
        ratings: List<RecordResponse>,
    ): List<RatingView> {
        if (ratings.isEmpty()) return emptyList()
        val org = user.organizationId
        val keys = facts.dimensions(org).associate { it.id.toString() to it.key }
        val scores =
            records
                .listAllIn(RatingFacts.SCORE, RatingFacts.RATING, ratings.map { it.id })
                .groupBy { it.attributes.ref(RatingFacts.RATING) }
        val names = mutableMapOf<String, String?>()
        for (offering in ratings.mapNotNull { it.attributes.ref(RatingFacts.OFFERING_FIELD) }.distinct()) {
            facts.assignments(org, UUID.fromString(offering)).forEach { names[it.id.toString()] = it.name }
        }
        for (enrollment in ratings.mapNotNull { it.attributes.ref(RatingFacts.ENROLLMENT) }.distinct()) {
            val version = facts.enrollment(org, UUID.fromString(enrollment))?.version ?: continue
            facts.linkedResources(org, version).forEach { names[it.resourceVersion.toString()] = it.title }
        }
        return ratings.map { r ->
            val a = r.attributes
            val target = RatingWriteGuard.targetOf(a)
            RatingView(
                id = r.id,
                enrollmentId = a.ref(RatingFacts.ENROLLMENT),
                type = a[RatingFacts.TARGET]?.toString(),
                targetId = target?.toString(),
                targetName = target?.let { names[it.toString()] },
                scores =
                    scores[r.id]
                        .orEmpty()
                        .mapNotNull { s ->
                            val key = keys[s.attributes.ref(RatingWriteGuard.DIMENSION)] ?: return@mapNotNull null
                            val value = (s.attributes[RatingWriteGuard.VALUE] as? Number)?.toInt() ?: return@mapNotNull null
                            key to value
                        }.toMap(),
                comment = RatingWriteGuard.text(a, RatingWriteGuard.COMMENT),
                moderation = RatingWriteGuard.text(a, RatingWriteGuard.MODERATION),
                createdAt = r.createdAt,
                updatedAt = r.updatedAt,
            )
        }
    }

    private fun invalid(
        field: String,
        reason: String,
    ): Nothing = throw ValidationException("Invalid rating", field, reason)

    companion object {
        const val VALUE_ACTION = "VALORAR"
        const val FINISHED = "FINALIZADA"
        const val MAX_COMMENT = 2000
        const val CLOSED = "CLOSED"
        const val ENROLLMENT = "ENROLLMENT"
        const val PROGRESS = "PROGRESS"
        const val LESSON = "LESSON"
        const val OWN = "OWN"
        const val RATED = "RATED"
    }
}
