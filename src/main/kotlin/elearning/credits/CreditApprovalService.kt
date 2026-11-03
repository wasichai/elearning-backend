package elearning.credits

import elearning.authoring.DecisionStore
import elearning.common.instantOf
import elearning.common.listAll
import elearning.common.ref
import elearning.course.CreditApprovalWrite
import elearning.course.CreditPolicyRecords
import elearning.course.VersionWorkload
import elearning.workload.CreditPolicy
import elearning.workload.CreditProposal
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// aprobación humana y auditada de los créditos de una versión de curso (RF-CRE-08, ADR-0035). la plataforma propone y
// registra; el reconocimiento es un acto de la institución, que hace una persona con APROBAR_CREDITOS en nombre de un
// órgano y con un documento. quien aprueba no es nadie que haya hecho o decidido el contenido: ni su autor, ni su revisor
// asignado, ni quien lo aprobó en la bandeja, ni un docente de una edición del curso; tampoco siendo ADMIN. todo se
// escribe como la persona: la fila queda en el audit_log de Wasichai con quién y cuándo
@Service
class CreditApprovalService(
    private val currentUser: CurrentUser,
    private val records: RecordService,
    private val metadata: MetadataService,
    private val decisions: DecisionStore,
    private val duties: CreditDuties,
    private val transactions: TransactionalOperator,
    private val clock: Clock,
) {
    // lo que se propone hoy y lo vigente. la lee quien lee la versión y las aprobaciones (nunca el estudiante: sin READ)
    suspend fun credits(versionId: UUID): VersionCredits {
        val version = visible(versionId)
        val current = current(history(versionId))
        val rule = ruleOf(version)
        return VersionCredits(
            versionId = version.id,
            versionStatus = version.attributes[STATE]?.toString() ?: DRAFT,
            system = CreditPolicyRecords.system(version.attributes[SYSTEM]?.toString())?.name,
            policyId = rule.bound?.id,
            proposal = rule.policy?.propose(VersionWorkload.of(version.attributes))?.let(::proposalView),
            current = current?.let(::approvalView),
        )
    }

    // el historial de la versión, lo más antiguo primero: aprobaciones y revocaciones, ninguna borrada ni cambiada
    suspend fun approvals(versionId: UUID): List<CreditApprovalView> {
        visible(versionId)
        return history(versionId).map(::approvalView)
    }

    // la aprobación y su copia en la versión van en una transacción, bajo un candado por versión: o las dos o ninguna
    suspend fun approve(
        versionId: UUID,
        request: CreditApprovalRequest,
    ): CreditApprovalView {
        val user = approver()
        val evidence = evidence(request.approvingBody, request.documentReference)
        val reason = trimmed(request.reason, "reason", MAX_REASON)
        request.approvedCredits?.let {
            if (it.signum() <= 0 ||
                it.stripTrailingZeros().scale() > MAX_SCALE
            ) {
                invalid("approvedCredits", "a positive number with at most $MAX_SCALE decimals")
            }
        }
        val version = visible(versionId)
        separate(version, user)
        val written =
            transactions.executeAndAwait {
                duties.lock(versionId)
                val fresh = records.get(VERSION, versionId)
                val state = fresh.attributes[STATE]?.toString() ?: DRAFT
                if (state !in APPROVABLE) throw ConflictException("Only an approved or published version gets its credits approved, not a $state one")
                if (CreditPolicyRecords.system(fresh.attributes[SYSTEM]?.toString()) == null) {
                    throw ConflictException("A version without a credit system has no credits to approve")
                }
                // la versión de política ligada, publicada y del sistema de la versión; sin ella, la de referencia (ADR-0036)
                val rule = ruleOf(fresh)
                if (rule.bound != null && rule.bound.attributes[CreditPolicyRecords.STATE]?.toString() != CreditPolicyRecords.PUBLISHED) {
                    throw ConflictException("The credit policy of this version is not published")
                }
                val policy = rule.policy ?: throw ConflictException("The credit policy of this version is of another credit system")
                if (current(history(versionId)) != null) throw ConflictException("This version already has approved credits: revoke them first")
                val proposal = policy.propose(VersionWorkload.of(fresh.attributes))
                val approved = request.approvedCredits ?: proposal.proposed
                if (approved.signum() <= 0) invalid("approvedCredits", "the proposal is 0: send the approved credits")
                if (approved.compareTo(proposal.proposed) != 0 &&
                    reason == null
                ) {
                    invalid("reason", "required when the approved credits differ from the proposed ones")
                }
                val row =
                    records.create(
                        APPROVAL,
                        RecordRequest(
                            mapOf(
                                VERSION to versionId.toString(),
                                TYPE to APPROVAL_TYPE,
                                CreditPolicyRecords.POLICY to rule.bound?.id,
                                "sistema" to proposal.system.name,
                                "politica_id" to proposal.policyId,
                                "politica_version" to proposal.policyVersion,
                                "metodo" to proposal.method,
                                "parametros" to JSON.writeValueAsString(proposal.parameters),
                                "fuente" to proposal.source,
                                "horas_totales" to proposal.totalHours.toPlainString(),
                                "creditos_calculados" to proposal.calculated.toPlainString(),
                                "creditos_propuestos" to proposal.proposed.toPlainString(),
                                APPROVED to approved.toPlainString(),
                                "motivo" to reason,
                                "organo" to evidence.first,
                                "documento_referencia" to evidence.second,
                                "documento_fecha" to request.documentDate?.toString(),
                                "aprobada_por" to user.userId.toString(),
                                AT to Instant.now(clock).toString(),
                            ),
                        ),
                        summary(
                            APPROVAL_TYPE,
                            evidence,
                            request.documentDate,
                            "${proposal.policyId}@${proposal.policyVersion} ${proposal.parameters}",
                            "${proposal.totalHours.plain().toPlainString()} h → ${approved.plain().toPlainString()} créditos " +
                                "(propuestos ${proposal.proposed.plain().toPlainString()})",
                            reason,
                        ),
                    )
                copy(versionId, approved, "${proposal.policyId}@${proposal.policyVersion}")
                row
            }
        return approvalView(written)
    }

    // revocar inserta otra fila que nombra la aprobación revocada y borra la copia de la versión: el catálogo deja de
    // decir «aprobados». solo se revoca la aprobación vigente
    suspend fun revoke(
        approvalId: UUID,
        request: CreditRevocationRequest,
    ): CreditApprovalView {
        val user = approver()
        val evidence = evidence(request.approvingBody, request.documentReference)
        val reason = trimmed(request.reason, "reason", MAX_REASON) ?: invalid("reason", "required")
        val approval = approval(approvalId)
        val versionId = approval.attributes.ref(VERSION)?.let(UUID::fromString) ?: throw NotFoundException(APPROVAL_NOT_FOUND)
        separate(visible(versionId), user)
        val written =
            transactions.executeAndAwait {
                duties.lock(versionId)
                if (current(history(versionId))?.id != approval.id) throw ConflictException("Only the current approval of a version is revoked")
                val row =
                    records.create(
                        APPROVAL,
                        RecordRequest(
                            mapOf(
                                VERSION to versionId.toString(),
                                TYPE to REVOCATION_TYPE,
                                "sustituye" to approval.id,
                                "motivo" to reason,
                                "organo" to evidence.first,
                                "documento_referencia" to evidence.second,
                                "documento_fecha" to request.documentDate?.toString(),
                                "aprobada_por" to user.userId.toString(),
                                AT to Instant.now(clock).toString(),
                            ),
                        ),
                        summary(REVOCATION_TYPE, evidence, request.documentDate, "revoca ${approval.id}", null, reason),
                    )
                copy(versionId, null, null)
                row
            }
        return approvalView(written)
    }

    // el motivo de la fila en el audit_log de Wasichai (ADR-041): /api/audit muestra de un alta quién y cuándo, no sus
    // valores, así que el acto se resume aquí, dentro del tope de 500 caracteres de Wasichai
    private fun summary(
        type: String,
        evidence: Pair<String, String>,
        date: LocalDate?,
        policy: String,
        result: String?,
        reason: String?,
    ): String =
        listOfNotNull(type, evidence.first, evidence.second + (date?.let { " ($it)" } ?: ""), policy, result, reason?.let { "motivo: $it" })
            .joinToString(" · ")
            .take(MAX_AUDIT_REASON)

    private suspend fun approver(): AuthenticatedUser {
        val user = currentUser.require()
        currentUser.requirePermission(user, APPROVE_CREDITS, metadata.definitionOf(VERSION).obj.id)
        return user
    }

    // separación de funciones por datos, en el servidor y para todos (ADMIN incluido): nunca por nombre de rol
    private suspend fun separate(
        version: RecordResponse,
        user: AuthenticatedUser,
    ) {
        val me = user.userId.toString()
        val versionId = UUID.fromString(version.id)
        if (version.attributes[AUTHOR]?.toString() == me) throw ForbiddenException("The author of a version does not approve its credits")
        if (version.attributes[REVIEWER]?.toString() == me) throw ForbiddenException("The assigned reviewer of a version does not approve its credits")
        if (decisions.ofVersion(user.organizationId, versionId).any { it.decision == APPROVE && it.authorId == me }) {
            throw ForbiddenException("Who approved a version in the review inbox does not approve its credits")
        }
        val course = version.attributes.ref(COURSE)?.let(UUID::fromString)
        if (course == null || duties.teachesCourse(user.organizationId, user.userId, course)) {
            throw ForbiddenException("A teacher of an offering of the course does not approve its credits")
        }
    }

    // la versión con lo aprobado, o sin nada al revocar. solo con la marca la guarda editorial lo deja pasar
    private suspend fun copy(
        versionId: UUID,
        approved: BigDecimal?,
        policy: String?,
    ) {
        withContext(CreditApprovalWrite()) {
            records.patch(VERSION, versionId, RecordRequest(mapOf(APPROVED to approved?.toPlainString(), POLICY to policy)), null, null)
        }
    }

    // la regla con la que se propone y se aprueba: la versión de política ligada a la versión del curso (bound) o, sin ella,
    // la de referencia de su sistema; ligada a otro sistema, ninguna (ADR-0036). publicar otra versión de la política no la
    // cambia: la relación nombra una versión concreta
    private class Rule(
        val policy: CreditPolicy?,
        val bound: RecordResponse?,
    )

    private suspend fun ruleOf(version: RecordResponse): Rule {
        val bound = version.attributes.ref(CreditPolicyRecords.POLICY)?.let { records.get(CreditPolicyRecords.POLICY, UUID.fromString(it)) }
        return Rule(CreditPolicyRecords.forVersion(version.attributes, bound?.attributes), bound)
    }

    private suspend fun history(versionId: UUID): List<RecordResponse> =
        records
            .listAll(APPROVAL, mapOf(VERSION to versionId.toString()))
            .sortedWith(compareBy({ instantOf(it.attributes[AT]) }, { it.createdAt }))

    // la vigente es la última fila si es una aprobación; tras una revocación, ninguna
    private fun current(history: List<RecordResponse>): RecordResponse? = history.lastOrNull()?.takeIf { it.attributes[TYPE]?.toString() == APPROVAL_TYPE }

    private suspend fun visible(id: UUID): RecordResponse =
        try {
            records.get(VERSION, id)
        } catch (_: ForbiddenException) {
            throw NotFoundException(VERSION_NOT_FOUND)
        }

    private suspend fun approval(id: UUID): RecordResponse =
        try {
            records.get(APPROVAL, id)
        } catch (_: ForbiddenException) {
            throw NotFoundException(APPROVAL_NOT_FOUND)
        }

    private fun evidence(
        body: String?,
        document: String?,
    ): Pair<String, String> =
        (trimmed(body, "approvingBody", MAX_TEXT) ?: invalid("approvingBody", "required")) to
            (trimmed(document, "documentReference", MAX_TEXT) ?: invalid("documentReference", "required"))

    private fun trimmed(
        value: String?,
        field: String,
        max: Int,
    ): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > max) invalid(field, "at most $max characters")
        return trimmed
    }

    private fun invalid(
        field: String,
        message: String,
    ): Nothing = throw ValidationException("Invalid $field", field, message)

    private fun proposalView(proposal: CreditProposal) =
        ProposalView(
            system = proposal.system.name,
            policyId = proposal.policyId,
            policyVersion = proposal.policyVersion,
            method = proposal.method,
            parameters = proposal.parameters,
            source = proposal.source,
            totalHours = proposal.totalHours.plain(),
            calculated = proposal.calculated.plain(),
            proposed = proposal.proposed.plain(),
            warnings = proposal.warnings,
            status = proposal.status.name,
        )

    private fun approvalView(row: RecordResponse): CreditApprovalView {
        val a = row.attributes
        return CreditApprovalView(
            id = row.id,
            versionId = a.ref(VERSION) ?: "",
            type = a[TYPE]?.toString() ?: APPROVAL_TYPE,
            revokes = a.text("sustituye"),
            system = a.text("sistema"),
            policyId = a.text("politica_id"),
            policyVersion = a.text("politica_version"),
            method = a.text("metodo"),
            parameters = a.text("parametros")?.let { text -> JSON.readTree(text).properties().associate { it.key to it.value.asString() } }.orEmpty(),
            source = a.text("fuente"),
            totalHours = a.decimal("horas_totales"),
            calculated = a.decimal("creditos_calculados"),
            proposed = a.decimal("creditos_propuestos"),
            approvedCredits = a.decimal(APPROVED),
            reason = a.text("motivo"),
            approvingBody = a.text("organo") ?: "",
            documentReference = a.text("documento_referencia") ?: "",
            documentDate = a.text("documento_fecha")?.let { LocalDate.parse(it.take(10)) },
            approvedBy = a.text("aprobada_por") ?: "",
            approvedAt = instantOf(a[AT]) ?: Instant.EPOCH,
        )
    }

    companion object {
        const val VERSION = "version_curso"
        const val APPROVAL = "aprobacion_creditos"
        const val COURSE = "curso"
        const val STATE = "estado"
        const val SYSTEM = "sistema_credito"
        const val AUTHOR = "autor_id"
        const val REVIEWER = "revisor_id"
        const val APPROVED = "creditos_aprobados"
        const val POLICY = "politica_creditos"
        const val TYPE = "tipo"
        const val AT = "aprobada_en"
        const val DRAFT = "BORRADOR"
        const val APPROVAL_TYPE = "APROBACION"
        const val REVOCATION_TYPE = "REVOCACION"
        const val APPROVE = "APROBAR"
        const val APPROVE_CREDITS = "APROBAR_CREDITOS"
        const val MAX_TEXT = 200
        const val MAX_REASON = 4000
        const val MAX_SCALE = 2
        const val MAX_AUDIT_REASON = 500
        const val VERSION_NOT_FOUND = "Versión no encontrada"
        const val APPROVAL_NOT_FOUND = "Aprobación no encontrada"

        // se aprueban los créditos de lo que ya pasó la revisión: lo aprobado o lo publicado
        val APPROVABLE = setOf("APROBADA", "PUBLICADA")

        private val JSON: JsonMapper = JsonMapper.builder().build()
    }
}

private fun Map<String, Any?>.text(name: String): String? = this[name]?.toString()?.takeIf { it.isNotBlank() }

private fun Map<String, Any?>.decimal(name: String): BigDecimal? =
    when (val v = this[name]) {
        is BigDecimal -> v.plain()
        is Number -> BigDecimal(v.toString()).plain()
        is String -> v.toBigDecimalOrNull()?.plain()
        else -> null
    }

// 8.00 se responde como 8, nunca como 8E+0
private fun BigDecimal.plain(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
