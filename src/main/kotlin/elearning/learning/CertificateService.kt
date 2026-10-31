package elearning.learning

import elearning.common.ref
import elearning.workload.WeeklyHours
import elearning.workload.Workload
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class CertificateCourse(
    val code: String,
    val title: String,
)

data class CertificateOffering(
    val code: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val timeZone: String,
)

data class Certificate(
    val code: String,
    val issuedAt: Instant,
    val holderName: String,
    val course: CertificateCourse,
    val version: Int,
    val offering: CertificateOffering,
    val totalHours: BigDecimal,
    val result: String,
    val rules: List<RuleResult>,
)

data class CertificateRequest(
    val holderName: String? = null,
)

// la emisión y la verificación (ADR-0016 §7). lo pide el estudiante sobre su matrícula; se emite una vez, bajo un cerrojo por
// matrícula, con una instantánea que no se recalcula aunque cambien después el curso o la matrícula
@Service
class CertificateService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val completion: CompletionService,
    private val store: CertificateStore,
    private val transactions: TransactionalOperator,
    private val db: DatabaseClient,
    private val clock: Clock,
) {
    data class Issued(
        val certificate: Certificate,
        val created: Boolean,
    )

    suspend fun issue(
        enrollmentId: UUID,
        request: CertificateRequest,
    ): Issued {
        val user = currentUser.require()
        val enrollment = records.get(ENROLLMENT, enrollmentId)
        if (enrollment.attributes[STUDENT]?.toString() != user.userId.toString()) throw NotFoundException(NOT_FOUND)
        val holder =
            request.holderName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
                ?: throw ValidationException("Invalid certificate", "holderName", "must have between 1 and $MAX_NAME characters")
        return transactions.executeAndAwait {
            db
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .bind("key", "lms.certificado:$enrollmentId")
                .fetch()
                .first()
                .awaitSingleOrNull()
            store.ofEnrollment(enrollmentId)?.let { return@executeAndAwait Issued(it, created = false) }
            if (enrollment.attributes[STATE]?.toString() != ACTIVE) throw ConflictException("Esta matrícula no está activa")
            val result = completion.of(enrollmentId)
            if (!result.met) throw ConflictException("Aún no cumples: ${result.rules.filter { !it.met }.joinToString("; ") { it.detail }}")
            val now = Instant.now(clock)
            val certificate = snapshot(enrollment, holder, result, now)
            store.insert(user.organizationId, enrollmentId, user.userId, certificate)
            records.patch(
                ENROLLMENT,
                enrollmentId,
                RecordRequest(mapOf(STATE to "FINALIZADA", "resultado" to "APROBADO", "finalizado_en" to now.toString())),
                null,
                null,
            )
            Issued(certificate, created = true)
        }
    }

    suspend fun verify(code: String): Certificate {
        if (!CODE_FORMAT.matches(code)) throw NotFoundException(NOT_FOUND)
        return store.ofCode(code) ?: throw NotFoundException(NOT_FOUND)
    }

    private suspend fun snapshot(
        enrollment: RecordResponse,
        holder: String,
        result: Completion,
        now: Instant,
    ): Certificate {
        val version = records.get(VERSION, UUID.fromString(enrollment.attributes.ref(VERSION)!!))
        val course = records.get(COURSE, UUID.fromString(version.attributes.ref(COURSE)!!))
        val offering = records.get(OFFERING, UUID.fromString(enrollment.attributes.ref(OFFERING_FIELD)!!))

        fun hours(name: String) = version.attributes[name]?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val workload =
            Workload(
                (version.attributes["semanas"] as? Number)?.toInt() ?: 1,
                WeeklyHours(hours("horas_teoria_semana"), hours("horas_practica_semana"), hours("horas_supervisadas_semana"), hours("horas_autonomas_semana")),
            )
        return Certificate(
            code = newCode(),
            issuedAt = now,
            holderName = holder,
            course = CertificateCourse(course.attributes["codigo"].toString(), course.attributes["titulo"].toString()),
            version = (version.attributes["numero"] as? Number)?.toInt() ?: 1,
            offering =
                CertificateOffering(
                    offering.attributes["codigo"].toString(),
                    LocalDate.parse(offering.attributes["fecha_inicio"].toString().take(10)),
                    LocalDate.parse(offering.attributes["fecha_fin"].toString().take(10)),
                    offering.attributes["zona_horaria"].toString(),
                ),
            totalHours = workload.totalHours,
            result = "APROBADO",
            rules = result.rules,
        )
    }

    // 12 símbolos de un alfabeto sin ambiguos: 60 bits, no se adivina
    private fun newCode(): String = (1..3).joinToString("-") { (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("") }

    private val random = SecureRandom()

    companion object {
        const val ENROLLMENT = "matricula"
        const val VERSION = "version_curso"
        const val COURSE = "curso"
        const val OFFERING = "edicion_curso"
        const val OFFERING_FIELD = "edicion"
        const val STUDENT = "estudiante_id"
        const val STATE = "estado"
        const val ACTIVE = "ACTIVA"
        const val NOT_FOUND = "Certificado no encontrado"
        const val MAX_NAME = 120
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val CODE_FORMAT = Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$")
    }
}

// lms.certificado: solo inserción
@org.springframework.stereotype.Repository
class CertificateStore(
    private val db: DatabaseClient,
) {
    suspend fun insert(
        organization: UUID,
        enrollment: UUID,
        student: UUID,
        certificate: Certificate,
    ) {
        db
            .sql(
                "INSERT INTO lms.certificado (codigo, organizacion_id, matricula_id, estudiante_id, emitido_en, datos) VALUES (:code, :org, :enrollment, :student, :at, CAST(:data AS jsonb))",
            ).bind("code", certificate.code)
            .bind("org", organization)
            .bind("enrollment", enrollment)
            .bind("student", student)
            .bind("at", certificate.issuedAt.atOffset(ZoneOffset.UTC))
            .bind("data", MAPPER.writeValueAsString(certificate))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun ofEnrollment(enrollment: UUID): Certificate? = one("matricula_id = :value", enrollment)

    suspend fun ofCode(code: String): Certificate? = one("codigo = :value", code)

    suspend fun codeOf(enrollment: UUID): String? = ofEnrollment(enrollment)?.code

    private suspend fun one(
        condition: String,
        value: Any,
    ): Certificate? =
        db
            .sql("SELECT CAST(datos AS text) AS datos FROM lms.certificado WHERE $condition")
            .bind("value", value)
            .map { row, _ -> MAPPER.readValue(row.get("datos", String::class.java), Certificate::class.java) }
            .one()
            .awaitSingleOrNull()

    companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }
}
