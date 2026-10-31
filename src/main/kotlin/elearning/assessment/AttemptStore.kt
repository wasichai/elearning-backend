package elearning.assessment

import io.r2dbc.spi.Row
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
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

// un intento para las listas: sin instantánea, respuestas ni resultado, que son lo que pesa. GRADED es el resultado sin
// preguntas pendientes, SUBMITTED con alguna y OPEN sin resultado (saveResult)
data class AttemptLine(
    val id: UUID,
    val enrollmentId: UUID,
    val lessonId: UUID,
    val studentId: UUID,
    val status: String,
    val startedAt: Instant,
    val submittedAt: Instant?,
    val percent: Int?,
    val passed: Boolean?,
    val pendingQuestions: Int,
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
                .bind("startedAt", startedAt.atOffset(ZoneOffset.UTC))
        return (
            if (deadline ==
                null
            ) {
                spec.bindNull("deadline", OffsetDateTime::class.java)
            } else {
                spec.bind("deadline", deadline.atOffset(ZoneOffset.UTC))
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

    suspend fun find(
        organization: UUID,
        id: UUID,
    ): Attempt? =
        db
            .sql("SELECT $COLUMNS FROM lms.intento WHERE organizacion_id = :org AND id = :id")
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
                .bind("submittedAt", submittedAt.atOffset(ZoneOffset.UTC))
                .bind("id", id)
        val withPercent = result.percent?.let { spec.bind("percent", it) } ?: spec.bindNull("percent", Integer::class.java)
        val withPassed = result.passed?.let { withPercent.bind("passed", it) } ?: withPercent.bindNull("passed", java.lang.Boolean::class.java)
        withPassed.fetch().rowsUpdated().awaitSingle()
    }

    // la cola de una edición en un estado, sin instantánea ni respuestas (M4 de #183): la lista no las devuelve
    suspend fun linesOfOffering(
        organization: UUID,
        offering: UUID,
        status: String,
    ): List<AttemptLine> =
        db
            .sql(
                "SELECT $LINE_COLUMNS FROM lms.intento " +
                    "WHERE organizacion_id = :org AND edicion_id = :offering AND estado = :status ORDER BY enviado_en, id",
            ).bind("org", organization)
            .bind("offering", offering)
            .bind("status", status)
            .map { row, _ -> line(row) }
            .all()
            .collectList()
            .awaitSingle()

    // todos los intentos de una edición en una lectura, para el roster: antes se leían por matrícula (M4 de #183)
    suspend fun linesOfOffering(
        organization: UUID,
        offering: UUID,
    ): List<AttemptLine> =
        db
            .sql(
                "SELECT $LINE_COLUMNS FROM lms.intento " +
                    "WHERE organizacion_id = :org AND edicion_id = :offering ORDER BY matricula_id, numero",
            ).bind("org", organization)
            .bind("offering", offering)
            .map { row, _ -> line(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun countPending(
        organization: UUID,
        offering: UUID,
    ): Int =
        db
            .sql("SELECT count(*) AS n FROM lms.intento WHERE organizacion_id = :org AND edicion_id = :offering AND estado = 'SUBMITTED'")
            .bind("org", organization)
            .bind("offering", offering)
            .map { row, _ -> row.get("n", Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()

    suspend fun ofEnrollment(
        organization: UUID,
        enrollment: UUID,
    ): List<Attempt> =
        db
            .sql("SELECT $COLUMNS FROM lms.intento WHERE organizacion_id = :org AND matricula_id = :enrollment ORDER BY numero")
            .bind("org", organization)
            .bind("enrollment", enrollment)
            .map { row, _ -> attempt(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun addGrade(
        organization: UUID,
        attempt: UUID,
        question: UUID,
        points: BigDecimal,
        comment: String?,
        reason: String?,
        author: UUID,
        at: Instant,
    ) {
        val spec =
            db
                .sql(
                    """
                    INSERT INTO lms.calificacion (organizacion_id, intento_id, pregunta_id, puntos, comentario, motivo, autor_id, creado_en)
                    VALUES (:org, :attempt, :question, :points, :comment, :reason, :author, :at)
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("attempt", attempt)
                .bind("question", question)
                .bind("points", points)
                .bind("author", author)
                .bind("at", at.atOffset(ZoneOffset.UTC))
        val withComment = comment?.let { spec.bind("comment", it) } ?: spec.bindNull("comment", String::class.java)
        val withReason = reason?.let { withComment.bind("reason", it) } ?: withComment.bindNull("reason", String::class.java)
        withReason.fetch().rowsUpdated().awaitSingle()
    }

    suspend fun grades(attempt: UUID): List<GradeEntry> =
        db
            .sql("SELECT pregunta_id, puntos, comentario, motivo, autor_id, creado_en FROM lms.calificacion WHERE intento_id = :attempt ORDER BY creado_en, id")
            .bind("attempt", attempt)
            .map { row, _ ->
                GradeEntry(
                    questionId = row.get("pregunta_id", UUID::class.java).toString(),
                    points = row.get("puntos", BigDecimal::class.java)!!,
                    comment = row.get("comentario", String::class.java),
                    reason = row.get("motivo", String::class.java),
                    authorId = row.get("autor_id", UUID::class.java).toString(),
                    at = row.get("creado_en", OffsetDateTime::class.java)!!.toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()

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

    private fun line(row: Row): AttemptLine =
        AttemptLine(
            id = row.get("id", UUID::class.java)!!,
            enrollmentId = row.get("matricula_id", UUID::class.java)!!,
            lessonId = row.get("leccion_id", UUID::class.java)!!,
            studentId = row.get("estudiante_id", UUID::class.java)!!,
            status = row.get("estado", String::class.java)!!,
            startedAt = row.get("iniciado_en", OffsetDateTime::class.java)!!.toInstant(),
            submittedAt = row.get("enviado_en", OffsetDateTime::class.java)?.toInstant(),
            percent = row.get("porcentaje", Int::class.javaObjectType),
            passed = row.get("aprobado", Boolean::class.javaObjectType),
            pendingQuestions = row.get("pendientes", Number::class.java)?.toInt() ?: 0,
        )

    companion object {
        // porcentaje y aprobado son los del resultado (saveResult); las preguntas sin nota se cuentan en la base
        const val LINE_COLUMNS =
            "id, matricula_id, leccion_id, estudiante_id, estado, iniciado_en, enviado_en, porcentaje, aprobado, " +
                "(SELECT count(*) FROM jsonb_array_elements(COALESCE(resultado -> 'questions', CAST('[]' AS jsonb))) q " +
                "WHERE q ->> 'points' IS NULL) AS pendientes"
        const val COLUMNS =
            "id, organizacion_id, edicion_id, matricula_id, leccion_id, estudiante_id, numero, estado, CAST(instantanea AS text) AS instantanea_json, " +
                "CAST(respuestas AS text) AS respuestas_json, iniciado_en, vence_en, enviado_en, CAST(resultado AS text) AS resultado_json"
    }
}
