package elearning.assessment

import elearning.common.ref
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import java.util.UUID

// lms.contenido_evaluacion, la copia del temario de evaluaciones que usan los intentos (ADR-0017). se escribe en la misma
// llamada que el registro, sea quien sea quien escribe
@Component
class AssessmentContentListener(
    private val db: DatabaseClient,
) : RecordChangeListener {
    override suspend fun recordChanged(change: RecordChange) {
        val parentField = PARENT[change.objectName] ?: return
        if (change.kind == RecordChangeKind.DELETED) {
            db
                .sql("DELETE FROM lms.contenido_evaluacion WHERE organizacion_id = :org AND objeto = :object AND registro_id = :id")
                .bind("org", change.organizationId)
                .bind("object", change.objectName)
                .bind("id", change.recordId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            return
        }
        val after = change.after.orEmpty()
        val parent = after.ref(parentField) ?: return
        val data = after.filterValues { it is String || it is Number || it is Boolean }
        db
            .sql(
                """
                INSERT INTO lms.contenido_evaluacion (organizacion_id, objeto, registro_id, padre_id, datos)
                VALUES (:org, :object, :id, :parent, CAST(:data AS jsonb))
                ON CONFLICT (organizacion_id, objeto, registro_id) DO UPDATE SET padre_id = EXCLUDED.padre_id, datos = EXCLUDED.datos
                """.trimIndent(),
            ).bind("org", change.organizationId)
            .bind("object", change.objectName)
            .bind("id", change.recordId)
            .bind("parent", UUID.fromString(parent))
            .bind("data", MAPPER.writeValueAsString(data))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    companion object {
        val PARENT = mapOf("evaluacion" to "leccion", "pregunta" to "evaluacion", "opcion" to "pregunta", "criterio_rubrica" to "pregunta")
        val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }
}

data class EvaluationSummary(
    val id: String,
    val title: String,
    val maxAttempts: Int,
    val timeLimitMinutes: Int?,
    val passingPercent: Int,
)

// compone la instantánea de la evaluación de una lección a partir de la copia
@Component
class AssessmentContent(
    private val db: DatabaseClient,
) {
    private data class Row(
        val id: String,
        val parent: String,
        val data: Map<String, Any?>,
    )

    suspend fun snapshot(
        organization: UUID,
        lesson: UUID,
        shuffle: (List<SnapshotOption>) -> List<SnapshotOption> = { it.shuffled() },
    ): AssessmentSnapshot? {
        val evaluation = rows(organization, "evaluacion", listOf(lesson)).singleOrNull() ?: return null
        val questions = rows(organization, "pregunta", listOf(UUID.fromString(evaluation.id)))
        val ids = questions.map { UUID.fromString(it.id) }
        val options = rows(organization, "opcion", ids).groupBy { it.parent }
        val criteria = rows(organization, "criterio_rubrica", ids).groupBy { it.parent }
        val random = evaluation.data["orden_aleatorio"] == true
        return AssessmentSnapshot(
            evaluationId = evaluation.id,
            title = evaluation.data["titulo"].toString(),
            instructions = evaluation.data["instrucciones"]?.toString(),
            maxAttempts = int(evaluation.data["intentos_max"]) ?: 1,
            timeLimitMinutes = int(evaluation.data["tiempo_limite_min"]),
            passingPercent = int(evaluation.data["nota_minima"]) ?: 60,
            showSolutions = evaluation.data["mostrar_soluciones"]?.toString() ?: "NUNCA",
            questions =
                questions.sortedBy { int(it.data["orden"]) ?: 0 }.map { q ->
                    val ordered =
                        options[q.id]
                            .orEmpty()
                            .sortedBy { int(it.data["orden"]) ?: 0 }
                            .map { SnapshotOption(it.id, it.data["texto"].toString(), it.data["correcta"] == true) }
                    SnapshotQuestion(
                        id = q.id,
                        order = int(q.data["orden"]) ?: 0,
                        type = TYPES.getValue(q.data["tipo"].toString()),
                        prompt = q.data["enunciado"].toString(),
                        points = q.data["puntos"].toString().toBigDecimal(),
                        options = if (random) shuffle(ordered) else ordered,
                        rubric =
                            criteria[q.id]
                                .orEmpty()
                                .sortedBy { int(it.data["orden"]) ?: 0 }
                                .map { SnapshotCriterion(it.data["descripcion"].toString(), it.data["puntos"].toString().toBigDecimal()) },
                        acceptedAnswers =
                            q.data["respuestas_aceptadas"]
                                ?.toString()
                                ?.lines()
                                ?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                                .orEmpty(),
                    )
                },
        )
    }

    // la evaluación de cada lección que tiene una, sin componer sus preguntas
    suspend fun summaries(
        organization: UUID,
        lessons: List<UUID>,
    ): Map<String, EvaluationSummary> =
        rows(organization, "evaluacion", lessons).associate {
            it.parent to
                EvaluationSummary(
                    id = it.id,
                    title = it.data["titulo"].toString(),
                    maxAttempts = int(it.data["intentos_max"]) ?: 1,
                    timeLimitMinutes = int(it.data["tiempo_limite_min"]),
                    passingPercent = int(it.data["nota_minima"]) ?: 60,
                )
        }

    private suspend fun rows(
        organization: UUID,
        objectName: String,
        parents: List<UUID>,
    ): List<Row> {
        if (parents.isEmpty()) return emptyList()
        return db
            .sql(
                "SELECT registro_id, padre_id, CAST(datos AS text) AS datos FROM lms.contenido_evaluacion WHERE organizacion_id = :org AND objeto = :object AND padre_id = ANY(:parents)",
            ).bind("org", organization)
            .bind("object", objectName)
            .bind("parents", parents.toTypedArray())
            .map { row, _ ->
                Row(
                    row.get("registro_id", UUID::class.java).toString(),
                    row.get("padre_id", UUID::class.java).toString(),
                    AssessmentContentListener.MAPPER.readValue(row.get("datos", String::class.java), object : TypeReference<Map<String, Any?>>() {}),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    private fun int(value: Any?): Int? = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull()

    companion object {
        val TYPES =
            mapOf(
                "UNICA" to "SINGLE",
                "MULTIPLE" to "MULTIPLE",
                "VERDADERO_FALSO" to "TRUE_FALSE",
                "CORTA" to "SHORT",
                "ABIERTA" to "OPEN",
                "ARCHIVO" to "FILE",
            )
    }
}
