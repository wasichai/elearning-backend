package elearning.ai

import tools.jackson.databind.JsonNode
import java.io.File

// el esquema del guion del render (ADR-0040 §2, render-worker/schema/guion.schema.json) aplicado a un documento, para probar
// que lo que guarda el paso del guion es lo que acepta el trabajador. Interpreta solo las palabras clave que usa ese esquema
// (type, const, enum, pattern, minLength, maxLength, minimum, maximum, minItems, maxItems, required, properties,
// additionalProperties false, items, oneOf y $ref a #/$defs) y falla con cualquier otra, así que un esquema que cambie no
// pasa en silencio. Devuelve los problemas, vacía si el documento vale
object GuionSchema {
    private val schema: JsonNode by lazy { AiJobs.MAPPER.readTree(File(PATH).readText()) }

    fun violations(document: JsonNode): List<String> = mutableListOf<String>().also { check(schema, document, "$", it) }

    private fun check(
        node: JsonNode,
        value: JsonNode,
        path: String,
        out: MutableList<String>,
    ) {
        for (keyword in node.propertyNames()) {
            require(keyword in KNOWN) { "the script schema uses '$keyword', which this check does not know" }
        }
        node.get("\$ref")?.let { ref ->
            val name = ref.asString().removePrefix("#/\$defs/")
            check(schema.get("\$defs").get(name) ?: error("no \$defs/$name"), value, path, out)
            return
        }
        node.get("oneOf")?.let { options ->
            val matches = options.values().count { option -> mutableListOf<String>().also { check(option, value, path, it) }.isEmpty() }
            if (matches != 1) out += "$path matches $matches of the oneOf options"
            return
        }
        node.get("const")?.let { if (it != value) out += "$path is not $it" }
        node.get("enum")?.let { options -> if (options.values().none { it == value }) out += "$path is not one of $options" }
        when (node.get("type")?.asString()) {
            null -> Unit
            "object" -> if (!value.isObject) out += "$path is not an object"
            "array" -> if (!value.isArray) out += "$path is not an array"
            "string" -> if (!value.isString) out += "$path is not a string"
            "integer" -> if (!value.isIntegralNumber) out += "$path is not an integer"
            "number" -> if (!value.isNumber) out += "$path is not a number"
            else -> error("unknown type ${node.get("type")}")
        }
        if (value.isString) {
            val text = value.asString()
            node.get("minLength")?.let { if (text.codePointCount(0, text.length) < it.asInt()) out += "$path is shorter than $it" }
            node.get("maxLength")?.let { if (text.codePointCount(0, text.length) > it.asInt()) out += "$path is longer than $it" }
            node.get("pattern")?.let { if (!Regex(it.asString()).containsMatchIn(text)) out += "$path does not match $it" }
        }
        if (value.isNumber) {
            node.get("minimum")?.let { if (value.asDouble() < it.asDouble()) out += "$path is less than $it" }
            node.get("maximum")?.let { if (value.asDouble() > it.asDouble()) out += "$path is more than $it" }
        }
        if (value.isArray) {
            node.get("minItems")?.let { if (value.size() < it.asInt()) out += "$path has fewer than $it items" }
            node.get("maxItems")?.let { if (value.size() > it.asInt()) out += "$path has more than $it items" }
            node.get("items")?.let { items -> value.values().forEachIndexed { i, item -> check(items, item, "$path[$i]", out) } }
        }
        if (value.isObject) {
            val properties = node.get("properties")
            node.get("required")?.values()?.forEach { if (!value.has(it.asString())) out += "$path lacks ${it.asString()}" }
            if (node.get("additionalProperties")?.asBoolean(true) == false) {
                for (name in value.propertyNames()) if (properties?.has(name) != true) out += "$path has $name, which the schema does not allow"
            }
            properties?.propertyNames()?.forEach { name -> value.get(name)?.let { check(properties.get(name), it, "$path.$name", out) } }
        }
    }

    const val PATH = "render-worker/schema/guion.schema.json"

    private val KNOWN =
        setOf(
            "\$schema",
            "\$id",
            "\$defs",
            "\$ref",
            "title",
            "description",
            "type",
            "const",
            "enum",
            "pattern",
            "minLength",
            "maxLength",
            "minimum",
            "maximum",
            "minItems",
            "maxItems",
            "required",
            "properties",
            "additionalProperties",
            "items",
            "oneOf",
        )
}
