package elearning.media.video

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// un problema del guion: dónde (secciones.0.subtitulos.1) y qué
data class ScriptViolation(
    val path: String,
    val message: String,
)

// el guion de vídeo (RF-MED-01) contra render-worker/schema/guion.schema.json (ADR-0040 §2), el mismo archivo que valida el
// trabajador: Gradle lo copia al classpath como render/guion.schema.json, así que no hay dos esquemas que puedan divergir.
// Interpreta solo el subconjunto de JSON Schema que ese archivo usa y se niega a cargar uno con otra palabra clave: una
// regla nueva del esquema no puede pasar sin aplicarse aquí. Después, las reglas que el esquema no expresa, las mismas que
// render-worker/src/guion.mjs (rules). Los ejemplos de render-worker/schema/ejemplos/ los prueban los dos lados.
// Ningún campo del guion es código: lo que no está en el esquema es un error, no se ignora
object ScriptSchema {
    private const val RESOURCE = "render/guion.schema.json"

    private val KEYWORDS =
        setOf(
            "\$schema",
            "\$id",
            "title",
            "description",
            "\$defs",
            "\$ref",
            "type",
            "properties",
            "required",
            "additionalProperties",
            "enum",
            "const",
            "pattern",
            "minLength",
            "maxLength",
            "minimum",
            "maximum",
            "minItems",
            "maxItems",
            "items",
            "oneOf",
        )

    private val mapper: JsonMapper = JsonMapper.builder().build()

    val schema: JsonNode by lazy {
        val text =
            ScriptSchema::class.java.classLoader
                .getResourceAsStream(RESOURCE)
                ?.use { it.readBytes().decodeToString() }
                ?: error("$RESOURCE is not on the classpath: Gradle copies it from render-worker/schema")
        load(text)
    }

    private val patterns = HashMap<String, Regex>()

    // un esquema con una palabra clave que este intérprete no conoce no se carga
    fun load(text: String): JsonNode {
        val root = mapper.readTree(text)
        val unknown = mutableSetOf<String>()

        fun walk(node: JsonNode) {
            if (!node.isObject) return
            for (name in node.propertyNames()) {
                if (name !in KEYWORDS) unknown += name
                val child = node.get(name)
                when (name) {
                    "properties", "\$defs" -> child.propertyNames().forEach { walk(child.get(it)) }
                    "items" -> walk(child)
                    "oneOf" -> child.values().forEach(::walk)
                }
            }
        }
        walk(root)
        check(unknown.isEmpty()) { "the script schema uses JSON Schema keywords that ScriptSchema does not apply: $unknown" }
        return root
    }

    // todo lo que tiene el guion contra el esquema y, si lo cumple, contra las reglas; maxSeconds null: sin tope de duración
    fun validate(
        script: JsonNode,
        maxSeconds: Int? = null,
    ): List<ScriptViolation> {
        val violations = schemaViolations(script)
        return violations.ifEmpty { rules(script, maxSeconds) }
    }

    fun schemaViolations(
        script: JsonNode,
        root: JsonNode = schema,
    ): List<ScriptViolation> = mutableListOf<ScriptViolation>().also { check(script, root, root, "", it) }

    // las reglas que JSON Schema no expresa (render-worker/src/guion.mjs, rules): ids de sección únicos; cada subtítulo
    // dentro de su sección, con desde < hasta y sin pisar al anterior; la duración total dentro de maxSeconds
    fun rules(
        script: JsonNode,
        maxSeconds: Int? = null,
    ): List<ScriptViolation> {
        val violations = mutableListOf<ScriptViolation>()
        val seen = mutableSetOf<String>()
        script.get("secciones").values().forEachIndexed { i, section ->
            val id = section.get("id").asString()
            if (!seen.add(id)) violations += ScriptViolation("secciones.$i.id", "the section id $id is repeated")
            val seconds = section.get("segundos").asDouble()
            var previous = 0.0
            section.get("subtitulos")?.values()?.forEachIndexed { j, subtitle ->
                val path = "secciones.$i.subtitulos.$j"
                val from = subtitle.get("desde").asDouble()
                val to = subtitle.get("hasta").asDouble()
                if (to <= from) violations += ScriptViolation(path, "a subtitle must end after it starts")
                if (to > seconds) violations += ScriptViolation(path, "a subtitle must end within its section (${seconds.toInt()} s)")
                if (from < previous) violations += ScriptViolation(path, "subtitles must be in order and must not overlap")
                previous = maxOf(previous, to)
            }
        }
        val total = durationOf(script)
        if (maxSeconds != null && total > maxSeconds) {
            violations += ScriptViolation("secciones", "the video lasts $total s, more than the $maxSeconds s allowed")
        }
        return violations
    }

    // la suma de las secciones, en segundos
    fun durationOf(script: JsonNode): Int = script.get("secciones").values().sumOf { it.get("segundos").asInt() }

    private fun check(
        node: JsonNode,
        rule: JsonNode,
        root: JsonNode,
        path: String,
        out: MutableList<ScriptViolation>,
    ) {
        rule.get("\$ref")?.let { ref ->
            val name = ref.asString().removePrefix("#/\$defs/")
            val target = root.get("\$defs")?.get(name) ?: error("the script schema has no definition $ref")
            return check(node, target, root, path, out)
        }
        rule.get("oneOf")?.let { return oneOf(node, it, root, path, out) }
        rule.get("const")?.let { if (!same(node, it)) out += ScriptViolation(path, "must be $it") }
        rule.get("enum")?.let { options ->
            if (options.values().none { same(node, it) }) out += ScriptViolation(path, "must be one of ${options.values().joinToString { it.toString() }}")
        }
        val type = rule.get("type")?.asString()
        if (type != null && !hasType(node, type)) {
            out += ScriptViolation(path, "must be ${article(type)}")
            return
        }
        when {
            node.isObject -> objectRules(node, rule, root, path, out)
            node.isArray -> arrayRules(node, rule, root, path, out)
            node.isString -> stringRules(node.asString(), rule, path, out)
            node.isNumber -> numberRules(node.asDouble(), rule, path, out)
        }
    }

    // exactamente una de las formas: si ninguna vale, los problemas de la que menos tiene
    private fun oneOf(
        node: JsonNode,
        options: JsonNode,
        root: JsonNode,
        path: String,
        out: MutableList<ScriptViolation>,
    ) {
        val results = options.values().map { option -> mutableListOf<ScriptViolation>().also { check(node, option, root, path, it) } }
        val matching = results.count { it.isEmpty() }
        when {
            matching == 1 -> Unit
            matching > 1 -> out += ScriptViolation(path, "matches more than one of the allowed shapes")
            else -> out += results.minBy { it.size }
        }
    }

    private fun objectRules(
        node: JsonNode,
        rule: JsonNode,
        root: JsonNode,
        path: String,
        out: MutableList<ScriptViolation>,
    ) {
        val properties = rule.get("properties")
        rule.get("required")?.values()?.forEach { required ->
            val name = required.asString()
            if (!node.has(name)) out += ScriptViolation(join(path, name), "is required")
        }
        for (name in node.propertyNames()) {
            val child = properties?.get(name)
            when {
                child != null -> check(node.get(name), child, root, join(path, name), out)
                rule.get("additionalProperties")?.isBoolean == true && !rule.get("additionalProperties").asBoolean() ->
                    out += ScriptViolation(join(path, name), "is not a field of the script")
            }
        }
    }

    private fun arrayRules(
        node: JsonNode,
        rule: JsonNode,
        root: JsonNode,
        path: String,
        out: MutableList<ScriptViolation>,
    ) {
        rule.get("minItems")?.let { if (node.size() < it.asInt()) out += ScriptViolation(path, "must have at least ${it.asInt()} items") }
        rule.get("maxItems")?.let { if (node.size() > it.asInt()) out += ScriptViolation(path, "must have at most ${it.asInt()} items") }
        rule.get("items")?.let { items -> node.values().forEachIndexed { i, child -> check(child, items, root, join(path, i.toString()), out) } }
    }

    // la longitud en puntos de código, como JSON Schema
    private fun stringRules(
        text: String,
        rule: JsonNode,
        path: String,
        out: MutableList<ScriptViolation>,
    ) {
        val length = text.codePointCount(0, text.length)
        rule.get("minLength")?.let { if (length < it.asInt()) out += ScriptViolation(path, "must have at least ${it.asInt()} characters") }
        rule.get("maxLength")?.let { if (length > it.asInt()) out += ScriptViolation(path, "must have at most ${it.asInt()} characters") }
        rule.get("pattern")?.let { pattern ->
            val regex = synchronized(patterns) { patterns.getOrPut(pattern.asString()) { Regex(pattern.asString()) } }
            if (!regex.containsMatchIn(text)) out += ScriptViolation(path, "must match ${pattern.asString()}")
        }
    }

    private fun numberRules(
        value: Double,
        rule: JsonNode,
        path: String,
        out: MutableList<ScriptViolation>,
    ) {
        rule.get("minimum")?.let { if (value < it.asDouble()) out += ScriptViolation(path, "must be at least ${it.asString()}") }
        rule.get("maximum")?.let { if (value > it.asDouble()) out += ScriptViolation(path, "must be at most ${it.asString()}") }
    }

    private fun hasType(
        node: JsonNode,
        type: String,
    ): Boolean =
        when (type) {
            "object" -> node.isObject
            "array" -> node.isArray
            "string" -> node.isString
            "number" -> node.isNumber
            // 3.0 también es entero, como en JSON Schema
            "integer" -> node.isNumber && node.decimalValue().stripTrailingZeros().scale() <= 0
            "boolean" -> node.isBoolean
            else -> error("the script schema uses the type $type, which ScriptSchema does not apply")
        }

    // 25 y 25.0 son el mismo número
    private fun same(
        node: JsonNode,
        expected: JsonNode,
    ): Boolean =
        if (node.isNumber && expected.isNumber) {
            node.decimalValue().compareTo(expected.decimalValue()) == 0
        } else {
            node == expected
        }

    private fun article(type: String) = if (type == "integer" || type == "array" || type == "object") "an $type" else "a $type"

    private fun join(
        path: String,
        name: String,
    ) = if (path.isEmpty()) name else "$path.$name"
}
