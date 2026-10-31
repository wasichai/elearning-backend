package elearning.assessment

import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class Attempt(
    val id: UUID,
    val organizationId: UUID,
    val offeringId: UUID,
    val enrollmentId: UUID,
    val lessonId: UUID,
    val studentId: UUID,
    val number: Int,
    val status: String,
    val snapshot: AssessmentSnapshot,
    val answers: List<AttemptAnswer>,
    val startedAt: Instant,
    val deadline: Instant?,
    val submittedAt: Instant?,
    val result: GradeResult?,
)

// lms.intento, siempre acotado a la organización
@Repository
class AttemptStore(
    private val db: DatabaseClient,
) {
    private val mapper = AssessmentContentListener.MAPPER

    suspend fun insert(
        organization: UUID,
        offering: UUID,
        enrollment: UUID,
        lesson: UUID,
        student: UUID,
        number: Int,
        snapshot: AssessmentSnapshot,
        startedAt: Instant,
        deadline: Instant?,
    ): Attempt {
        val spec =
            db
                .sql(
                    """
                    INSERT INTO lms.intento (organizacion_id, edicion_id, matricula_id, leccion_id, estudiante_id, numero, instantanea, iniciado_en, vence_en)
                    VALUES (:org, :offering, :enrollment, :lesson, :student, :number, CAST(:snapshot AS jsonb), :startedAt, :deadline)
                    RETURNING $COLUMNS
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("offering", offering)
                .bind("enrollment", enrollment)
                .bind("lesson", lesson)
                .bind("student", student)
                .bind("number", number)
                .bind("snapshot", mapper.writeValueAsString(snapshot))
                .bind("startedAt", startedAt.atOffset(java.time.ZoneOffset.UTC))
        return (
            if (deadline ==
                null
            ) {
                spec.bindNull("deadline", OffsetDateTime::class.java)
            } else {
                spec.bind("deadline", deadline.atOffset(java.time.ZoneOffset.UTC))
            }
        ).map { row, _ -> attempt(row) }
            .one()
            .awaitSingle()
    }

    // la fila bloqueada hasta el final de la transacción: guardar y enviar no se pisan
    suspend fun lock(
        organization: UUID,
        id: UUID,
    ): Attempt? =
        db
            .sql("SELECT $COLUMNS FROM lms.intento WHERE organizacion_id = :org AND id = :id FOR UPDATE")
            .bind("org", organization)
            .bind("id", id)
            .map { row, _ -> attempt(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun open(
        enrollment: UUID,
        lesson: UUID,
    ): Attempt? =
        db
            .sql("SELECT $COLUMNS FROM lms.intento WHERE matricula_id = :enrollment AND leccion_id = :lesson AND estado = 'OPEN' FOR UPDATE")
            .bind("enrollment", enrollment)
            .bind("lesson", lesson)
            .map { row, _ -> attempt(row) }
            .one()
            .awaitSingleOrNull()

    suspend fun count(
        enrollment: UUID,
        lesson: UUID,
    ): Int =
        db
            .sql("SELECT count(*) AS n FROM lms.intento WHERE matricula_id = :enrollment AND leccion_id = :lesson")
            .bind("enrollment", enrollment)
            .bind("lesson", lesson)
            .map { row, _ -> row.get("n", Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()

    suspend fun saveAnswers(
        id: UUID,
        answers: List<AttemptAnswer>,
    ) {
        db
            .sql("UPDATE lms.intento SET respuestas = CAST(:answers AS jsonb) WHERE id = :id")
            .bind("answers", mapper.writeValueAsString(answers))
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // el resultado y su estado; enviado_en solo se fija al cerrar el intento
    suspend fun saveResult(
        id: UUID,
        result: GradeResult,
        submittedAt: Instant,
    ) {
        val spec =
            db
                .sql(
                    """
                    UPDATE lms.intento SET estado = :status, resultado = CAST(:result AS jsonb), enviado_en = COALESCE(enviado_en, :submittedAt), porcentaje = :percent, aprobado = :passed
                    WHERE id = :id
                    """.trimIndent(),
                ).bind("status", if (result.pending) "SUBMITTED" else "GRADED")
                .bind("result", mapper.writeValueAsString(result))
                .bind("submittedAt", submittedAt.atOffset(java.time.ZoneOffset.UTC))
                .bind("id", id)
        val withPercent = result.percent?.let { spec.bind("percent", it) } ?: spec.bindNull("percent", Integer::class.java)
        val withPassed = result.passed?.let { withPercent.bind("passed", it) } ?: withPercent.bindNull("passed", java.lang.Boolean::class.java)
        withPassed.fetch().rowsUpdated().awaitSingle()
    }

    private fun attempt(row: Row): Attempt =
        Attempt(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organizacion_id", UUID::class.java)!!,
            offeringId = row.get("edicion_id", UUID::class.java)!!,
            enrollmentId = row.get("matricula_id", UUID::class.java)!!,
            lessonId = row.get("leccion_id", UUID::class.java)!!,
            studentId = row.get("estudiante_id", UUID::class.java)!!,
            number = row.get("numero", Integer::class.java)!!.toInt(),
            status = row.get("estado", String::class.java)!!,
            snapshot = mapper.readValue(row.get("instantanea_json", String::class.java), AssessmentSnapshot::class.java),
            answers = mapper.readValue(row.get("respuestas_json", String::class.java), object : TypeReference<List<AttemptAnswer>>() {}),
            startedAt = row.get("iniciado_en", OffsetDateTime::class.java)!!.toInstant(),
            deadline = row.get("vence_en", OffsetDateTime::class.java)?.toInstant(),
            submittedAt = row.get("enviado_en", OffsetDateTime::class.java)?.toInstant(),
            result = row.get("resultado_json", String::class.java)?.let { mapper.readValue(it, GradeResult::class.java) },
        )

    companion object {
        const val COLUMNS =
            "id, organizacion_id, edicion_id, matricula_id, leccion_id, estudiante_id, numero, estado, CAST(instantanea AS text) AS instantanea_json, " +
                "CAST(respuestas AS text) AS respuestas_json, iniciado_en, vence_en, enviado_en, CAST(resultado AS text) AS resultado_json"
    }
}
