package elearning.authoring

import elearning.common.listAll
import elearning.common.listAllIn
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

// copia una versión a un borrador de quien la pide (ADR-0018 §1): su temario, sus evaluaciones y sus enlaces a recursos, con
// identidades nuevas. todo como la persona, así que sus permisos y la guarda editorial aplican. el original no se toca.
// devuelve la identidad del borrador; su detalle lo lee el estudio (StudioReader), ya confirmada la copia
@Service
class NewVersionService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val transactions: TransactionalOperator,
) {
    suspend fun copy(
        courseId: UUID,
        request: NewVersionRequest,
    ): UUID {
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
            // nivel a nivel: cada uno se lee de una vez y se escribe en lote, no registro a registro (L4 de #216)
            val units = records.listAll(UNIT, mapOf(VERSION to source.id))
            val newUnits = copyAll(units, UNIT, VERSION, mapOf(source.id to created.id))
            val lessons = records.listAllIn(LESSON, UNIT, units.map { it.id })
            val newLessons = copyAll(lessons, LESSON, UNIT, newUnits)
            val lessonIds = lessons.map { it.id }
            records.createAll(
                LINK,
                records.listAllIn(LINK, LESSON, lessonIds).map { link ->
                    RecordRequest(
                        mapOf(
                            LESSON to newLessons.getValue(link.attributes.ref(LESSON)!!),
                            RESOURCE_VERSION to link.attributes.ref(RESOURCE_VERSION),
                            "orden" to link.attributes["orden"],
                        ),
                    )
                },
            )
            val evaluations = records.listAllIn(EVALUATION, LESSON, lessonIds)
            val newEvaluations = copyAll(evaluations, EVALUATION, LESSON, newLessons, drop = setOf("codigo"))
            val questions = records.listAllIn(QUESTION, EVALUATION, evaluations.map { it.id })
            val newQuestions = copyAll(questions, QUESTION, EVALUATION, newEvaluations)
            val questionIds = questions.map { it.id }
            copyAll(records.listAllIn(OPTION, QUESTION, questionIds), OPTION, QUESTION, newQuestions)
            copyAll(records.listAllIn(RUBRIC, QUESTION, questionIds), RUBRIC, QUESTION, newQuestions)
            UUID.fromString(created.id)
        }
    }

    // copia los registros bajo sus padres nuevos, en orden y en un lote; devuelve la identidad nueva de cada uno
    private suspend fun copyAll(
        sources: List<RecordResponse>,
        objectName: String,
        parentField: String,
        parents: Map<String, String>,
        drop: Set<String> = emptySet(),
    ): Map<String, String> {
        val requests =
            sources.map {
                RecordRequest(scalars(it, drop + parentField) + mapOf(parentField to parents.getValue(it.attributes.ref(parentField)!!)))
            }
        return sources.map { it.id }.zip(records.createAll(objectName, requests).map { it.id }).toMap()
    }

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
