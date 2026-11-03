package elearning.ai

import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// el registro AgentRun (RF-IA-03, ADR-0033 §7, docs/ai §4). En Kotlin no se llama AgentRun: ese nombre ya es de
// wasichai-agent. Una ejecución es una llamada de un paso, con lo que se pidió, con qué modelo, lo que salió y lo que costó

// el paso de la canalización (docs/ai §3); uno nuevo llega aquí y a docs/ai
enum class AgentStep { SOURCE_ANALYSIS, COURSE_DESIGN, LESSON_DRAFT, SCRIPT, CONSISTENCY_REVIEW, ASSISTANT }

// MODEL: un proveedor; DETERMINISTIC: el doble sin red de docs/ai §7
enum class Generator { MODEL, DETERMINISTIC }

enum class AgentExecutionStatus { PENDING, RUNNING, SUCCEEDED, INVALID_OUTPUT, FAILED, CANCELLED }

enum class InterventionKind { ACCEPTED, EDITED, REJECTED }

// una fuente leída por la ejecución: su referencia exacta y el hash de su contenido
data class SourceDigest(
    val ref: String,
    val sha256: String,
)

// los parámetros efectivos enviados al proveedor. Tipados a propósito: un mapa libre podría llevar una clave o una
// cabecera (RNF-SEG-09); un parámetro nuevo entra aquí
data class ModelSettings(
    val maxTokens: Int,
    val temperature: Double? = null,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
    }
}

data class ModelChoice(
    val provider: String,
    val model: String,
    val settings: ModelSettings,
) {
    init {
        require(provider in PROVIDERS) { "provider must be one of $PROVIDERS" }
        require(model.isNotBlank()) { "model must not be blank" }
    }

    companion object {
        val PROVIDERS = setOf("anthropic", "openai")
    }
}

// lo que se pide al empezar: no cambia después. inputs es el JSON exacto que recibió el paso
data class NewAgentExecution(
    val organizationId: UUID,
    val jobId: UUID,
    val step: AgentStep,
    val requestedBy: UUID,
    val courseId: UUID,
    val unitId: UUID? = null,
    val versionId: UUID? = null,
    val objective: String,
    val inputs: Any,
    val sources: List<SourceDigest> = emptyList(),
    val generator: Generator,
    // null solo con DETERMINISTIC
    val model: ModelChoice?,
    val promptVersion: String,
    val templateVersion: String,
    val idempotencyKey: String,
    val attempt: Int,
) {
    init {
        require(objective.isNotBlank()) { "objective must not be blank" }
        require((generator == Generator.MODEL) == (model != null)) { "a MODEL execution has a model and a DETERMINISTIC one has none" }
        require(attempt > 0) { "attempt must be positive" }
    }
}

// los tokens que informó el proveedor
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0) { "tokens must not be negative" }
    }
}

// el cierre: un estado terminal, la salida y el informe de validación (JSON), el error legible y el consumo
data class AgentExecutionOutcome(
    val status: AgentExecutionStatus,
    val output: Any? = null,
    val validations: Any? = null,
    val error: String? = null,
    val usage: TokenUsage? = null,
) {
    init {
        require(status !in OPEN) { "an execution closes with a final status, not $status" }
    }

    companion object {
        val OPEN = setOf(AgentExecutionStatus.PENDING, AgentExecutionStatus.RUNNING)
    }
}

// una aceptación, edición o rechazo posterior de lo generado, por una persona
data class Intervention(
    val userId: UUID,
    val kind: InterventionKind,
    val at: Instant,
    val note: String? = null,
)

// el coste y la tarifa con que se calculó (por millón de tokens), con la fecha desde la que rige
data class AgentExecutionCost(
    val amount: BigDecimal,
    val currency: String,
    val inputPerMillion: BigDecimal,
    val outputPerMillion: BigDecimal,
    val rateSince: LocalDate,
)

// una ejecución para las listas: sin entradas, salida ni validaciones, que son lo que pesa
data class AgentExecutionLine(
    val id: UUID,
    val jobId: UUID,
    val step: AgentStep,
    val status: AgentExecutionStatus,
    val generator: Generator,
    val provider: String?,
    val model: String?,
    val requestedBy: UUID,
    val courseId: UUID,
    val unitId: UUID?,
    val versionId: UUID?,
    val attempt: Int,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val cost: AgentExecutionCost?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

data class AgentExecution(
    val id: UUID,
    val organizationId: UUID,
    val jobId: UUID,
    val step: AgentStep,
    val requestedBy: UUID,
    val courseId: UUID,
    val unitId: UUID?,
    val versionId: UUID?,
    val objective: String,
    val inputs: JsonNode,
    val sources: List<SourceDigest>,
    val generator: Generator,
    val provider: String?,
    val model: String?,
    val modelSettings: ModelSettings?,
    val promptVersion: String,
    val templateVersion: String,
    val idempotencyKey: String,
    val attempt: Int,
    val status: AgentExecutionStatus,
    val output: JsonNode?,
    val validations: JsonNode?,
    val error: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val cost: AgentExecutionCost?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val interventions: List<Intervention>,
)
