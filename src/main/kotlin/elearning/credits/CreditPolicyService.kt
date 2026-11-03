package elearning.credits

import elearning.common.instantOf
import elearning.common.listAll
import elearning.course.CreditPolicyRecords
import elearning.course.CreditPolicyRecords.DRAFT
import elearning.course.CreditPolicyRecords.JURISDICTION
import elearning.course.CreditPolicyRecords.KEY
import elearning.course.CreditPolicyRecords.NUMBER
import elearning.course.CreditPolicyRecords.POLICY
import elearning.course.CreditPolicyRecords.PROGRAM
import elearning.course.CreditPolicyRecords.PUBLISHED
import elearning.course.CreditPolicyRecords.PUBLISHED_AT
import elearning.course.CreditPolicyRecords.PUBLISHED_BY
import elearning.course.CreditPolicyRecords.RETIRED
import elearning.course.CreditPolicyRecords.STATE
import elearning.course.CreditPolicyRecords.SYSTEM
import elearning.course.CreditPolicyWrite
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// una versión de política de crédito tal como se guarda (RF-CRE-07, ADR-0036). parameters: los del método, como texto
data class CreditPolicyView(
    val id: String,
    val key: String,
    val number: Int,
    val name: String,
    // BORRADOR | PUBLICADA | RETIRADA
    val status: String,
    val system: String,
    val method: String,
    val parameters: Map<String, String>,
    val jurisdiction: String,
    // null: toda la institución (la organización)
    val programCode: String?,
    val source: String,
    val sourceUrl: String?,
    val validFrom: LocalDate?,
    val publishedBy: String?,
    val publishedAt: Instant?,
)

// las políticas de crédito en datos (RF-CRE-07, ADR-0036). el borrador se escribe por la API de registros y lo vigila
// CreditPolicyGuard; aquí, lo que no es editar: listar por ámbito, publicar, retirar y abrir la versión siguiente. todo
// como la persona: los permisos de Wasichai, su organización y la guarda aplican. publicar exige la acción PUBLICAR
@Service
class CreditPolicyService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val clock: Clock,
) {
    // por jurisdicción, sistema, estado y programa; sin filtros, todas las que la persona lee, por clave y versión
    suspend fun list(
        jurisdiction: String?,
        system: String?,
        status: String?,
        program: String?,
    ): List<CreditPolicyView> {
        val filters =
            listOfNotNull(
                jurisdiction?.let { JURISDICTION to it },
                system?.let { SYSTEM to it },
                status?.let { STATE to it },
                program?.let { PROGRAM to it },
            ).toMap()
        return records
            .listAll(POLICY, filters)
            .map(::view)
            .sortedWith(compareBy({ it.key }, { it.number }))
    }

    suspend fun get(id: UUID): CreditPolicyView = view(visible(id))

    // de borrador a publicada: desde aquí no cambia. quién y cuándo los pone el servidor
    suspend fun publish(id: UUID): CreditPolicyView {
        val user = currentUser.require()
        currentUser.requirePermission(user, PUBLISH, policyObject())
        val policy = visible(id)
        if (state(policy) != DRAFT) throw ConflictException("Only a draft credit policy is published")
        return view(
            write(id, mapOf(STATE to PUBLISHED, PUBLISHED_BY to user.userId.toString(), PUBLISHED_AT to Instant.now(clock).toString())),
        )
    }

    // retirada: no se liga a versiones nuevas de curso; lo ya ligado y lo aprobado con ella siguen valiendo lo que valían
    suspend fun retire(id: UUID): CreditPolicyView {
        val user = currentUser.require()
        currentUser.requirePermission(user, PUBLISH, policyObject())
        val policy = visible(id)
        if (state(policy) != PUBLISHED) throw ConflictException("Only a published credit policy is retired")
        return view(write(id, mapOf(STATE to RETIRED)))
    }

    // la versión siguiente de la misma clave, en borrador y con lo mismo que la de origen; la de origen no se toca. de un
    // borrador no: se edita ese borrador
    suspend fun newVersion(id: UUID): CreditPolicyView {
        val user = currentUser.require()
        currentUser.requirePermission(user, Actions.CREATE, policyObject())
        val source = visible(id)
        if (state(source) == DRAFT) throw ConflictException("A draft credit policy is edited, not versioned")
        val key = source.attributes[KEY]?.toString() ?: throw ConflictException("A credit policy without key")
        val next = records.listAll(POLICY, mapOf(KEY to key)).maxOf { number(it) } + 1
        val copied = source.attributes.filter { (k, v) -> k !in NOT_COPIED && v != null && v !is Map<*, *> } + mapOf(NUMBER to next, STATE to DRAFT)
        return view(records.create(POLICY, RecordRequest(copied)))
    }

    private suspend fun write(
        id: UUID,
        attributes: Map<String, Any?>,
    ): RecordResponse = withContext(CreditPolicyWrite()) { records.patch(POLICY, id, RecordRequest(attributes), null, null) }

    private suspend fun visible(id: UUID): RecordResponse =
        try {
            records.get(POLICY, id)
        } catch (_: ForbiddenException) {
            throw NotFoundException(NOT_FOUND)
        }

    private suspend fun policyObject(): UUID = metadata.definitionOf(POLICY).obj.id

    private fun state(policy: RecordResponse) = policy.attributes[STATE]?.toString() ?: DRAFT

    private fun number(policy: RecordResponse): Int =
        when (val n = policy.attributes[NUMBER]) {
            is Number -> n.toInt()
            else -> n?.toString()?.toIntOrNull() ?: 0
        }

    private fun view(policy: RecordResponse): CreditPolicyView {
        val a = policy.attributes
        return CreditPolicyView(
            id = policy.id,
            key = a[KEY]?.toString() ?: "",
            number = number(policy),
            name = a["nombre"]?.toString() ?: "",
            status = state(policy),
            system = a[SYSTEM]?.toString() ?: "",
            method = a[CreditPolicyRecords.METHOD]?.toString() ?: "",
            parameters = runCatching { CreditPolicyRecords.parameters(a[CreditPolicyRecords.PARAMETERS]?.toString()) }.getOrDefault(emptyMap()),
            jurisdiction = a[JURISDICTION]?.toString() ?: "",
            programCode = a[PROGRAM]?.toString()?.takeIf { it.isNotBlank() },
            source = a[CreditPolicyRecords.SOURCE]?.toString() ?: "",
            sourceUrl = a["fuente_url"]?.toString()?.takeIf { it.isNotBlank() },
            validFrom = a["vigente_desde"]?.toString()?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)) },
            publishedBy = a[PUBLISHED_BY]?.toString()?.takeIf { it.isNotBlank() },
            publishedAt = instantOf(a[PUBLISHED_AT]),
        )
    }

    companion object {
        const val PUBLISH = "PUBLICAR"
        const val NOT_FOUND = "Política de crédito no encontrada"

        // lo que no pasa a la versión siguiente: su número, su estado y su publicación
        val NOT_COPIED = setOf(NUMBER, STATE, PUBLISHED_BY, PUBLISHED_AT)
    }
}
