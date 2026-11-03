package elearning.media.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

// el guion contra el mismo esquema y los mismos ejemplos que el trabajador (render-worker/schema, ADR-0040 §2): lo que
// render-worker/test/schema.test.mjs acepta o rechaza con zod y con ajv, aquí también. Los «regla-*» cumplen el esquema y
// solo los rechazan las reglas
class ScriptSchemaTest {
    private val mapper = JsonMapper.builder().build()
    private val examples = File("render-worker/schema/ejemplos")

    private fun examples(kind: String): List<Pair<String, JsonNode>> =
        File(examples, kind)
            .listFiles { file -> file.name.endsWith(".json") }!!
            .sortedBy { it.name }
            .map { it.name to mapper.readTree(it) }

    @TestFactory
    fun `the valid examples are valid`() =
        examples("validos").map { (name, script) ->
            DynamicTest.dynamicTest(name) { assertEquals(emptyList<ScriptViolation>(), ScriptSchema.validate(script, 900)) }
        }

    @TestFactory
    fun `the invalid examples are not, by the schema or only by a rule`() =
        examples("invalidos").map { (name, script) ->
            DynamicTest.dynamicTest(name) {
                assertTrue(ScriptSchema.validate(script, 900).isNotEmpty(), name)
                assertEquals(name.startsWith("regla-"), ScriptSchema.schemaViolations(script).isEmpty(), ScriptSchema.schemaViolations(script).toString())
            }
        }

    @Test
    fun `there are examples of both kinds`() {
        assertTrue(examples("validos").size >= 2)
        assertTrue(examples("invalidos").count { it.first.startsWith("regla-") } >= 3)
    }

    // ADR-0039 §5: solo datos. un componente, HTML o una URL no tienen dónde ir
    @Test
    fun `code, html and urls are named where they are`() {
        val component = ScriptSchema.validate(example("codigo-componente.json"))
        assertTrue(component.contains(ScriptViolation("componente", "is not a field of the script")), component.toString())
        val html = ScriptSchema.validate(example("diapositiva-html.json"))
        assertTrue(html.any { it.path == "secciones.0.diapositiva.html" && it.message == "is not a field of the script" }, html.toString())
        val url = ScriptSchema.validate(example("imagen-url.json"))
        assertTrue(url.contains(ScriptViolation("secciones.0.diapositiva.imagen", "must match ^[0-9a-f]{64}$")), url.toString())
    }

    @Test
    fun `the rules name the subtitle and the duration`() {
        assertEquals(
            listOf(ScriptViolation("secciones.0.subtitulos.0", "a subtitle must end within its section (3 s)")),
            ScriptSchema.validate(example("regla-subtitulo-fuera-de-su-seccion.json")),
        )
        val minimal = mapper.readTree(File(examples, "validos/minimo.json"))
        assertEquals(3, ScriptSchema.durationOf(minimal))
        assertEquals(listOf(ScriptViolation("secciones", "the video lasts 3 s, more than the 2 s allowed")), ScriptSchema.validate(minimal, 2))
    }

    @Test
    fun `whole numbers written with a decimal point are integers, as in json schema`() {
        val script = mapper.readTree(File(examples, "validos/minimo.json").readText().replace("\"segundos\": 3", "\"segundos\": 3.0"))
        assertEquals(emptyList<ScriptViolation>(), ScriptSchema.validate(script))
    }

    // una palabra clave nueva en el esquema no puede pasar sin que este intérprete la aplique
    @Test
    fun `a schema with a keyword it does not apply is refused`() {
        val error =
            assertThrows<IllegalStateException> { ScriptSchema.load("""{"type": "object", "properties": {"a": {"type": "string", "format": "uri"}}}""") }
        assertTrue(error.message!!.contains("[format]"), error.message)
    }

    @Test
    fun `every object of the schema is closed`() {
        val open = mutableListOf<String>()

        fun walk(
            node: JsonNode,
            at: String,
        ) {
            if (node.isArray) node.values().forEachIndexed { i, child -> walk(child, "$at/$i") }
            if (!node.isObject) return
            if (node.get("type")?.asString() == "object" && node.get("additionalProperties")?.asBoolean() != false) open += at
            node.propertyNames().forEach { walk(node.get(it), "$at/$it") }
        }
        walk(ScriptSchema.schema, "#")
        assertEquals(emptyList<String>(), open)
    }

    private fun example(name: String): JsonNode = mapper.readTree(File(examples, "invalidos/$name"))
}
