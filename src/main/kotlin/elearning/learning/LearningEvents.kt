package elearning.learning

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class LearningEvent(
    val organizationId: UUID,
    val enrollmentId: UUID,
    val lessonId: UUID,
    val studentId: UUID,
    val type: String,
    val clientEventId: UUID,
)

// lms.evento_aprendizaje (V4): solo INSERT. idempotente por el identificador que pone el cliente; la base impide cambiar
// o borrar lo guardado
@Repository
class LearningEvents(
    private val db: DatabaseClient,
) {
    // true si era nuevo; false si ese estudiante ya había enviado ese mismo evento
    suspend fun record(event: LearningEvent): Boolean =
        db
            .sql(
                """
                INSERT INTO lms.evento_aprendizaje (organizacion_id, matricula_id, leccion_id, estudiante_id, tipo, cliente_evento_id)
                VALUES (:org, :enrollment, :lesson, :student, :type, :client)
                ON CONFLICT (organizacion_id, estudiante_id, cliente_evento_id) DO NOTHING
                """.trimIndent(),
            ).bind("org", event.organizationId)
            .bind("enrollment", event.enrollmentId)
            .bind("lesson", event.lessonId)
            .bind("student", event.studentId)
            .bind("type", event.type)
            .bind("client", event.clientEventId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun count(
        enrollment: UUID,
        lesson: UUID,
    ): Int =
        db
            .sql("SELECT count(*) AS n FROM lms.evento_aprendizaje WHERE matricula_id = :e AND leccion_id = :l")
            .bind("e", enrollment)
            .bind("l", lesson)
            .map { row, _ -> row.get("n", Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()
}
