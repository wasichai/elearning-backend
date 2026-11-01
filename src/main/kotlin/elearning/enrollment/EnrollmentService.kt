package elearning.enrollment

import elearning.common.listAll
import elearning.common.listByIds
import elearning.common.ref
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class Enrolled(
    val enrollment: RecordResponse,
    val created: Boolean,
)

// el comando de matrícula (ADR-0012, ADR-0013). todo como la persona que se matricula: sus permisos y sus alcances
// aplican, así que una edición de una versión no publicada ni siquiera se encuentra. el cupo se cuenta en
// lms.ocupacion_edicion, bloqueada en la misma transacción que crea la matrícula. los prerrequisitos de la versión
// (RF-ACA-08) se comprueban antes que el cupo: el motivo del rechazo es el que la persona puede resolver
@Service
class EnrollmentService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val transactions: TransactionalOperator,
    private val db: DatabaseClient,
    private val clock: Clock,
) {
    suspend fun enroll(offeringCode: String): Enrolled {
        val user = currentUser.require()
        val offering = one(OFFERING, mapOf(CODE to offeringCode)) ?: throw NotFoundException(NOT_FOUND)
        own(user, offering.id)?.let { return Enrolled(it, created = false) }
        return transactions.executeAndAwait {
            val taken = lockOccupancy(user.organizationId, offering.id)
            // otra petición del mismo estudiante pudo crearla mientras esperaba el cerrojo
            own(user, offering.id)?.let { return@executeAndAwait Enrolled(it, created = false) }
            // estado, ventana, versión y cupo se leen ya con el cerrojo: un cierre o una reducción de cupo que llegaron
            // mientras se esperaba cuentan (revisión de la Fase 2, L10)
            val current = records.get(OFFERING, UUID.fromString(offering.id))
            val version = current.attributes.ref(VERSION) ?: throw NotFoundException(NOT_FOUND)
            val published = records.get(VERSION, UUID.fromString(version))
            if (published.attributes[STATE]?.toString() != PUBLISHED) {
                throw ConflictException("La versión de esta edición ya no está publicada")
            }
            open(current)
            prerequisites(user, published)
            val capacity = (current.attributes[CAPACITY] as? Number)?.toInt()
            if (capacity != null && taken >= capacity) throw ConflictException("La edición no tiene cupo")
            val created =
                records.create(
                    ENROLLMENT,
                    RecordRequest(
                        mapOf(
                            OFFERING_FIELD to offering.id,
                            VERSION to version,
                            STUDENT to user.userId.toString(),
                            STUDENT_EMAIL to user.email,
                            STATE to ACTIVE,
                            ENROLLED_AT to Instant.now(clock).toString(),
                        ),
                    ),
                )
            db
                .sql("UPDATE lms.ocupacion_edicion SET matriculados = matriculados + 1 WHERE organizacion_id = :org AND edicion_id = :id")
                .bind("org", user.organizationId)
                .bind("id", UUID.fromString(offering.id))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            Enrolled(created, created = true)
        }
    }

    // abierta: en estado INSCRIPCION_ABIERTA y hoy, en la zona horaria de la edición, dentro de su ventana
    private fun open(offering: RecordResponse) {
        val a = offering.attributes
        if (a[STATE]?.toString() != OPEN) throw ConflictException("La inscripción de esta edición no está abierta")
        val zone = runCatching { ZoneId.of(a[ZONE].toString()) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))
        val today = LocalDate.now(clock.withZone(zone))
        val from = a[FROM]?.toString()?.take(10)?.let(LocalDate::parse)
        val until = a[UNTIL]?.toString()?.take(10)?.let(LocalDate::parse)
        if (from != null && today.isBefore(from)) throw ConflictException("La inscripción de esta edición aún no empieza")
        if (until != null && today.isAfter(until)) throw ConflictException("La inscripción de esta edición ya cerró")
    }

    // RF-ACA-08: los cursos que la versión exige haber aprobado (cursos_previos, un código por línea). cuenta una matrícula
    // propia FINALIZADA y APROBADO en cualquier versión de ese curso. todo se lee como el estudiante: su alcance ya le deja
    // ver solo sus matrículas, así que la de otra persona no cuenta. un código que no se encuentra no se cumple (falla
    // cerrada): el motivo lo nombra para que se corrija la versión
    private suspend fun prerequisites(
        user: AuthenticatedUser,
        version: RecordResponse,
    ) {
        val codes =
            version.attributes[PREREQUISITES]
                ?.toString()
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()
        if (codes.isEmpty()) return
        val passed =
            records
                .listAll(ENROLLMENT, mapOf(STUDENT to user.userId.toString(), STATE to FINISHED, RESULT to PASSED))
                .mapNotNull { it.attributes.ref(VERSION) }
        val passedCourses = records.listByIds(VERSION, passed).mapNotNull { it.attributes.ref(COURSE) }.toSet()
        val missing =
            codes.mapNotNull { code ->
                val course = one(COURSE, mapOf(CODE to code))
                when {
                    course == null -> code
                    course.id in passedCourses -> null
                    else -> course.attributes[TITLE]?.toString()?.let { "$code ($it)" } ?: code
                }
            }
        if (missing.isNotEmpty()) {
            throw ConflictException("Para matricularte en esta edición antes debes aprobar: ${missing.joinToString("; ")}")
        }
    }

    private suspend fun lockOccupancy(
        organization: UUID,
        offering: String,
    ): Int {
        val id = UUID.fromString(offering)
        db
            .sql("INSERT INTO lms.ocupacion_edicion (organizacion_id, edicion_id) VALUES (:org, :id) ON CONFLICT DO NOTHING")
            .bind("org", organization)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return db
            .sql("SELECT matriculados FROM lms.ocupacion_edicion WHERE organizacion_id = :org AND edicion_id = :id FOR UPDATE")
            .bind("org", organization)
            .bind("id", id)
            .map { row, _ -> row.get("matriculados", Number::class.java)!!.toInt() }
            .one()
            .awaitSingle()
    }

    private suspend fun own(
        user: AuthenticatedUser,
        offering: String,
    ): RecordResponse? = one(ENROLLMENT, mapOf(OFFERING_FIELD to offering, STUDENT to user.userId.toString()))

    private suspend fun one(
        objectName: String,
        filters: Map<String, String>,
    ): RecordResponse? = records.list(objectName, RecordQuery(page = PageRequest(0, 2), filters = filters, count = false)).content.firstOrNull()

    companion object {
        const val NOT_FOUND = "Edición no encontrada"
        const val ENROLLMENT = "matricula"
        const val OFFERING = "edicion_curso"
        const val OFFERING_FIELD = "edicion"
        const val VERSION = "version_curso"
        const val STUDENT = "estudiante_id"
        const val STUDENT_EMAIL = "estudiante_correo"
        const val STATE = "estado"
        const val CODE = "codigo"
        const val CAPACITY = "capacidad"
        const val ZONE = "zona_horaria"
        const val FROM = "inscripcion_desde"
        const val UNTIL = "inscripcion_hasta"
        const val ENROLLED_AT = "matriculado_en"
        const val ACTIVE = "ACTIVA"
        const val COURSE = "curso"
        const val TITLE = "titulo"
        const val PREREQUISITES = "cursos_previos"
        const val RESULT = "resultado"
        const val FINISHED = "FINALIZADA"
        const val PASSED = "APROBADO"
        const val OPEN = "INSCRIPCION_ABIERTA"
        const val PUBLISHED = "PUBLICADA"
        const val DEFAULT_ZONE = "America/Lima"
    }
}

// una relación puede llegar como id o expandida
