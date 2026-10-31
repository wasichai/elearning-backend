package elearning.course

import elearning.common.listAll
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
import java.time.Instant
import java.util.UUID

// el flujo editorial de version_curso y la inmutabilidad de su temario, para TODA escritura: la API genérica de
// registros de Wasichai incluida. las acciones declaradas (PUBLICAR, APROBAR…) no protegen nada por sí solas: se
// conceden y se consultan, y es esta guarda la que las exige (hallazgo alto de la revisión de seguridad, E10.1).
//
// - solo transiciones legales, cada una con su acción; saltarse la revisión no es una transición
// - nadie aprueba una versión de la que es autor (autor_id), tampoco el administrador
// - el contenido solo cambia en BORRADOR y solo lo cambia su autor; en revisión, aprobada, publicada o retirada no se
//   mueve (ni su temario)
// - créditos aprobados y fecha de publicación solo los pone quien tiene la acción correspondiente
// - la plataforma (userId nulo) es código de confianza y no pasa por aquí
@Component
@Order(GUARD_ORDER)
class EditorialWorkflowGuard(
    private val currentUser: CurrentUser,
    // RecordService recibe la lista de guardas: se pide perezosamente para no formar un ciclo
    private val records: ObjectProvider<RecordService>,
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
                if (attributes.text(AUTHOR) != user.userId.toString() && !holds(PUBLISH)) {
                    throw ValidationException("A version is written in its author's name", AUTHOR, "must be your own user id")
                }
                if (attributes[APPROVED_CREDITS] != null) require(APPROVE)
                if (attributes[PUBLISHED_AT] != null) require(PUBLISH)
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
                }
                if (APPROVED_CREDITS in changed) require(APPROVE)
                if (PUBLISHED_AT in changed && to != PUBLISHED) throw ConflictException("$PUBLISHED_AT is set only when publishing")
                val content = changed - setOf(APPROVED_CREDITS, PUBLISHED_AT)
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

    // unidades y lecciones de una versión que no está en borrador no se crean, cambian, mueven ni borran
    private suspend fun curriculum(
        change: RecordWrite,
        versions: List<String>,
    ) {
        for (id in versions.distinct()) {
            val state =
                records
                    .getObject()
                    .get(VERSION, UUID.fromString(id))
                    .attributes
                    .text(STATE) ?: DRAFT
            if (state != DRAFT) throw ConflictException("The curriculum of a version that is $state does not change: create a new version")
        }
    }

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
        const val STATE = "estado"
        const val AUTHOR = "autor_id"
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

        // (desde, hacia) → acciones que la permiten (basta una)
        val TRANSITIONS: Map<Pair<String, String>, Set<String>> =
            mapOf(
                (DRAFT to IN_REVIEW) to setOf(SUBMIT),
                // el autor la retira de revisión o el revisor pide cambios
                (IN_REVIEW to DRAFT) to setOf(SUBMIT, APPROVE),
                (IN_REVIEW to APPROVED) to setOf(APPROVE),
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
