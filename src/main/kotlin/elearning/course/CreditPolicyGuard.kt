package elearning.course

import elearning.course.CreditPolicyRecords.DRAFT
import elearning.course.CreditPolicyRecords.JURISDICTION
import elearning.course.CreditPolicyRecords.METHOD
import elearning.course.CreditPolicyRecords.PARAMETERS
import elearning.course.CreditPolicyRecords.POLICY
import elearning.course.CreditPolicyRecords.PUBLISHED_AT
import elearning.course.CreditPolicyRecords.PUBLISHED_BY
import elearning.course.CreditPolicyRecords.STATE
import elearning.course.CreditPolicyRecords.SYSTEM
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// la escritura con la que los comandos de política publican o retiran una versión: solo ellos cambian su estado y quién y
// cuándo se publicó (ADR-0036)
class CreditPolicyWrite : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CreditPolicyWrite>
}

// las políticas de crédito (politica_credito, RF-CRE-07, ADR-0036), para toda escritura de una persona, la API de registros
// incluida. lo publicado no cambia: una propuesta o una aprobación ligada a una versión de política sigue valiendo lo que
// valía
//
// - una política nace en borrador y solo se edita en borrador; publicada o retirada no cambia ni se borra (409)
// - su estado, quién y cuándo la publicó solo los cambian los comandos de publicar y retirar (409 por la API de registros)
// - el método es de su sistema y sus parámetros, los de la norma (ECTS 25–30 h, PE desde 16/32 h…) → 400 si no
// - la jurisdicción es un código ISO 3166-1 alfa-2 o EHEA (400)
// - la plataforma (userId nulo) es código de confianza y no pasa por aquí
@Component
@Order(GUARD_ORDER)
class CreditPolicyGuard : RecordWriteGuard {
    override suspend fun beforeWrite(change: RecordWrite) {
        if (change.userId == null || change.objectName != POLICY) return
        val attributes = change.attributes.orEmpty()
        when (change.kind) {
            RecordChangeKind.CREATED -> {
                if ((attributes[STATE]?.toString() ?: DRAFT) != DRAFT || attributes[PUBLISHED_BY] != null || attributes[PUBLISHED_AT] != null) {
                    throw ConflictException(BORN_A_DRAFT)
                }
                validate(attributes)
            }
            RecordChangeKind.DELETED -> {
                if (change.before?.get(STATE)?.toString() != DRAFT) throw ConflictException("Only a draft credit policy is deleted")
            }
            else -> {
                if (currentCoroutineContext()[CreditPolicyWrite] != null) return
                val from = change.before?.get(STATE)?.toString() ?: DRAFT
                if (from != DRAFT) throw ConflictException("A $from credit policy does not change: create a new version")
                if (attributes.containsKey(STATE) && (attributes[STATE]?.toString() ?: DRAFT) != DRAFT) throw ConflictException(BORN_A_DRAFT)
                if (attributes[PUBLISHED_BY] != null || attributes[PUBLISHED_AT] != null) throw ConflictException(BORN_A_DRAFT)
                validate(change.before.orEmpty() + attributes)
            }
        }
    }

    private fun validate(policy: Map<String, Any?>) {
        val method =
            try {
                CreditPolicyRecords.method(policy[METHOD]?.toString())
            } catch (e: IllegalArgumentException) {
                throw ValidationException("Invalid credit policy method", METHOD, e.message ?: "unknown method")
            }
        if (policy[SYSTEM]?.toString() != method.system.name) {
            throw ValidationException("Invalid credit policy system", SYSTEM, "method ${method.name} is of system ${method.system.name}")
        }
        val jurisdiction = policy[JURISDICTION]?.toString()
        if (jurisdiction != null && !JURISDICTIONS.matches(jurisdiction)) {
            throw ValidationException("Invalid jurisdiction", JURISDICTION, "an ISO 3166-1 alpha-2 code (PE, US…) or EHEA")
        }
        try {
            // la clave y el número aún pueden faltar en un borrador a medias: solo se comprueba la regla
            CreditPolicyRecords.policy(policy + mapOf(CreditPolicyRecords.KEY to "-", CreditPolicyRecords.NUMBER to "0"))
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid credit policy parameters", PARAMETERS, e.message ?: "invalid parameters")
        }
    }

    companion object {
        const val BORN_A_DRAFT = "A credit policy is written as a draft and published with POST /api/lms/credits/policies/{id}/publish"
        val JURISDICTIONS = Regex("^([A-Z]{2}|EHEA)$")
    }
}
