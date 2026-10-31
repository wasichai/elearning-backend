package elearning.importer

// analizador del grafo include:: de una fuente AsciiDoc (ADR-0010 etapa 1, ADR-0018 §5). Dominio puro: no toca disco ni
// red; lee por las funciones que recibe, que el importador conecta a la copia de la fuente en un commit exacto.
//
// Sigue los include:: relativos con su leveloffset y los atributos del documento; saca secciones, anclas, imágenes y
// referencias cruzadas con su procedencia, y reporta los defectos (RF-IMP-04) en vez de fallar. No reimplementa
// Asciidoctor: el texto de los manuales es dato no confiable y aquí solo se mira su estructura. Lo que no hace, a
// propósito: ni ifdef/ifndef/ifeval (se siguen ambas ramas), ni tag=/lines= (se reportan y se sigue el archivo entero),
// ni include:: remotos o absolutos (salen de la raíz y nunca se leen)
class IncludeGraphAnalyzer(
    // contenido de un archivo por su ruta relativa a la raíz, o null si no está
    private val read: (String) -> String?,
    // si existe un archivo que no hace falta leer (imágenes, otros libros enlazados)
    private val exists: (String) -> Boolean,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    fun analyze(master: String): IncludeAnalysis = Run(master).analyze()

    // el estado de un análisis: los atributos y el leveloffset son del documento entero, como en Asciidoctor
    private inner class Run(
        val master: String,
    ) {
        val files = linkedSetOf<String>()
        val includes = mutableListOf<IncludeEdge>()
        val sections = mutableListOf<Section>()
        val anchors = mutableListOf<Anchor>()
        val anchorIds = mutableSetOf<String>()
        val images = mutableListOf<ImageReference>()
        val references = mutableListOf<CrossReference>()
        val defects = mutableListOf<Defect>()
        val attributes = mutableMapOf("empty" to "", "sp" to " ")
        var levelOffset = 0

        fun analyze(): IncludeAnalysis {
            val text = read(master)
            if (text == null) {
                defects += Defect(DefectKind.MISSING_INCLUDE, Location(master, 0), master)
            } else {
                files += master
                process(master, text, listOf(master), verbatim = false)
            }
            // al final: una referencia puede apuntar a un ancla que aparece más adelante
            references.forEach { if (!resolves(it)) defects += Defect(DefectKind.BROKEN_REFERENCE, it.location, it.target) }
            return IncludeAnalysis(master, files.toList(), includes, sections, anchors, images, references, defects)
        }

        // verbatim: el archivo entra dentro de un bloque literal; solo cuentan sus include::
        fun process(
            path: String,
            text: String,
            stack: List<String>,
            verbatim: Boolean,
        ) {
            var comment: String? = null
            var delimiter: String? = null
            var pendingId: String? = null
            text.split('\n').forEachIndexed { index, raw ->
                val line = raw.trimEnd()
                val location = Location(path, index + 1)
                if (comment != null) {
                    if (line == comment) comment = null
                    return@forEachIndexed
                }
                // el preprocesador ve los include:: también dentro de los bloques literales
                INCLUDE.matchEntire(line)?.let {
                    if (it.groupValues[1].isEmpty()) include(it, location, stack, verbatim || delimiter != null)
                    return@forEachIndexed
                }
                if (verbatim) return@forEachIndexed
                if (delimiter != null) {
                    if (line == delimiter) delimiter = null
                    return@forEachIndexed
                }
                when {
                    COMMENT_BLOCK.matches(line) -> comment = line
                    line.startsWith("//") || line.isEmpty() -> Unit
                    VERBATIM_BLOCK.matches(line) -> delimiter = line
                    line.startsWith(FENCE) -> delimiter = FENCE
                    else -> {
                        ATTRIBUTE_ENTRY.matchEntire(line)?.let {
                            attributeEntry(it)
                            return@forEachIndexed
                        }
                        val blockId = (ANCHOR_LINE.matchEntire(line) ?: BLOCK_ID.matchEntire(line))?.groupValues?.get(1)
                        if (blockId != null) {
                            register(blockId, location)
                            pendingId = blockId
                            return@forEachIndexed
                        }
                        SECTION.matchEntire(line)?.let {
                            section(it, location, pendingId)
                            pendingId = null
                            return@forEachIndexed
                        }
                        // atributos de bloque y títulos de bloque no separan un ancla de su sección
                        if (BLOCK_ATTRIBUTES.matches(line)) return@forEachIndexed
                        val blockTitle = BLOCK_TITLE.matches(line)
                        val image = BLOCK_IMAGE.matchEntire(line)
                        if (image != null) {
                            image(image.groupValues[1], image.groupValues[2], location)
                        } else {
                            inline(line, location)
                        }
                        if (!blockTitle) pendingId = null
                    }
                }
            }
        }

        fun include(
            match: MatchResult,
            location: Location,
            stack: List<String>,
            verbatim: Boolean,
        ) {
            val written = match.groupValues[2]
            val options = OPTION.findAll(match.groupValues[3]).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim('"', '\'') }
            val target = substitute(written) ?: return report(DefectKind.UNRESOLVED_ATTRIBUTE, location, written)
            val resolved = resolve(parentOf(location.path), target) ?: return report(DefectKind.INCLUDE_OUTSIDE_ROOT, location, target)
            val unsupported = options.keys.filter { it in UNSUPPORTED_OPTIONS }
            if (unsupported.isNotEmpty()) report(DefectKind.UNSUPPORTED_INCLUDE_OPTION, location, "$resolved: ${unsupported.joinToString()}")
            if (resolved in stack) {
                return report(DefectKind.INCLUDE_CYCLE, location, (stack.dropWhile { it != resolved } + resolved).joinToString(" -> "))
            }
            if (stack.size > maxDepth) return report(DefectKind.INCLUDE_TOO_DEEP, location, resolved)
            val optional = listOfNotNull(options["opts"], options["options"]).any { "optional" in it.split(',').map(String::trim) }
            val text = read(resolved) ?: return if (optional) Unit else report(DefectKind.MISSING_INCLUDE, location, resolved)
            val saved = levelOffset
            options["leveloffset"]?.let { levelOffset = offset(it, levelOffset) }
            includes += IncludeEdge(location, resolved, levelOffset)
            files += resolved
            process(resolved, text, stack + resolved, verbatim)
            if ("leveloffset" in options) levelOffset = saved
        }

        fun attributeEntry(match: MatchResult) {
            val name = match.groupValues[2].lowercase()
            val unset = match.groupValues[1].isNotEmpty() || match.groupValues[3].isNotEmpty()
            if (unset) {
                attributes -= name
                if (name == "leveloffset") levelOffset = 0
                return
            }
            // un atributo que falta queda escrito tal cual, como con attribute-missing=skip
            val value = ATTRIBUTE_REFERENCE.replace(match.groupValues[4].trim()) { attributes[it.groupValues[1].lowercase()] ?: it.value }
            attributes[name] = value
            if (name == "leveloffset") levelOffset = offset(value, levelOffset)
        }

        fun section(
            match: MatchResult,
            location: Location,
            explicitId: String?,
        ) {
            val rawLevel = match.groupValues[1].length - 1
            val title = match.groupValues[2].trim()
            // el título del documento no lleva id salvo que se lo pongan
            val documentTitle = sections.isEmpty() && location.path == master && rawLevel == 0 && levelOffset == 0
            val id = explicitId ?: if (documentTitle) null else generatedId(title).also { register(it, location) }
            sections += Section(location, rawLevel + levelOffset, title, id)
        }

        // como Asciidoctor 2: idprefix + título en minúsculas sin caracteres inválidos, idseparator en lugar de espacios,
        // puntos y guiones, y _2, _3… si ya existe
        fun generatedId(title: String): String {
            val prefix = attributes["idprefix"] ?: "_"
            val separator = attributes["idseparator"] ?: "_"
            var id = prefix + title.lowercase().replace(INVALID_ID_CHARS, "")
            if (separator.isNotEmpty()) {
                val sep = separator.first()
                val replaced = if (sep == '-' || sep == '.') " .-" else " $sep.-"
                id = squeeze(id, replaced, sep).removeSuffix(sep.toString())
                if (prefix.isEmpty()) id = id.trimStart(sep)
            }
            if (id !in anchorIds) return id
            var count = 2
            while ("$id$separator$count" in anchorIds) count++
            return "$id$separator$count"
        }

        fun inline(
            line: String,
            location: Location,
        ) {
            INLINE_ANCHOR.findAll(line).filter { it.groupValues[1].isEmpty() }.forEach {
                register(it.groupValues[2].ifEmpty { it.groupValues[3] }, location)
            }
            CROSS_REFERENCE.findAll(line).filter { it.groupValues[1].isEmpty() }.forEach {
                references += CrossReference(location, it.groupValues[2].ifEmpty { it.groupValues[3] })
            }
            INLINE_IMAGE.findAll(line).forEach { image(it.groupValues[1], it.groupValues[2], location) }
        }

        // las imágenes se resuelven contra imagesdir desde la carpeta del maestro, no del fragmento que las nombra
        fun image(
            written: String,
            attributeList: String,
            location: Location,
        ) {
            val target = substitute(written) ?: return report(DefectKind.UNRESOLVED_ATTRIBUTE, location, written)
            val alt = alt(attributeList)
            val imagesDir = attributes["imagesdir"].orEmpty()
            if (isRemote(target) || target.startsWith("data:") || isRemote(imagesDir)) {
                images += ImageReference(location, target, null, alt)
                return
            }
            val combined = if (imagesDir.isEmpty()) target else "${imagesDir.trimEnd('/')}/$target"
            val path = if (isAbsolute(combined)) null else normalize(join(parentOf(master), combined))
            images += ImageReference(location, target, path, alt)
            when {
                path == null -> report(DefectKind.IMAGE_OUTSIDE_ROOT, location, target)
                !exists(path) -> report(DefectKind.MISSING_IMAGE, location, path)
            }
        }

        // «id», «archivo.adoc#id» o «archivo.adoc»; un libro que no se incluyó solo tiene que existir
        fun resolves(reference: CrossReference): Boolean {
            val target = reference.target
            val (file, fragment) =
                when {
                    '#' in target -> target.substringBefore('#') to target.substringAfter('#')
                    target.endsWith(".adoc") -> target to ""
                    else -> "" to target
                }
            if (file.isEmpty()) return fragment in anchorIds
            val path = resolve(parentOf(reference.location.path), file) ?: return false
            return if (path in files) fragment.isEmpty() || fragment in anchorIds else exists(path)
        }

        fun register(
            id: String,
            location: Location,
        ) {
            if (!anchorIds.add(id)) return report(DefectKind.DUPLICATE_ANCHOR, location, id)
            anchors += Anchor(id, location)
        }

        // null si alguna referencia a un atributo no está definida
        fun substitute(text: String): String? {
            var missing = false
            val result = ATTRIBUTE_REFERENCE.replace(text) { attributes[it.groupValues[1].lowercase()] ?: it.value.also { missing = true } }
            return if (missing) null else result
        }

        fun report(
            kind: DefectKind,
            location: Location,
            detail: String,
        ) {
            defects += Defect(kind, location, detail)
        }
    }

    companion object {
        // el límite por omisión de Asciidoctor (max-include-depth)
        const val DEFAULT_MAX_DEPTH = 64

        private const val FENCE = "```"
        private const val ID = """[\p{L}_:][\p{L}\p{N}_:.\-]*"""
        private val INCLUDE = Regex("""^(\\)?include::([^\s\[](?:[^\[]*[^\s\[])?)\[(.*)]$""")
        private val OPTION = Regex("""([\w-]+)\s*=\s*("[^"]*"|'[^']*'|[^,]*)""")
        private val UNSUPPORTED_OPTIONS = setOf("tag", "tags", "lines")
        private val COMMENT_BLOCK = Regex("""^/{4,}$""")
        private val VERBATIM_BLOCK = Regex("""^(-{4,}|\.{4,}|\+{4,})$""")
        private val ATTRIBUTE_ENTRY = Regex("""^:(!?)([\p{L}\p{N}_][\p{L}\p{N}_-]*)(!?):(?:[ \t]+(.*))?$""")
        private val ATTRIBUTE_REFERENCE = Regex("""\{([\p{L}\p{N}_][\p{L}\p{N}_-]*)}""")
        private val ANCHOR_LINE = Regex("""^\[\[($ID)(?:,[^\]]*)?]]$""")
        private val BLOCK_ID = Regex("""^\[#([\p{L}_:][\p{L}\p{N}_:\-]*)(?:[.%][^\],]*)?(?:,.*)?]$""")
        private val BLOCK_ATTRIBUTES = Regex("""^\[[^\[\]].*]$""")
        private val BLOCK_TITLE = Regex("""^\.[^.\s].*$""")
        private val SECTION = Regex("""^(={1,6})\s+(\S.*?)(?:\s+=+)?$""")
        private val BLOCK_IMAGE = Regex("""^image::([^\s\[](?:[^\[]*[^\s\[])?)\[(.*)]$""")
        private val INLINE_IMAGE = Regex("""(?<![\p{L}\p{N}_:\\])image:([^:\s\[](?:[^\[]*[^\s\[])?)\[([^\]]*)]""")
        private val INLINE_ANCHOR = Regex("""(\\)?(?:\[\[($ID)(?:,[^\]]*)?]]|anchor:($ID)\[[^\]]*])""")
        private val CROSS_REFERENCE = Regex("""(\\)?(?:<<([^\s<>,]+?)(?:\s*,[^>]*)?>>|xref:([^\s\[]+)\[[^\]]*])""")
        private val INVALID_ID_CHARS =
            Regex(
                """<[^>]+>|&(?:[a-z][a-z]+\d{0,2}|#\d\d\d{0,4}|#x[\da-f][\da-f][\da-f]{0,3});|[^ \p{L}\p{M}\p{N}\p{Pc}\-.]+?""",
            )
        private val REMOTE = Regex("""^[A-Za-z][A-Za-z0-9+.\-]+://""")
        private val DRIVE = Regex("""^[A-Za-z]:[/\\]""")

        private fun isRemote(target: String) = REMOTE.containsMatchIn(target)

        private fun isAbsolute(target: String) = target.startsWith('/') || target.startsWith('\\') || DRIVE.containsMatchIn(target)

        private fun parentOf(path: String) = path.substringBeforeLast('/', "")

        private fun join(
            directory: String,
            target: String,
        ) = if (directory.isEmpty()) target else "$directory/$target"

        // la ruta de un include:: relativa al archivo que lo escribe; null si es remota, absoluta o sale de la raíz
        private fun resolve(
            directory: String,
            target: String,
        ): String? = if (isRemote(target) || isAbsolute(target)) null else normalize(join(directory, target))

        // null si sube por encima de la raíz
        private fun normalize(path: String): String? {
            val parts = ArrayDeque<String>()
            for (part in path.replace('\\', '/').split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull() ?: return null
                    else -> parts.addLast(part)
                }
            }
            return parts.joinToString("/")
        }

        // leveloffset=+1 suma, -1 resta y 2 fija; un valor raro no cambia nada
        private fun offset(
            value: String,
            current: Int,
        ): Int {
            val trimmed = value.trim()
            val number = trimmed.removePrefix("+").toIntOrNull() ?: return if (trimmed.isEmpty()) 0 else current
            return if (trimmed.startsWith('+') || trimmed.startsWith('-')) current + number else number
        }

        // el primer atributo posicional, con o sin comillas
        private fun alt(attributeList: String): String {
            val text = attributeList.trim()
            if (text.startsWith('"')) return text.drop(1).substringBefore('"')
            return text.substringBefore(',').trim()
        }

        // como tr_s de Ruby: cada racha de caracteres del conjunto queda en un solo separador
        private fun squeeze(
            text: String,
            replaced: String,
            separator: Char,
        ): String {
            val result = StringBuilder()
            var previousReplaced = false
            for (char in text) {
                if (char in replaced) {
                    if (!previousReplaced) result.append(separator)
                    previousReplaced = true
                } else {
                    result.append(char)
                    previousReplaced = false
                }
            }
            return result.toString()
        }
    }
}
