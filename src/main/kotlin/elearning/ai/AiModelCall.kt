package elearning.ai

import elearning.jobs.Job
import tools.jackson.databind.JsonNode

// la llamada de un paso a su generador (ADR-0033 §4): una por intento. La implementa cada paso (con Embabel o con el doble
// determinista); la política que la rodea (idempotencia, reintentos, cancelación, presupuesto y registro) es de AiStepRunner
fun interface AiModelCall {
    suspend fun call(prompt: AiPrompt): AiReply
}

// lo que un paso lee antes de llamar a su generador (AiStepRunner.run con read): el material que recibe el generador y las
// fuentes que leyó, con el hash de su contenido, que quedan en la ejecución
data class AiStepInput<M>(
    val material: M,
    val sources: List<SourceDigest>,
)

// el generador de un paso que lee antes de llamar: una llamada por intento, con su material
fun interface AiStepGenerator<M> {
    suspend fun generate(
        prompt: AiPrompt,
        material: M,
    ): AiReply
}

// lo que recibe el generador: solo las entradas de su ejecución (docs/ai §6). model null: el doble determinista
data class AiPrompt(
    val step: AgentStep,
    val objective: String,
    val inputs: JsonNode,
    val sources: List<SourceDigest>,
    val model: ModelChoice?,
    val promptVersion: String,
    val templateVersion: String,
)

// lo que devolvió, ya deserializado y validado contra el esquema del paso, con el consumo que informó el proveedor
data class AiReply(
    val output: Any,
    val usage: TokenUsage? = null,
    val validations: Any? = null,
)

// el proveedor respondió con un error HTTP (ADR-0033 §6). 408, 429 y 5xx son pasajeros: la cola reintenta con espera
// creciente y su tope. El resto (400, 401, 403, 404…) no cambia por reintentar
class AiProviderFailure(
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val transient: Boolean get() = isTransient(status)

    companion object {
        fun isTransient(status: Int): Boolean = status == 408 || status == 429 || status >= 500
    }
}

// la salida no cumple el esquema del paso o sus reglas: INVALID_OUTPUT, sin borrador y sin reintento de la cola (docs/ai §5).
// usage: lo que costó obtenerla, que se registra igual
class AiInvalidOutput(
    message: String,
    val usage: TokenUsage? = null,
    val validations: Any? = null,
) : RuntimeException(message)

// lo que escribe un paso que crea registros con su salida (AiStepRunner.runWriting). scope envuelve las dos fases (la del
// borrador: como la plataforma, donde lo que se sube y lo que lo adjunta van en el mismo bloque); prepare corre fuera de la
// transacción (lo que bloquea o sube archivos) y write dentro, la misma que cierra la ejecución y el trabajo, y devuelve la
// salida con que se cierra la ejecución
interface AiDraftWriter<P : Any> {
    suspend fun <T> scope(
        job: Job,
        block: suspend () -> T,
    ): T

    suspend fun prepare(
        request: AiStepPayload,
        reply: AiReply,
    ): P

    suspend fun write(
        request: AiStepPayload,
        reply: AiReply,
        prepared: P,
    ): Any
}
