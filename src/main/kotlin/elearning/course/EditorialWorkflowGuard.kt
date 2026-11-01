package elearning.course

import elearning.common.listAll
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// la escritura con la que la bandeja del revisor decide una revisión: el estado de la versión y su fila en
// lms.decision_revision, en la misma transacción (ADR-0021). la API genérica de registros no la lleva (ADR-040 de Wasichai)
class ReviewDecisionWrite : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReviewDecisionWrite>
}

// el flujo editorial de version_curso y la inmutabilidad de su temario, para TODA escritura: la API genérica de
// registros de Wasichai incluida. las acciones declaradas (PUBLICAR, APROBAR…) no protegen nada por sí solas: se
// conceden y se consultan, y es esta guarda la que las exige (hallazgo alto de la revisión de seguridad, E10.1).
//
// - solo transiciones legales, cada una con su acción; saltarse la revisión no es una transición
// - nadie aprueba una versión de la que es autor (autor_id), tampoco el administrador
// - con revisor asignado (revisor_id), solo esa persona decide la revisión: aprobar, pedir cambios o rechazar (ADR-0018 §2).
//   el revisor lo asigna quien publica, nunca el autor de la versión
// - una revisión de version_curso se decide solo en la bandeja, que deja la decisión y su motivo (ADR-0021); la de una
//   version_recurso, que no tiene bandeja, por la API de registros con motivo (X-Change-Reason) salvo al aprobar
// - el contenido solo cambia en BORRADOR y solo lo cambia su autor, también su temario; en revisión, aprobada,
//   publicada o retirada no se mueve (ni su temario)
// - créditos aprobados y fecha de publicación solo los pone quien tiene la acción correspondiente
// - la plataforma (userId nulo) es código de confianza y no pasa por aquí
@Component
@Order(GUARD_ORDER)
class EditorialWorkflowGuard(
    private val currentUser: CurrentUser,
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
    private val metadata: ObjectProvider<MetadataService>,
) : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.userId == null) return
        when (change.objectName) {
            // una versión de recurso sigue el mismo flujo que una de curso (ADR-0012): autor, transiciones con su acción,
            // sin autoaprobación, lo publicado inmutable con su archivo
            VERSION, RESOURCE_VERSION -> version(change, currentUser.require())
            UNIT -> curriculum(change, listOfNotNull(change.before.ref(VERSION), change.attributes.ref(VERSION)))
            LESSON -> curriculum(change, listOfNotNull(change.before.ref(UNIT), change.attributes.ref(UNIT)).mapNotNull { unitVersion(it) })
            RESOURCE_LINK -> resourceLink(change)
            // los entornos de un laboratorio son temario (ADR-0027): se congelan con la versión de su lección
            LAB_ENVIRONMENT -> curriculum(change, refs(change, LESSON).mapNotNull { lessonVersion(it) })
            RESOURCE -> resource(change)
            // las evaluaciones son temario (ADR-0016): se congelan con la versión de su lección
            EVALUATION -> {
                curriculum(change, refs(change, LESSON).mapNotNull { lessonVersion(it) })
                oneEvaluationPerLesson(change)
            }
            QUESTION -> curriculum(change, refs(change, EVALUATION).mapNotNull { evaluationVersion(it) })
            OPTION, RUBRIC -> curriculum(change, refs(change, QUESTION).mapNotNull { questionVersion(it) })
        }
    }

    private suspend fun version(
        change: RecordWrite,
        user: AuthenticatedUser,
    ) {
        suspend fun holds(action: String) = currentUser.hasPermission(user, action, change.objectId)

        suspend fun require(action: String) {
            if (!holds(action)) throw ForbiddenException("Missing permission $action")
        }
        when (change.kind) {
            RecordChangeKind.CREATED -> {
                val attributes = change.attributes.orEmpty()
                val state = attributes.text(STATE) ?: DRAFT
                if (state != DRAFT) require(ACTION_TO_ENTER.getValue(state))
                // nacer aprobada es una aprobación sin revisión: la de un curso sale de la bandeja; nadie se aprueba lo suyo
                if (state == APPROVED && change.objectName == VERSION) throw ForbiddenException(DECIDED_IN_THE_INBOX)
                if (state == APPROVED && attributes.text(AUTHOR) == user.userId.toString()) {
                    throw ForbiddenException("A version is approved by someone other than its author")
                }
                if (attributes.text(AUTHOR) != user.userId.toString() && !holds(PUBLISH)) {
                    throw ValidationException("A version is written in its author's name", AUTHOR, "must be your own user id")
                }
                if (attributes[APPROVED_CREDITS] != null) require(APPROVE)
                if (attributes[PUBLISHED_AT] != null) require(PUBLISH)
                attributes.text(REVIEWER)?.let { reviewer(it, attributes.text(AUTHOR), state, user, holds(PUBLISH)) }
            }
            RecordChangeKind.DELETED -> {
                if (change.before.text(STATE) ?: DRAFT != DRAFT) throw ConflictException("Only a draft version can be deleted")
            }
            else -> {
                val before = change.before.orEmpty()
                val attributes = change.attributes.orEmpty()
                val from = before.text(STATE) ?: DRAFT
                val to = if (attributes.containsKey(STATE)) attributes.text(STATE) ?: DRAFT else from
                val changed = attributes.keys.filter { it != STATE && (it.startsWith(LINK) || !same(attributes[it], before[it])) }.toSet()

                if (AUTHOR in changed) throw ConflictException("The author of a version does not change")
                if (from != to) {
                    val allowed = TRANSITIONS[from to to] ?: throw ConflictException("Transition $from -> $to is not allowed")
                    if (allowed.none { holds(it) }) throw ForbiddenException("Missing permission ${allowed.joinToString(" or ")}")
                    if (to == APPROVED && before.text(AUTHOR).let { it == null || it == user.userId.toString() }) {
                        throw ForbiddenException("A version is approved by someone other than its author")
                    }
                    if (from == IN_REVIEW && decides(before, to, user, holds(APPROVE))) decision(change, to)
                }
                if (APPROVED_CREDITS in changed) require(APPROVE)
                if (PUBLISHED_AT in changed && to != PUBLISHED) throw ConflictException("$PUBLISHED_AT is set only when publishing")
                if (REVIEWER in changed) reviewer(attributes.text(REVIEWER), before.text(AUTHOR), from, user, holds(PUBLISH))
                val content = changed - setOf(APPROVED_CREDITS, PUBLISHED_AT, REVIEWER)
                if (content.isNotEmpty() && from != DRAFT) {
                    throw ConflictException("A version that is $from does not change: create a new version (${content.sorted().joinToString()})")
                }
                // un borrador lo escribe su autor: UPDATE sobre el objeto (que el revisor necesita para aprobar) no basta
                // para reescribir el borrador de otro. quien publica (dirección editorial) sí puede
                if (content.isNotEmpty() && before.text(AUTHOR) != user.userId.toString() && !holds(PUBLISH)) {
                    throw ForbiddenException("Only the author edits a draft version")
                }
            }
        }
    }

    // salir de revisión es decidirla (true), salvo que el autor la retire a borrador. decide quien aprueba y no es el autor;
    // con revisor asignado, nadie más que ese revisor (tampoco el administrador: primero se reasigna)
    private fun decides(
        before: Map<String, Any?>,
        to: String,
        user: AuthenticatedUser,
        approver: Boolean,
    ): Boolean {
        val me = user.userId.toString()
        val author = before.text(AUTHOR)
        if (to == DRAFT && author == me) return false
        if (author == null || author == me) throw ForbiddenException("A review is decided by someone other than the author")
        if (!approver) throw ForbiddenException("Missing permission $APPROVE")
        val assigned = before.text(REVIEWER)
        if (assigned != null && assigned != me) throw ForbiddenException("Only the assigned reviewer decides this review")
        return true
    }

    // una decisión deja rastro (ADR-0021): la de un curso, su fila en lms.decision_revision, que solo escribe la bandeja en
    // la misma transacción; la de un recurso, su motivo en el audit_log de Wasichai, obligatorio salvo al aprobar
    private suspend fun decision(
        change: RecordWrite,
        to: String,
    ) {
        if (change.objectName == VERSION && currentCoroutineContext()[ReviewDecisionWrite] == null) {
            throw ForbiddenException(DECIDED_IN_THE_INBOX)
        }
        if (change.objectName == RESOURCE_VERSION && to != APPROVED && change.reason.isNullOrBlank()) {
            throw ValidationException("A review decision other than approving needs a reason", "reason", "send the X-Change-Reason header")
        }
    }

    // asignar o quitar el revisor: lo hace quien publica, mientras la versión no está decidida, nunca al autor ni a sí
    // mismo (sin autoasignación de alcance, docs/security)
    private fun reviewer(
        reviewer: String?,
        author: String?,
        state: String,
        user: AuthenticatedUser,
        publisher: Boolean,
    ) {
        if (!publisher) throw ForbiddenException("Missing permission $PUBLISH")
        if (reviewer != null && reviewer == user.userId.toString()) throw ForbiddenException("Nobody assigns themselves as reviewer")
        if (state != DRAFT && state != IN_REVIEW) throw ConflictException("The reviewer of a version that is $state does not change")
        if (reviewer != null && reviewer == author) {
            throw ValidationException("The author does not review their own version", REVIEWER, "must not be the author")
        }
    }

    // unidades y lecciones de una versión que no está en borrador no se crean, cambian, mueven ni borran. el temario de
    // un borrador lo escribe su autor, como el borrador mismo, o quien publica (revisión de la Fase 2, L8)
    private suspend fun curriculum(
        change: RecordWrite,
        versions: List<String>,
    ) {
        val user = currentUser.require()
        for (id in versions.distinct()) {
            val version =
                records
                    .getObject()
                    .get(VERSION, UUID.fromString(id))
                    .attributes
            val state = version.text(STATE) ?: DRAFT
            if (state != DRAFT) throw ConflictException("The curriculum of a version that is $state does not change: create a new version")
            if (version.text(AUTHOR) != user.userId.toString() && !currentUser.hasPermission(user, PUBLISH, versionObject())) {
                throw ForbiddenException("Only the author edits the curriculum of a draft version")
            }
        }
    }

    // PUBLICAR se concede sobre version_curso, no sobre la unidad o la lección que se escribe
    private suspend fun versionObject(): UUID =
        metadata
            .getObject()
            .definitionOf(VERSION)
            .obj.id

    // un recurso con alguna versión fuera de borrador ya lo ven revisores o estudiantes: su título y su tipo (que decide
    // cómo se muestra el archivo) no cambian ni se borra; para cambiarlo, un recurso nuevo (revisión de la Fase 2)
    private suspend fun resource(change: RecordWrite) {
        if (change.kind == RecordChangeKind.CREATED) return
        val before = change.before.orEmpty()
        val attributes = change.attributes.orEmpty()
        if (change.kind != RecordChangeKind.DELETED && attributes.keys.all { same(attributes[it], before[it]) }) return
        var cursor: String? = null
        do {
            val page =
                records.getObject().list(
                    RESOURCE_VERSION,
                    RecordQuery(
                        page = PageRequest(0, PageRequest.MAX_SIZE),
                        filters = mapOf(RESOURCE to change.recordId.toString()),
                        count = false,
                        after = cursor,
                    ),
                )
            if (page.content.any { (it.attributes.text(STATE) ?: DRAFT) != DRAFT }) {
                throw ConflictException("A resource with versions beyond draft does not change: create a new resource")
            }
            cursor = page.nextCursor
        } while (cursor != null)
    }

    // el recurso de una lección: solo en temario en borrador y solo una versión de recurso ya publicada
    private suspend fun resourceLink(change: RecordWrite) {
        val lessons = listOfNotNull(change.before.ref(LESSON), change.attributes.ref(LESSON))
        curriculum(change, lessons.mapNotNull { lessonVersion(it) })
        val resource = change.attributes.ref(RESOURCE_VERSION) ?: return
        val state =
            records
                .getObject()
                .get(RESOURCE_VERSION, UUID.fromString(resource))
                .attributes
                .text(STATE)
        if (state != PUBLISHED) throw ConflictException("A lesson only uses published resource versions")
    }

    // una lección tiene a lo sumo una evaluación: Wasichai no admite una restricción única de un solo campo de relación
    private suspend fun oneEvaluationPerLesson(change: RecordWrite) {
        if (change.kind == RecordChangeKind.DELETED) return
        val lesson = change.attributes.ref(LESSON) ?: return
        val others = records.getObject().listAll(EVALUATION, mapOf(LESSON to lesson)).filter { it.id != change.recordId.toString() }
        if (others.isNotEmpty()) throw ConflictException("This lesson already has an evaluation")
    }

    private fun refs(
        change: RecordWrite,
        field: String,
    ): List<String> = listOfNotNull(change.before.ref(field), change.attributes.ref(field))

    private suspend fun evaluationVersion(evaluation: String): String? =
        records
            .getObject()
            .get(EVALUATION, UUID.fromString(evaluation))
            .attributes
            .ref(LESSON)
            ?.let { lessonVersion(it) }

    private suspend fun questionVersion(question: String): String? =
        records
            .getObject()
            .get(QUESTION, UUID.fromString(question))
            .attributes
            .ref(EVALUATION)
            ?.let { evaluationVersion(it) }

    private suspend fun lessonVersion(lesson: String): String? =
        records
            .getObject()
            .get(LESSON, UUID.fromString(lesson))
            .attributes
            .ref(UNIT)
            ?.let { unitVersion(it) }

    private suspend fun unitVersion(unit: String): String? =
        records
            .getObject()
            .get(UNIT, UUID.fromString(unit))
            .attributes
            .ref(VERSION)

    companion object {
        const val VERSION = "version_curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val RESOURCE = "recurso"
        const val EVALUATION = "evaluacion"
        const val QUESTION = "pregunta"
        const val OPTION = "opcion"
        const val RUBRIC = "criterio_rubrica"
        const val RESOURCE_VERSION = "version_recurso"
        const val RESOURCE_LINK = "recurso_leccion"
        const val LAB_ENVIRONMENT = "entorno_laboratorio"
        const val STATE = "estado"
        const val AUTHOR = "autor_id"
        const val REVIEWER = "revisor_id"
        const val APPROVED_CREDITS = "creditos_aprobados"
        const val PUBLISHED_AT = "publicada_en"
        const val LINK = "rel:"

        const val DRAFT = "BORRADOR"
        const val IN_REVIEW = "EN_REVISION"
        const val APPROVED = "APROBADA"
        const val PUBLISHED = "PUBLICADA"
        const val RETIRED = "RETIRADA"

        const val SUBMIT = "ENVIAR_A_REVISION"
        const val APPROVE = "APROBAR"
        const val PUBLISH = "PUBLICAR"
        const val RETIRE = "RETIRAR"

        const val DECIDED_IN_THE_INBOX = "A course version review is decided in the review inbox: POST /api/lms/review/versions/{id}/decision"

        // (desde, hacia) → acciones que la permiten (basta una)
        val TRANSITIONS: Map<Pair<String, String>, Set<String>> =
            mapOf(
                (DRAFT to IN_REVIEW) to setOf(SUBMIT),
                // el autor la retira de revisión o el revisor pide cambios
                (IN_REVIEW to DRAFT) to setOf(SUBMIT, APPROVE),
                (IN_REVIEW to APPROVED) to setOf(APPROVE),
                // el revisor la rechaza (ADR-0018 §2): queda retirada, con su decisión y su motivo en lms.decision_revision
                (IN_REVIEW to RETIRED) to setOf(APPROVE),
                (APPROVED to PUBLISHED) to setOf(PUBLISH),
                (PUBLISHED to RETIRED) to setOf(RETIRE),
            )

        // crear directamente en un estado que no es borrador (importar historia) pide la acción de ese estado
        val ACTION_TO_ENTER: Map<String, String> =
            mapOf(IN_REVIEW to SUBMIT, APPROVED to APPROVE, PUBLISHED to PUBLISH, RETIRED to RETIRE)
    }
}

const val GUARD_ORDER = 300

private val BOOLEANS = setOf("true", "false")

private fun Map<String, Any?>?.text(name: String): String? = this?.get(name)?.toString()?.takeIf { it.isNotBlank() }

// una relación puede llegar como id o expandida
private fun Map<String, Any?>?.ref(name: String): String? =
    when (val v = this?.get(name)) {
        is Map<*, *> -> v["id"]?.toString()
        null -> null
        else -> v.toString().takeIf { it.isNotBlank() }
    }

// lo enviado frente a lo guardado: un PUT reenvía todo y no por eso cambia. números por valor, instantes por instante
private fun same(
    sent: Any?,
    stored: Any?,
): Boolean {
    val a = sent?.toString()?.takeIf { it.isNotBlank() }
    val b = stored?.toString()?.takeIf { it.isNotBlank() }
    if (a == null || b == null) return a == b
    a.toBigDecimalOrNull()?.let { x -> b.toBigDecimalOrNull()?.let { y -> return x.compareTo(y) == 0 } }
    runCatching { return Instant.parse(a) == Instant.parse(b) }
    return a.equals(b, ignoreCase = a.lowercase() in BOOLEANS && b.lowercase() in BOOLEANS)
}
