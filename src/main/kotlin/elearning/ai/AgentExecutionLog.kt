package elearning.ai

import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import wasichai.core.common.NotFoundException
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

// la escritura del registro AgentRun (RF-IA-03, ADR-0033 §7): la usan los pasos de la canalización desde su trabajo, que
// corre como la plataforma y ya comprobó los permisos de quien lo pidió (ADR-0022 §4). Aquí no se mira a quien llama.
// start abre la ejecución antes de llamar al modelo, finish la cierra una vez con su consumo y su coste, e intervene añade
// la decisión posterior de una persona. Ningún campo guarda una clave de proveedor (RNF-SEG-09): si alguna aparece en el
// texto (un error que la repite, una entrada pegada), se guarda tapada, y lo mismo el valor de una cabecera de credenciales
// (Authorization, x-api-key, api-key), aunque su clave no sea una de las configuradas
@Service
class AgentExecutionLog(
    private val store: AgentExecutionStore,
    private val rates: AiRates,
    private val clock: Clock,
    environment: Environment,
) {
    // las claves configuradas; las demasiado cortas no se buscan, taparían texto cualquiera
    private val secrets: List<String> =
        SECRET_VARIABLES.mapNotNull { environment.getProperty(it)?.trim() }.filter { it.length >= MIN_SECRET_LENGTH }.distinct()

    suspend fun start(run: NewAgentExecution): UUID =
        store.insert(
            run = run,
            inputs = json(run.inputs)!!,
            sources = json(run.sources)!!,
            settings = json(run.model?.settings),
            objective = redact(run.objective),
            startedAt = clock.instant(),
        )

    // el coste, con la tarifa que regía el día en que empezó; sin tarifa o sin consumo, null
    suspend fun finish(
        organization: UUID,
        id: UUID,
        outcome: AgentExecutionOutcome,
    ): AgentExecution {
        val run = store.find(organization, id) ?: throw NotFoundException("Agent execution $id not found")
        check(run.status in AgentExecutionOutcome.OPEN) { "agent execution $id is already closed (${run.status})" }
        val rate =
            if (run.provider != null && run.model != null) {
                rates.rateFor(run.provider, run.model, run.startedAt.atOffset(ZoneOffset.UTC).toLocalDate())
            } else {
                null
            }
        val cost = outcome.usage?.let { rate?.cost(it) }
        val closed =
            store.close(
                organization = organization,
                id = id,
                outcome = outcome,
                output = json(outcome.output),
                validations = json(outcome.validations),
                error = outcome.error?.let(::redact),
                cost = cost,
                finishedAt = clock.instant(),
            )
        check(closed) { "agent execution $id is already closed" }
        return store.find(organization, id)!!
    }

    suspend fun intervene(
        organization: UUID,
        id: UUID,
        intervention: Intervention,
    ): AgentExecution {
        val item = json(intervention.copy(note = intervention.note?.let(::redact)))!!
        if (!store.addIntervention(organization, id, item)) throw NotFoundException("Agent execution $id not found")
        return store.find(organization, id)!!
    }

    suspend fun find(
        organization: UUID,
        id: UUID,
    ): AgentExecution? = store.find(organization, id)

    // una clave dentro de un texto JSON solo puede estar dentro de una cadena: taparla deja el JSON válido
    private fun json(value: Any?): String? = value?.let { redact(AgentExecutionStore.MAPPER.writeValueAsString(it)) }

    private fun redact(text: String): String = secrets.fold(text) { acc, secret -> acc.replace(secret, REDACTED) }.replace(CREDENTIAL_HEADER, "$1: $REDACTED")

    private companion object {
        // las de los proveedores de ADR-0033 §2; una clave nueva entra aquí
        val SECRET_VARIABLES = listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY")
        const val MIN_SECRET_LENGTH = 8
        const val REDACTED = "[REDACTED]"

        // el valor, hasta el fin de línea, una comilla o una barra invertida: dentro de un JSON el texto sigue siendo válido.
        // sin límite de palabra delante: en un JSON, la cabecera que sigue a un salto de línea va pegada a su \n
        val CREDENTIAL_HEADER = Regex("""(?i)(authorization|x-api-key|api-key)\s*:\s*[^\r\n"'\\]+""")
    }
}
