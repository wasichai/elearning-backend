package elearning.course

import elearning.workload.CreditMethod
import elearning.workload.CreditMethods
import elearning.workload.CreditPolicy
import elearning.workload.CreditSystem
import elearning.workload.ReferencePolicies
import tools.jackson.databind.json.JsonMapper

// una política de crédito en datos (politica_credito, ADR-0036) como regla de cálculo de workload, y la que propone para
// una versión de curso: la versión de política ligada o, sin ella, la de referencia de su sistema. la usan la guarda, la
// ficha del catálogo y la aprobación de créditos, para que las tres calculen igual
object CreditPolicyRecords {
    const val POLICY = "politica_credito"
    const val KEY = "clave"
    const val NUMBER = "numero"
    const val STATE = "estado"
    const val SYSTEM = "sistema"
    const val METHOD = "metodo"
    const val PARAMETERS = "parametros"
    const val SOURCE = "fuente"
    const val JURISDICTION = "jurisdiccion"
    const val PROGRAM = "programa_codigo"
    const val PUBLISHED_BY = "publicada_por"
    const val PUBLISHED_AT = "publicada_en"
    const val VERSION_SYSTEM = "sistema_credito"

    const val DRAFT = "BORRADOR"
    const val PUBLISHED = "PUBLICADA"
    const val RETIRED = "RETIRADA"

    private val JSON: JsonMapper = JsonMapper.builder().build()

    // los parámetros como texto: un objeto JSON de números o textos; vacío o ausente, ninguno (los de la norma)
    fun parameters(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val node = runCatching { JSON.readTree(text) }.getOrNull()
        require(node != null && node.isObject) { "parameters must be a JSON object" }
        return node.properties().associate { (name, value) ->
            require(value.isValueNode && !value.isNull) { "parameter $name must be a number" }
            name to value.asString()
        }
    }

    // la regla de una política guardada. sus datos ya los validó la guarda al escribirla; si no valen, IllegalArgumentException
    fun policy(attributes: Map<String, Any?>): CreditPolicy {
        val method = method(attributes[METHOD]?.toString())
        return CreditMethods.of(
            method,
            parameters(attributes[PARAMETERS]?.toString()),
            requireNotNull(attributes[KEY]?.toString()) { "policy without key" },
            requireNotNull(attributes[NUMBER]?.toString()) { "policy without number" },
            attributes[SOURCE]?.toString().orEmpty(),
        )
    }

    fun method(name: String?): CreditMethod =
        requireNotNull(CreditMethod.entries.firstOrNull { it.name == name }) { "unknown method '$name': one of ${CreditMethod.entries}" }

    fun system(name: String?): CreditSystem? = CreditSystem.entries.firstOrNull { it.name == name }

    // la política con la que propone una versión: la ligada (bound, sus atributos) si la hay y es de su sistema; si no está
    // ligada, la de referencia. ligada a otro sistema, ninguna: no se convierte uno en otro (RF-CRE-06)
    fun forVersion(
        version: Map<String, Any?>,
        bound: Map<String, Any?>?,
    ): CreditPolicy? {
        val system = system(version[VERSION_SYSTEM]?.toString()) ?: return null
        if (bound == null) return ReferencePolicies.of(system)
        return policy(bound).takeIf { it.system == system }
    }
}
