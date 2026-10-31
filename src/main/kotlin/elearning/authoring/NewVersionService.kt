package elearning.authoring

import elearning.common.listAll
import elearning.common.ref
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.util.UUID

data class NewVersionRequest(
    val fromVersionId: String? = null,
)

data class NewVersionResult(
    val id: String,
    val number: Int,
    val status: String,
)

// copia una versión a un borrador de quien la pide (ADR-0018 §1): su temario, sus evaluaciones y sus enlaces a recursos, con
// identidades nuevas. todo como la persona, así que sus permisos y la guarda editorial aplican. el original no se toca
@Service
class NewVersionService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val transactions: TransactionalOperator,
) {
    suspend fun copy(
        courseId: UUID,
        request: NewVersionRequest,
    ): NewVersionResult {
        val me = currentUser.require().userId.toString()
        val course = records.get(COURSE, courseId)
        val versions = records.listAll(VERSION, mapOf(COURSE to course.id))
        val source =
            if (request.fromVersionId != null) {
                versions.firstOrNull { it.id == request.fromVersionId }
                    ?: throw ValidationException("Invalid version", "fromVersionId", "must be a version of this course")
            } else {
                versions.maxByOrNull { number(it) } ?: throw ValidationException("Invalid version", "fromVersionId", "this course has no versions")
            }
        val next = versions.maxOf { number(it) } + 1
        return transactions.executeAndAwait {
            val attributes = scalars(source, DRAFT_DROPS) + mapOf(COURSE to course.id, "numero" to next, "autor_id" to me, "estado" to "BORRADOR")
            val created = records.create(VERSION, RecordRequest(attributes))
            for (unit in records.listAll(UNIT, mapOf(VERSION to source.id))) {
                val newUnit = copyChild(unit, UNIT, VERSION, created.id)
                for (lesson in records.listAll(LESSON, mapOf(UNIT to unit.id))) {
                    val newLesson = copyChild(lesson, LESSON, UNIT, newUnit.id)
                    for (link in records.listAll(LINK, mapOf(LESSON to lesson.id))) {
                        records.create(
                            LINK,
                            RecordRequest(
                                mapOf(
                                    LESSON to newLesson.id,
                                    RESOURCE_VERSION to link.attributes.ref(RESOURCE_VERSION),
                                    "orden" to link.attributes["orden"],
                                ),
                            ),
                        )
                    }
                    for (evaluation in records.listAll(EVALUATION, mapOf(LESSON to lesson.id))) {
                        copyEvaluation(evaluation, newLesson.id)
                    }
                }
            }
            NewVersionResult(created.id, next, "BORRADOR")
        }
    }

    private suspend fun copyEvaluation(
        evaluation: RecordResponse,
        lesson: String,
    ) {
        val copied = records.create(EVALUATION, RecordRequest(scalars(evaluation, setOf(LESSON, "codigo")) + mapOf(LESSON to lesson)))
        for (question in records.listAll(QUESTION, mapOf(EVALUATION to evaluation.id))) {
            val newQuestion = copyChild(question, QUESTION, EVALUATION, copied.id)
            for (option in records.listAll(OPTION, mapOf(QUESTION to question.id))) copyChild(option, OPTION, QUESTION, newQuestion.id)
            for (criterion in records.listAll(RUBRIC, mapOf(QUESTION to question.id))) copyChild(criterion, RUBRIC, QUESTION, newQuestion.id)
        }
    }

    private suspend fun copyChild(
        record: RecordResponse,
        objectName: String,
        parentField: String,
        parent: String,
    ) = records.create(objectName, RecordRequest(scalars(record, setOf(parentField)) + mapOf(parentField to parent)))

    // los valores propios del registro, sin los vacíos ni lo que no se hereda
    private fun scalars(
        record: RecordResponse,
        drop: Set<String>,
    ): Map<String, Any?> = record.attributes.filter { (k, v) -> k !in drop && v != null && v !is Map<*, *> }

    private fun number(version: RecordResponse) = (version.attributes["numero"] as? Number)?.toInt() ?: 0

    companion object {
        const val COURSE = "curso"
        const val VERSION = "version_curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val LINK = "recurso_leccion"
        const val RESOURCE_VERSION = "version_recurso"
        const val EVALUATION = "evaluacion"
        const val QUESTION = "pregunta"
        const val OPTION = "opcion"
        const val RUBRIC = "criterio_rubrica"

        // lo decidido en la versión de origen no se hereda: ni su estado, ni su publicación, ni sus créditos aprobados
        val DRAFT_DROPS = setOf(COURSE, "numero", "autor_id", "estado", "publicada_en", "creditos_aprobados", "revisor_id")
    }
}
