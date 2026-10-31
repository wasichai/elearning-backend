package elearning.architecture

import java.io.File

// un archivo fuente: la ruta tal como se mostrará en el mensaje y su texto
data class SourceFile(
    val path: String,
    val text: String,
)

enum class Rule(
    val description: String,
) {
    WASICHAI_PACKAGE("nada bajo el paquete wasichai (CLAUDE.md, regla 7)"),
    BLOCKING_PERSISTENCE("sin JPA, Hibernate, Spring Data repositories ni JDBC bloqueante (CLAUDE.md, regla 2)"),
    WASICHAI_INTERNALS("sin clases internas de Wasichai (CLAUDE.md, regla 1)"),
    ROLE_NAME_LITERAL("nunca se compara un nombre de rol en código (CLAUDE.md, regla 4)"),
    PURE_WORKLOAD("elearning.workload es dominio puro: ni Spring, ni Wasichai, ni R2DBC"),
    PACKAGE_DAG("solo las dependencias de docs/architecture/overview.md §2.3"),
    CONCRETE_PROVIDER("ningún proveedor concreto de IA, almacenamiento, videoconferencia ni fuentes fuera de su adaptador (RNF-ARQ-08)"),
}

data class Violation(
    val rule: Rule,
    val path: String,
    val line: Int,
    val detail: String,
) {
    override fun toString() = "$path:$line: $detail [${rule.name}: ${rule.description}]"
}

// los .kt bajo root, en orden estable; la ruta del mensaje es la del archivo (relativa si root lo es)
fun readSources(root: File): List<SourceFile> =
    root
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .map { SourceFile(it.path, it.readText()) }
        .toList()

// escaneo de fuentes, como CoreArchitectureTest de Wasichai: sin ArchUnit ni bytecode. Las reglas leen el código sin
// comentarios, así que un comentario puede nombrar lo prohibido; las cadenas se conservan porque la regla de roles
// mira justamente sus literales. allowedDependencies: capacidad → capacidades que puede usar; el paquete raíz
// (la aplicación) puede usarlas todas
class SourceScanner(
    private val allowedDependencies: Map<String, Set<String>>,
    private val rootPackage: String = "elearning",
) {
    fun scan(sources: List<SourceFile>): List<Violation> = sources.flatMap(::scan)

    fun scan(source: SourceFile): List<Violation> {
        val lines = withoutComments(source.text).lines()
        val packageIndex = lines.indexOfFirst { PACKAGE.containsMatchIn(it) }
        val packageName = if (packageIndex < 0) "" else PACKAGE.find(lines[packageIndex])!!.groupValues[1]
        val violations = mutableListOf<Violation>()

        fun report(
            rule: Rule,
            index: Int,
            detail: String,
        ) {
            violations += Violation(rule, source.path, index + 1, detail)
        }

        lines.forEachIndexed { index, line ->
            if (WASICHAI_PACKAGE.containsMatchIn(line)) report(Rule.WASICHAI_PACKAGE, index, "declara el paquete '$packageName'")
            BLOCKING_PERSISTENCE.findAll(line).forEach { report(Rule.BLOCKING_PERSISTENCE, index, "usa ${it.value}") }
            WASICHAI_INTERNALS.findAll(line).forEach { report(Rule.WASICHAI_INTERNALS, index, "usa ${it.value}") }
            ROLE_NAME_LITERAL.findAll(line).forEach { report(Rule.ROLE_NAME_LITERAL, index, "literal de rol ${it.value}") }
            if (!isProviderAdapter(packageName)) {
                CONCRETE_PROVIDER.findAll(line).forEach { report(Rule.CONCRETE_PROVIDER, index, "usa ${it.value}") }
            }
            if (isWorkload(packageName)) {
                FRAMEWORK_REFERENCE.findAll(line).forEach { report(Rule.PURE_WORKLOAD, index, "$packageName usa ${it.value}") }
            }
        }
        violations += dagViolations(source, lines, packageIndex, packageName)
        return violations
    }

    private fun isProviderAdapter(packageName: String) =
        PROVIDER_ADAPTERS.any { packageName == "$rootPackage.$it" || packageName.startsWith("$rootPackage.$it.") }

    private fun isWorkload(packageName: String) = packageName == "$rootPackage.workload" || packageName.startsWith("$rootPackage.workload.")

    private fun dagViolations(
        source: SourceFile,
        lines: List<String>,
        packageIndex: Int,
        packageName: String,
    ): List<Violation> {
        // el paquete raíz es la aplicación: la única que puede juntar todas las capacidades
        if (packageName == rootPackage) return emptyList()
        val packageLine = maxOf(packageIndex, 0) + 1
        if (!packageName.startsWith("$rootPackage.")) {
            return listOf(Violation(Rule.PACKAGE_DAG, source.path, packageLine, "el paquete '$packageName' está fuera de '$rootPackage'"))
        }
        val own = packageName.removePrefix("$rootPackage.").substringBefore('.')
        val permitted =
            allowedDependencies[own]
                ?: return listOf(
                    Violation(Rule.PACKAGE_DAG, source.path, packageLine, "la capacidad '$own' no está en la tabla de dependencias permitidas"),
                )
        val reference = Regex("""\b${Regex.escape(rootPackage)}\.([a-z][a-z0-9_]*)\b""")
        return lines
            .withIndex()
            .filter { it.index != packageIndex }
            .flatMap { (index, line) -> reference.findAll(line).map { index to it.groupValues[1] } }
            .filter { (_, target) -> target != own && target !in permitted }
            .distinctBy { (_, target) -> target }
            .map { (index, target) -> Violation(Rule.PACKAGE_DAG, source.path, index + 1, "$rootPackage.$own no puede usar $rootPackage.$target") }
    }

    companion object {
        private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""")
        private val WASICHAI_PACKAGE = Regex("""^\s*package\s+wasichai\b""")
        private val BLOCKING_PERSISTENCE =
            Regex(
                """\b(jakarta\.persistence|org\.hibernate|org\.springframework\.data\.jpa|org\.springframework\.data\.repository|""" +
                    """org\.springframework\.data\.r2dbc\.repository|javax?\.sql|org\.springframework\.jdbc)\.[\w.*]*""",
            )
        private val WASICHAI_INTERNALS = Regex("""\b(RecordStore|PhysicalTableRecordStore|ObjectSchemaManager|AuditService)\b""")

        // entre comillas dobles (Kotlin) o simples (SQL dentro de una cadena), con o sin ROLE_ de Spring Security
        private val ROLE_NAME_LITERAL = Regex("""(["'])(?:ROLE_)?(ESTUDIANTE|DOCENTE|CREADOR|REVISOR|ADMIN)\1""", RegexOption.IGNORE_CASE)

        // SDK concretos (por paquete) y hosts de sus API (en cadenas, para quien llamara con WebClient): IA, almacenamiento
        // de objetos, videoconferencia y fuentes del importador. Solo sus adaptadores, previstos en docs/architecture/overview.md §2.3, los usan
        private val CONCRETE_PROVIDER =
            Regex(
                """\b(com\.openai|com\.anthropic|com\.aallam\.openai|com\.theokanning\.openai|com\.google\.genai|com\.google\.cloud\.vertexai|""" +
                    """com\.azure\.ai|org\.springframework\.ai\.(?:openai|anthropic|vertexai|bedrock|ollama|mistralai|azure)|""" +
                    """dev\.langchain4j\.model\.(?:openai|anthropic|vertexai|bedrock|ollama|mistralai|azure)|""" +
                    """software\.amazon\.awssdk|com\.amazonaws|io\.minio|com\.google\.cloud\.storage|com\.azure\.storage|""" +
                    """us\.zoom|com\.zoom|com\.google\.apps\.meet|com\.google\.api\.services\.meet|com\.microsoft\.graph|org\.jitsi|""" +
                    """com\.twilio|io\.agora)\.[\w.*]*|""" +
                    """\b(?:api\.openai\.com|api\.anthropic\.com|generativelanguage\.googleapis\.com|[\w-]+\.amazonaws\.com|""" +
                    """api\.zoom\.us|meet\.googleapis\.com|graph\.microsoft\.com|""" +
                    """api\.github\.com|raw\.githubusercontent\.com)\b""",
            )
        private val PROVIDER_ADAPTERS = setOf("ai", "media", "course.meeting", "importer.github")
        private val FRAMEWORK_REFERENCE = Regex("""\b(org\.springframework|wasichai|io\.r2dbc)\.[\w.*]*""")
    }
}

private enum class Mode { CODE, STRING, RAW_STRING }

// el texto con cada comentario cambiado por espacios y los saltos de línea intactos: los números de línea no se mueven.
// Sabe de cadenas, cadenas crudas, plantillas ${...} anidadas, literales de carácter, nombres entre acentos graves y
// comentarios de bloque anidados, lo justo para que un "//" dentro de una URL no se tome por comentario
internal fun withoutComments(text: String): String {
    val out = StringBuilder(text.length)
    // por cada plantilla abierta: a qué cadena se vuelve al cerrarla y cuántas llaves llevaba abiertas el código de fuera
    val templates = ArrayDeque<Pair<Mode, Int>>()
    var mode = Mode.CODE
    var braces = 0
    var i = 0

    fun copy(until: Int) {
        out.append(text, i, until)
        i = until
    }

    fun blank(until: Int) {
        while (i < until) out.append(if (text[i++] == '\n') '\n' else ' ')
    }

    fun openTemplate() {
        copy(i + 2)
        templates.addLast(mode to braces)
        braces = 0
        mode = Mode.CODE
    }

    while (i < text.length) {
        val c = text[i]
        when (mode) {
            Mode.CODE ->
                when {
                    text.startsWith("//", i) -> blank(text.indexOf('\n', i).let { if (it < 0) text.length else it })
                    text.startsWith("/*", i) -> blank(blockCommentEnd(text, i))
                    text.startsWith("\"\"\"", i) -> copy(i + 3).also { mode = Mode.RAW_STRING }
                    c == '"' -> copy(i + 1).also { mode = Mode.STRING }
                    c == '\'' -> copy(charLiteralEnd(text, i))
                    c == '`' -> copy(text.indexOf('`', i + 1).let { if (it < 0) text.length else it + 1 })
                    c == '{' && templates.isNotEmpty() -> copy(i + 1).also { braces++ }
                    c == '}' && templates.isNotEmpty() && braces == 0 -> {
                        copy(i + 1)
                        templates.removeLast().let { (outer, outerBraces) ->
                            mode = outer
                            braces = outerBraces
                        }
                    }
                    c == '}' && templates.isNotEmpty() -> copy(i + 1).also { braces-- }
                    else -> copy(i + 1)
                }
            Mode.STRING ->
                when {
                    c == '\\' -> copy(minOf(i + 2, text.length))
                    c == '"' || c == '\n' -> copy(i + 1).also { mode = Mode.CODE }
                    text.startsWith("\${", i) -> openTemplate()
                    else -> copy(i + 1)
                }
            Mode.RAW_STRING ->
                when {
                    text.startsWith("\"\"\"", i) -> copy(i + 3).also { mode = Mode.CODE }
                    text.startsWith("\${", i) -> openTemplate()
                    else -> copy(i + 1)
                }
        }
    }
    return out.toString()
}

// Kotlin anida los comentarios de bloque
private fun blockCommentEnd(
    text: String,
    start: Int,
): Int {
    var depth = 0
    var j = start
    while (j < text.length) {
        when {
            text.startsWith("/*", j) -> depth++.also { j += 2 }
            text.startsWith("*/", j) -> {
                j += 2
                if (--depth == 0) return j
            }
            else -> j++
        }
    }
    return text.length
}

// 'a', '\n', 'A', '"': hasta la comilla de cierre, sin pasar de la línea
private fun charLiteralEnd(
    text: String,
    start: Int,
): Int {
    var j = start + 1
    if (j < text.length && text[j] == '\\') j++
    j++
    while (j < text.length && text[j] != '\'' && text[j] != '\n') j++
    return minOf(j + 1, text.length)
}
