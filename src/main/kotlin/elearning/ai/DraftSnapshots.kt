package elearning.ai

import elearning.common.listAll
import elearning.common.listAllIn
import elearning.common.ref
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import java.math.BigDecimal

// lee el borrador que valida DraftValidator: la versión y su temario, por niveles (no lección a lección), como lo vea quien
// llama (la plataforma en el trabajo, el autor al enviar a revisión). solo los campos que se validan, cada lista ordenada:
// el mismo contenido da la misma instantánea y el mismo hash
@Component
class DraftSnapshots(
    // RecordService recibe la lista de guardas, y GeneratedDraftGuard usa esta clase: se pide perezosamente
    private val records: ObjectProvider<RecordService>,
) {
    suspend fun of(version: RecordResponse): DraftSnapshot {
        val records = records.getObject()
        val units = records.listAll(UNIT, mapOf(VERSION to version.id))
        val lessons = records.listAllIn(LESSON, UNIT, units.map { it.id })
        val lessonIds = lessons.map { it.id }
        val labs = records.listAllIn(LAB, LESSON, lessonIds).groupBy { it.attributes.ref(LESSON) }
        val links = records.listAllIn(LINK, LESSON, lessonIds).groupingBy { it.attributes.ref(LESSON) }.eachCount()
        val evaluations = records.listAllIn(EVALUATION, LESSON, lessonIds)
        val questions = records.listAllIn(QUESTION, EVALUATION, evaluations.map { it.id })
        val options = records.listAllIn(OPTION, QUESTION, questions.map { it.id }).groupBy { it.attributes.ref(QUESTION) }
        val questionsOf = questions.groupBy { it.attributes.ref(EVALUATION) }
        val evaluationsOf = evaluations.groupBy { it.attributes.ref(LESSON) }
        val lessonsOf = lessons.groupBy { it.attributes.ref(UNIT) }
        return DraftSnapshot(
            versionId = version.id,
            texts = texts(version, VERSION_TEXTS),
            units =
                units.sortedWith(ORDERED).map { unit ->
                    DraftUnit(
                        id = unit.id,
                        order = int(unit),
                        title = text(unit, TITLE).orEmpty(),
                        hours = text(unit, "horas_estimadas")?.toBigDecimalOrNull()?.let(::plain),
                        sourceRef = text(unit, SOURCE),
                        texts = texts(unit, UNIT_TEXTS),
                        lessons =
                            lessonsOf[unit.id].orEmpty().sortedWith(ORDERED).map { lesson ->
                                DraftLesson(
                                    id = lesson.id,
                                    order = int(lesson),
                                    title = text(lesson, TITLE).orEmpty(),
                                    type = text(lesson, "tipo").orEmpty(),
                                    minutes = (lesson.attributes["duracion_minutos"] as? Number)?.toInt(),
                                    sourceRef = text(lesson, SOURCE),
                                    texts = texts(lesson, LESSON_TEXTS),
                                    resources = links[lesson.id] ?: 0,
                                    labs =
                                        labs[lesson.id].orEmpty().sortedWith(ORDERED).map {
                                            DraftLab(it.id, text(it, TITLE).orEmpty(), it.attributes["enlace"]?.toString().orEmpty())
                                        },
                                    evaluations =
                                        evaluationsOf[lesson.id].orEmpty().sortedBy { it.id }.map { evaluation ->
                                            DraftEvaluation(
                                                id = evaluation.id,
                                                title = text(evaluation, TITLE).orEmpty(),
                                                instructions = text(evaluation, "instrucciones"),
                                                questions =
                                                    questionsOf[evaluation.id].orEmpty().sortedWith(ORDERED).map { question ->
                                                        DraftQuestion(
                                                            id = question.id,
                                                            order = int(question),
                                                            type = text(question, "tipo").orEmpty(),
                                                            statement = text(question, "enunciado").orEmpty(),
                                                            acceptedAnswers = text(question, "respuestas_aceptadas"),
                                                            options =
                                                                options[question.id].orEmpty().sortedWith(ORDERED).map {
                                                                    DraftOption(
                                                                        id = it.id,
                                                                        order = int(it),
                                                                        text = text(it, "texto").orEmpty(),
                                                                        correct = it.attributes["correcta"]?.toString() == "true",
                                                                    )
                                                                },
                                                        )
                                                    },
                                            )
                                        },
                                )
                            },
                    )
                },
        )
    }

    private fun text(
        record: RecordResponse,
        field: String,
    ): String? = record.attributes[field]?.toString()?.takeIf { it.isNotBlank() }

    // los no vacíos, por nombre
    private fun texts(
        record: RecordResponse,
        fields: List<String>,
    ): Map<String, String> = fields.sorted().mapNotNull { f -> text(record, f)?.let { f to it } }.toMap(LinkedHashMap())

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private companion object {
        const val VERSION = "version_curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val LAB = "entorno_laboratorio"
        const val LINK = "recurso_leccion"
        const val EVALUATION = "evaluacion"
        const val QUESTION = "pregunta"
        const val OPTION = "opcion"
        const val TITLE = "titulo"
        const val SOURCE = "referencia_fuente"
        val VERSION_TEXTS =
            listOf("objetivos", "resultados_aprendizaje", "requisitos", "sistema_evaluacion", "certificacion", "fuentes", "conocimientos_previos_claves")
        val UNIT_TEXTS = listOf("descripcion", "objetivos")
        val LESSON_TEXTS = listOf("resumen", "objetivo", "requisitos", "hitos", "criterios_aceptacion", "entregables")

        fun int(record: RecordResponse) = (record.attributes["orden"] as? Number)?.toInt() ?: 0

        val ORDERED: Comparator<RecordResponse> = compareBy<RecordResponse> { int(it) }.thenBy { it.id }
    }
}
