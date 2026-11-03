package elearning.credits

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// lo que pide la autoridad académica al aprobar: el número (por omisión, lo propuesto), el motivo si difiere, y el acto
// institucional que lo respalda (órgano y documento)
data class CreditApprovalRequest(
    val approvedCredits: BigDecimal? = null,
    val reason: String? = null,
    val approvingBody: String? = null,
    val documentReference: String? = null,
    val documentDate: LocalDate? = null,
)

// revocar también es un acto institucional: con motivo, órgano y documento
data class CreditRevocationRequest(
    val reason: String? = null,
    val approvingBody: String? = null,
    val documentReference: String? = null,
    val documentDate: LocalDate? = null,
)

// una fila de aprobacion_creditos tal como se registró: la instantánea de la política y del cálculo viaja con ella
data class CreditApprovalView(
    val id: String,
    val versionId: String,
    // APROBACION | REVOCACION
    val type: String,
    // en una revocación, la aprobación que revoca
    val revokes: String?,
    val system: String?,
    val policyId: String?,
    val policyVersion: String?,
    val method: String?,
    val parameters: Map<String, String>,
    val source: String?,
    val totalHours: BigDecimal?,
    val calculated: BigDecimal?,
    val proposed: BigDecimal?,
    val approvedCredits: BigDecimal?,
    val reason: String?,
    val approvingBody: String,
    val documentReference: String,
    val documentDate: LocalDate?,
    val approvedBy: String,
    val approvedAt: Instant,
)

data class ProposalView(
    val system: String,
    val policyId: String,
    val policyVersion: String,
    val method: String,
    val parameters: Map<String, String>,
    val source: String,
    val totalHours: BigDecimal,
    val calculated: BigDecimal,
    val proposed: BigDecimal,
    val warnings: List<String>,
    val status: String,
)

// lo que se propondría hoy para una versión y la aprobación vigente, si la hay. sin sistema de crédito, sin propuesta
data class VersionCredits(
    val versionId: String,
    val versionStatus: String,
    val system: String?,
    // la versión de política ligada (politica_credito); null: propone la de referencia del sistema
    val policyId: String?,
    val proposal: ProposalView?,
    val current: CreditApprovalView?,
)
