package elearning.reputation

import elearning.common.ref
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Clock
import java.util.UUID

// las invariantes de las valoraciones (ADR-0038 §24) para TODA escritura de valoracion, puntaje_valoracion y
// denuncia_valoracion, la plataforma
// incluida. son apiOnly, así que solo escribe el LMS, pero la guarda no se fía de quien llama:
//
// - una valoración tiene un destino y solo uno, del tipo que declara; su matrícula es de la persona que valora, de la misma
//   edición y ACTIVA o FINALIZADA; el docente es de esa edición y el recurso, de una lección de la versión de la matrícula
// - nadie valora lo suyo: ni un docente de la edición a sus colegas (ni a sí mismo), ni el autor su versión de recurso
// - una persona escribe solo a su nombre y, fuera de la ventana, no crea, edita ni retira (la plataforma suprime siempre)
// - lo que identifica la valoración no cambia; editar el comentario lo devuelve a PENDIENTE
// - publicar u ocultar un comentario exige MODERAR, y no se modera lo propio ni lo que lo tiene a uno como destino; ocultar
//   pide un motivo tasado; el autor apela una vez lo oculto y la apelación la decide otro moderador, una vez
// - una denuncia la hace el valorado de lo que lo tiene como destino; resolverla, quien modera
// - un puntaje es de 1 a 5, de una dimensión activa del destino de su valoración y del mismo estudiante
//
// las comprobaciones que cruzan objetos leen por RatingFacts: no dependen de lo que quien escribe puede leer
@Component
@Order(RATING_GUARD_ORDER)
class RatingWriteGuard(
    private val currentUser: CurrentUser,
    private val facts: RatingFacts,
    // MetadataService recibe las guardas por RecordService: se pide perezosamente para no formar un ciclo
    private val metadata: ObjectProvider<MetadataService>,
    private val properties: ReputationProperties,
    private val clock: Clock,
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.objectName !in GUARDED) return
        val user = change.userId?.let { currentUser.require() }
        try {
            when (change.objectName) {
                RatingFacts.RATING -> rating(change, user)
                RatingFacts.SCORE -> score(change, user)
                else -> report(change, user)
            }
        } catch (_: RatingFacts.MissingModel) {
            throw ConflictException("The rating model is incomplete")
        }
    }

    private suspend fun rating(
        change: RecordWrite,
        user: AuthenticatedUser?,
    ) {
        val org = change.organizationId
        val before = change.before.orEmpty()
        val after = change.attributes.orEmpty()
        when (change.kind) {
            RecordChangeKind.CREATED -> created(org, after, user)
            RecordChangeKind.UPDATED -> {
                val changed = IDENTITY.filter { !same(before[it], after[it]) }
                if (changed.isNotEmpty()) throw ConflictException("What a rating is about does not change: ${changed.joinToString()}")
                val fields = after.keys.filter { !same(before[it], after[it]) }.toSet()
                val from = text(before, MODERATION)
                val to = text(after, MODERATION)
                if (fields.any { it in MODERATOR_FIELDS } || (from != to && to in MODERATED)) {
                    moderated(org, change.recordId, before, after, fields, user)
                } else {
                    authored(org, before, user, appealOnly = fields.isNotEmpty() && fields.all { it in APPEAL_FIELDS })
                    if (fields.any { it in APPEAL_FIELDS }) appealed(before, after, fields)
                    val comment = text(after, COMMENT)
                    if (comment != text(before, COMMENT)) {
                        // el comentario editado vuelve a moderación; sin comentario no hay nada que moderar
                        if (to != comment?.let { PENDING }) invalid(MODERATION, "an edited comment goes back to $PENDING")
                    } else if (from != to) {
                        throw ForbiddenException("Missing permission $MODERATE")
                    }
                }
            }
            RecordChangeKind.DELETED -> authored(org, before, user)
            else -> throw ConflictException("A rating has no transitions")
        }
    }

    private suspend fun created(
        org: UUID,
        after: Map<String, Any?>,
        user: AuthenticatedUser?,
    ) {
        val student = uuidOf(after[RatingFacts.STUDENT]) ?: invalid(RatingFacts.STUDENT, "is required")
        if (user != null && user.userId != student) invalid(RatingFacts.STUDENT, "must be your own user id")
        val target = text(after, RatingFacts.TARGET)
        val assignment = uuidOf(after[RatingFacts.ASSIGNMENT])
        val resource = uuidOf(after[RatingFacts.RESOURCE_VERSION])
        when (target) {
            TEACHER -> if (assignment == null || resource != null) invalid(RatingFacts.ASSIGNMENT, "names one assignment and no resource")
            RESOURCE -> if (resource == null || assignment != null) invalid(RatingFacts.RESOURCE_VERSION, "names one resource version and no teacher")
            else -> invalid(RatingFacts.TARGET, "must be $TEACHER or $RESOURCE")
        }
        val enrollmentId = uuidOf(after[RatingFacts.ENROLLMENT]) ?: invalid(RatingFacts.ENROLLMENT, "is required")
        val enrollment = facts.enrollment(org, enrollmentId) ?: invalid(RatingFacts.ENROLLMENT, "does not exist")
        if (enrollment.student != student) throw ForbiddenException("Only the student of an enrollment rates from it")
        if (enrollment.state !in RATING_ENROLLMENTS) throw ForbiddenException("Only an active or finished enrollment rates")
        val offering = uuidOf(after[RatingFacts.OFFERING_FIELD])
        if (offering == null || offering != enrollment.offering) invalid(RatingFacts.OFFERING_FIELD, "must be the offering of the enrollment")
        if (target == TEACHER) {
            val assignments = facts.assignments(org, offering)
            if (assignments.none { it.id == assignment }) throw ForbiddenException("The teacher does not teach this offering")
            if (assignments.any { it.user == student }) throw ForbiddenException("A teacher of the offering does not rate its teachers")
        } else {
            val version = enrollment.version ?: throw ForbiddenException("The resource is not used in this enrollment")
            val linked = facts.linkedResources(org, version).filter { it.resourceVersion == resource }
            if (linked.isEmpty()) throw ForbiddenException("The resource is not used in this enrollment")
            if (linked.any { it.author == student }) throw ForbiddenException("The author of a resource does not rate it")
        }
        val state = text(after, MODERATION)
        if (state != null && state != PENDING) throw ForbiddenException("Missing permission $MODERATE")
        if (text(after, COMMENT) != null && state != PENDING) invalid(MODERATION, "a new comment starts as $PENDING")
        if (user != null) requireOpen(org, offering)
    }

    // una persona cambia o retira solo lo suyo y con la ventana abierta; la plataforma (la supresión) siempre. apelar no
    // depende de la ventana: lo oculto puede llegar después de cerrarse
    private suspend fun authored(
        org: UUID,
        before: Map<String, Any?>,
        user: AuthenticatedUser?,
        appealOnly: Boolean = false,
    ) {
        if (user == null) return
        if (uuidOf(before[RatingFacts.STUDENT]) != user.userId) throw ForbiddenException("Only its author changes a rating")
        if (!appealOnly) requireOpen(org, uuidOf(before[RatingFacts.OFFERING_FIELD]))
    }

    // una sola apelación, de un comentario oculto (regla 17)
    private fun appealed(
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        fields: Set<String>,
    ) {
        if (text(before, APPEAL) != null) throw ConflictException("A hidden comment is appealed only once")
        if (text(before, MODERATION) != HIDDEN) throw ConflictException("Only a hidden comment is appealed")
        if (COMMENT in fields || text(after, APPEAL) == null) invalid(APPEAL, "an appeal has a text and leaves the comment as it is")
    }

    // publicar, ocultar con motivo tasado o decidir una apelación: MODERAR, nunca lo propio ni lo que lo tiene a uno como
    // destino; la apelación la decide otro moderador, una vez (reglas 14, 15, 17 y 18)
    private suspend fun moderated(
        org: UUID,
        id: UUID?,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        fields: Set<String>,
        user: AuthenticatedUser?,
    ) {
        if (fields.any { it == COMMENT || it in APPEAL_FIELDS }) throw ConflictException("A moderator does not rewrite a comment nor its appeal")
        if (text(after, MODERATION) == HIDDEN && text(after, REASON) !in REASONS) invalid(REASON, "hiding needs one of ${REASONS.joinToString()}")
        val deciding = fields.any { it in DECISION_FIELDS }
        if (deciding) {
            if (text(before, APPEAL) == null) throw ConflictException("There is no appeal to decide")
            if (text(before, DECIDED_BY) != null) throw ConflictException("An appeal is decided once")
        }
        if (user == null) return
        val ratingObject =
            metadata
                .getObject()
                .loadDefinition(org, RatingFacts.RATING)
                .obj.id
        if (!currentUser.hasPermission(user, MODERATE, ratingObject)) throw ForbiddenException("Missing permission $MODERATE")
        if (deciding && uuidOf(before[MODERATED_BY]) == user.userId) throw ForbiddenException("Another moderator decides the appeal")
        val rating = id?.let { facts.rating(org, it) } ?: return
        if (rating.student == user.userId) throw ForbiddenException("Nobody moderates their own rating")
        if (facts.ratedUser(org, rating) == user.userId) throw ForbiddenException("Nobody moderates a rating about themselves")
    }

    // una denuncia la hace el valorado de un comentario publicado que lo tiene como destino; la resuelve quien modera
    private suspend fun report(
        change: RecordWrite,
        user: AuthenticatedUser?,
    ) {
        if (user == null) return
        val org = change.organizationId
        when (change.kind) {
            RecordChangeKind.CREATED -> {
                val after = change.attributes.orEmpty()
                if (uuidOf(after[REPORTER]) != user.userId) invalid(REPORTER, "must be your own user id")
                val rating = uuidOf(after[REPORTED])?.let { facts.rating(org, it) } ?: invalid(REPORTED, "does not exist")
                if (facts.ratedUser(org, rating) != user.userId) throw ForbiddenException("Only whoever is rated reports a comment")
            }
            else -> {
                val reportObject =
                    metadata
                        .getObject()
                        .loadDefinition(org, REPORT)
                        .obj.id
                val ratingObject =
                    metadata
                        .getObject()
                        .loadDefinition(org, RatingFacts.RATING)
                        .obj.id
                if (!currentUser.hasPermission(user, MODERATE, ratingObject) || !currentUser.hasPermission(user, "UPDATE", reportObject)) {
                    throw ForbiddenException("Missing permission $MODERATE")
                }
            }
        }
    }

    private suspend fun score(
        change: RecordWrite,
        user: AuthenticatedUser?,
    ) {
        val org = change.organizationId
        val before = change.before.orEmpty()
        val after = change.attributes.orEmpty()
        val values = if (change.kind == RecordChangeKind.DELETED) before else after
        if (change.kind == RecordChangeKind.UPDATED) {
            val changed = SCORE_IDENTITY.filter { !same(before[it], after[it]) }
            if (changed.isNotEmpty()) throw ConflictException("What a score is about does not change: ${changed.joinToString()}")
        }
        if (change.kind != RecordChangeKind.CREATED && change.kind != RecordChangeKind.UPDATED && change.kind != RecordChangeKind.DELETED) {
            throw ConflictException("A score has no transitions")
        }
        val student = uuidOf(values[RatingFacts.STUDENT])
        if (user != null && student != user.userId) throw ForbiddenException("Only its author changes a score")
        val rating = uuidOf(values[RatingFacts.RATING])?.let { facts.rating(org, it) } ?: invalid(RatingFacts.RATING, "does not exist")
        if (change.kind != RecordChangeKind.DELETED) {
            if (rating.student != student) invalid(RatingFacts.STUDENT, "must be the student of the rating")
            val value = after[VALUE]?.toString()?.trim()?.toBigDecimalOrNull()
            if (value == null || value.stripTrailingZeros().scale() > 0 || value.toInt() !in SCALE) invalid(VALUE, "must be a whole number from 1 to 5")
            if (change.kind == RecordChangeKind.CREATED) {
                val dimension = uuidOf(after[DIMENSION])
                val known = facts.dimensions(org).firstOrNull { it.id == dimension }
                if (known == null || !known.active || known.target != rating.target) {
                    invalid(DIMENSION, "must be an active dimension for the target of the rating")
                }
            }
        }
        if (user != null) requireOpen(org, rating.offering)
    }

    private suspend fun requireOpen(
        org: UUID,
        offering: UUID?,
    ) {
        val found = offering?.let { facts.offering(org, it) }
        if (found == null || !RatingWindow.isOpen(found.end, found.zone, properties.windowAfterEnd, clock)) {
            throw ConflictException(WINDOW_CLOSED)
        }
    }

    private fun invalid(
        field: String,
        reason: String,
    ): Nothing = throw ValidationException("Invalid rating", field, reason)

    companion object {
        // el destino docente: DOCENCIA, para no escribir nunca un nombre de rol (ArchitectureTest)
        const val TEACHER = "DOCENCIA"
        const val RESOURCE = "RECURSO"
        const val COMMENT = "comentario"
        const val MODERATION = "estado_moderacion"
        const val PENDING = "PENDIENTE"
        const val PUBLISHED = "PUBLICADA"
        const val HIDDEN = "OCULTA"
        const val MODERATE = "MODERAR"
        const val VALUE = "valor"
        const val DIMENSION = "dimension"
        const val WINDOW_CLOSED = "The rating window of this offering is closed"
        const val REASON = "motivo_moderacion"
        const val MODERATED_BY = "moderada_por"
        const val MODERATED_AT = "moderada_en"
        const val APPEAL = "apelacion"
        const val APPEALED_AT = "apelada_en"
        const val DECIDED_BY = "apelacion_decidida_por"
        const val APPEAL_RESULT = "apelacion_resultado"
        const val TOKEN = "ficha"
        const val REPORT = "denuncia_valoracion"
        const val REPORTED = "valoracion_id"
        const val REPORTER = "denunciante_id"
        val GUARDED = setOf(RatingFacts.RATING, RatingFacts.SCORE, REPORT)
        val REASONS = listOf("ATAQUE_PERSONAL", "DATOS_PERSONALES", "SPAM", "FUERA_DE_TEMA", "CONFLICTO_DE_INTERES", "ILEGAL")
        val APPEAL_FIELDS = setOf(APPEAL, APPEALED_AT)
        val DECISION_FIELDS = setOf(DECIDED_BY, APPEAL_RESULT)
        val MODERATOR_FIELDS = setOf(REASON, MODERATED_BY, MODERATED_AT) + DECISION_FIELDS
        val MODERATED = setOf(PUBLISHED, HIDDEN)
        val RATING_ENROLLMENTS = setOf("ACTIVA", "FINALIZADA")
        val SCALE = 1..5
        val IDENTITY =
            listOf(
                RatingFacts.STUDENT,
                RatingFacts.ENROLLMENT,
                RatingFacts.OFFERING_FIELD,
                RatingFacts.TARGET,
                RatingFacts.ASSIGNMENT,
                RatingFacts.RESOURCE_VERSION,
                TOKEN,
            )
        val SCORE_IDENTITY = listOf(RatingFacts.STUDENT, RatingFacts.RATING, DIMENSION)

        // un UUID venga como UUID, como relación expandida ({"id": …}) o como texto; otra cosa, null
        fun uuidOf(value: Any?): UUID? =
            when (value) {
                null -> null
                is UUID -> value
                else -> mapOf("v" to value).ref("v")?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            }

        // el docente o la versión de recurso valorados
        fun targetOf(attributes: Map<String, Any?>): UUID? = uuidOf(attributes[RatingFacts.ASSIGNMENT]) ?: uuidOf(attributes[RatingFacts.RESOURCE_VERSION])

        // el mismo valor: como UUID si alguno lo es (una relación llega como id o expandida), si no como texto
        fun same(
            a: Any?,
            b: Any?,
        ): Boolean {
            val ua = uuidOf(a)
            val ub = uuidOf(b)
            if (ua != null || ub != null) return ua == ub
            return a?.toString()?.trim().orEmpty() == b?.toString()?.trim().orEmpty()
        }

        // un texto sin espacios a los lados; vacío es null
        fun text(
            attributes: Map<String, Any?>,
            name: String,
        ): String? = attributes[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

// después de las guardas del temario (course.GUARD_ORDER, 300), que reputation no usa
const val RATING_GUARD_ORDER = 310
