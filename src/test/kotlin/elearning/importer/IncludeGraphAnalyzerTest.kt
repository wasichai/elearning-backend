package elearning.importer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// RF-IMP-02 y RF-IMP-04 sobre fragmentos escritos para esta prueba (ADR-0018 §5): los manuales reales no se copian
class IncludeGraphAnalyzerTest {
    private fun analyze(
        files: Map<String, String>,
        master: String = "manual/libro.adoc",
        existing: Set<String> = emptySet(),
        maxDepth: Int = IncludeGraphAnalyzer.DEFAULT_MAX_DEPTH,
    ): IncludeAnalysis {
        val read = { path: String -> files[path] }
        return IncludeGraphAnalyzer(read, { it in files || it in existing }, maxDepth).analyze(master)
    }

    private fun IncludeAnalysis.kinds() = defects.map { it.kind }

    @Test
    fun `follows relative includes two levels deep and applies leveloffset to the sections`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "= Guía de prueba\n\ninclude::capitulos/01-inicio.adoc[leveloffset=+1]\n",
                    "manual/capitulos/01-inicio.adoc" to "= Inicio\n\nTexto.\n\ninclude::pasos/entrar.adoc[leveloffset=+1]\n",
                    "manual/capitulos/pasos/entrar.adoc" to "= Entrar\n\n== Objetivo\n\nEntrar al sistema.\n",
                ),
            )

        assertThat(analysis.files).containsExactly("manual/libro.adoc", "manual/capitulos/01-inicio.adoc", "manual/capitulos/pasos/entrar.adoc")
        assertThat(analysis.includes.map { it.target to it.levelOffset })
            .containsExactly("manual/capitulos/01-inicio.adoc" to 1, "manual/capitulos/pasos/entrar.adoc" to 2)
        assertThat(analysis.sections.map { it.title to it.level })
            .containsExactly("Guía de prueba" to 0, "Inicio" to 1, "Entrar" to 2, "Objetivo" to 3)
        assertThat(analysis.sections.last().location).isEqualTo(Location("manual/capitulos/pasos/entrar.adoc", 3))
        assertThat(analysis.defects).isEmpty()
    }

    @Test
    fun `the leveloffset of an include ends with the include, and an absolute one replaces the current`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "= Libro\n\ninclude::a.adoc[leveloffset=+1]\n\n== Después\n\ninclude::b.adoc[leveloffset=2]\n",
                    "manual/a.adoc" to "= A\n",
                    "manual/b.adoc" to ":leveloffset: -1\n\n= B\n",
                ),
            )

        assertThat(analysis.sections.map { it.title to it.level }).containsExactly("Libro" to 0, "A" to 1, "Después" to 1, "B" to 1)
    }

    @Test
    fun `a missing include is a defect unless it is optional`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "= Libro\n\ninclude::falta.adoc[]\ninclude::opcional.adoc[opts=optional]\n",
                ),
            )

        assertThat(analysis.defects).containsExactly(Defect(DefectKind.MISSING_INCLUDE, Location("manual/libro.adoc", 3), "manual/falta.adoc"))
    }

    @Test
    fun `an include that leaves the root, is absolute or remote is reported and never read`() {
        val asked = mutableListOf<String>()
        val files =
            mapOf(
                "manual/libro.adoc" to
                    "include::../../secreto.adoc[]\ninclude::/etc/passwd[]\ninclude::https://ejemplo.invalid/x.adoc[]\n" +
                    "include::C:/x.adoc[]\n",
            )
        val read = { path: String ->
            asked += path
            files[path]
        }
        val analysis = IncludeGraphAnalyzer(read, { it in files }).analyze("manual/libro.adoc")

        assertThat(analysis.kinds()).containsOnly(DefectKind.INCLUDE_OUTSIDE_ROOT).hasSize(4)
        assertThat(asked).containsExactly("manual/libro.adoc")
    }

    @Test
    fun `an include may climb directories while it stays inside the root`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/usuario/libro.adoc" to "include::../comun/acceso.adoc[]\n",
                    "manual/comun/acceso.adoc" to "== Acceso\n",
                ),
                master = "manual/usuario/libro.adoc",
            )

        assertThat(analysis.files).containsExactly("manual/usuario/libro.adoc", "manual/comun/acceso.adoc")
        assertThat(analysis.defects).isEmpty()
    }

    @Test
    fun `a cycle is reported once and not followed`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "include::a.adoc[]\n",
                    "manual/a.adoc" to "== A\n\ninclude::b.adoc[]\n",
                    "manual/b.adoc" to "== B\n\ninclude::a.adoc[]\n",
                ),
            )

        assertThat(analysis.defects)
            .containsExactly(
                Defect(DefectKind.INCLUDE_CYCLE, Location("manual/b.adoc", 3), "manual/a.adoc -> manual/b.adoc -> manual/a.adoc"),
            )
        assertThat(analysis.sections.map { it.title }).containsExactly("A", "B")
    }

    @Test
    fun `the same fragment may be included twice when there is no cycle`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "include::nota.adoc[]\ninclude::nota.adoc[]\n",
                    "manual/nota.adoc" to "Una nota.\n",
                ),
            )

        assertThat(analysis.includes).hasSize(2)
        assertThat(analysis.files).containsExactly("manual/libro.adoc", "manual/nota.adoc")
        assertThat(analysis.defects).isEmpty()
    }

    @Test
    fun `an include deeper than the limit is reported and not followed`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "include::n1.adoc[]\n",
                    "manual/n1.adoc" to "include::n2.adoc[]\n",
                    "manual/n2.adoc" to "include::n3.adoc[]\n",
                    "manual/n3.adoc" to "== Fondo\n",
                ),
                maxDepth = 2,
            )

        assertThat(analysis.defects).containsExactly(Defect(DefectKind.INCLUDE_TOO_DEEP, Location("manual/n2.adoc", 1), "manual/n3.adoc"))
        assertThat(analysis.sections).isEmpty()
    }

    @Test
    fun `attributes resolve in include targets, also when defined in an included file`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "include::atributos.adoc[]\n\ninclude::{comun}/acceso.adoc[]\ninclude::{nadie}/x.adoc[]\n",
                    "manual/atributos.adoc" to ":base: compartido\n:comun: {base}/comun\n",
                    "manual/compartido/comun/acceso.adoc" to "== Acceso\n",
                ),
            )

        assertThat(analysis.files).contains("manual/compartido/comun/acceso.adoc")
        assertThat(analysis.defects).containsExactly(Defect(DefectKind.UNRESOLVED_ATTRIBUTE, Location("manual/libro.adoc", 4), "{nadie}/x.adoc"))
    }

    @Test
    fun `tag and lines selections are not supported, so they are reported and the whole file is followed`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "include::a.adoc[tag=parte]\ninclude::b.adoc[lines=1..3]\n",
                    "manual/a.adoc" to "== A\n",
                    "manual/b.adoc" to "== B\n",
                ),
            )

        assertThat(analysis.kinds()).containsExactly(DefectKind.UNSUPPORTED_INCLUDE_OPTION, DefectKind.UNSUPPORTED_INCLUDE_OPTION)
        assertThat(analysis.sections.map { it.title }).containsExactly("A", "B")
    }

    @Test
    fun `sections take their explicit anchor or a generated one like asciidoctor, and the document title none`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to
                        "= Libro\n\n[[sec-alta]]\n== Dar de alta\n\n[#sec-baja.importante]\n== Dar de baja\n\n" +
                        "== Requisitos previos\n\n== Requisitos previos\n\n== Paso 1. ¿Qué hacer?\n",
                ),
            )

        assertThat(analysis.sections.map { it.id })
            .containsExactly(null, "sec-alta", "sec-baja", "_requisitos_previos", "_requisitos_previos_2", "_paso_1_qué_hacer")
        assertThat(analysis.anchors.map { it.id })
            .containsExactly("sec-alta", "sec-baja", "_requisitos_previos", "_requisitos_previos_2", "_paso_1_qué_hacer")
    }

    @Test
    fun `idprefix and idseparator change the generated anchors`() {
        val analysis = analyze(mapOf("manual/libro.adoc" to ":idprefix:\n:idseparator: -\n\n== Casos especiales\n"))

        assertThat(analysis.sections.single().id).isEqualTo("casos-especiales")
    }

    @Test
    fun `inline anchors count, and a duplicated explicit anchor is a defect`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "include::a.adoc[]\ninclude::b.adoc[]\n",
                    "manual/a.adoc" to "Texto con [[term-ruc,RUC]] y anchor:term-dni[DNI].\n",
                    "manual/b.adoc" to "[[term-ruc]]\nOtra vez.\n",
                ),
            )

        assertThat(analysis.anchors.map { it.id }).containsExactly("term-ruc", "term-dni")
        assertThat(analysis.defects).containsExactly(Defect(DefectKind.DUPLICATE_ANCHOR, Location("manual/b.adoc", 1), "term-ruc"))
    }

    @Test
    fun `cross references resolve across fragments and forward, and the broken ones are reported`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to "include::a.adoc[]\ninclude::b.adoc[]\n",
                    "manual/a.adoc" to "Ver <<sec-b>>, <<sec-b,la otra>>, xref:sec-b[aquí] y <<b.adoc#sec-b>>.\nVer <<no-existe>> y xref:b.adoc[].\n",
                    "manual/b.adoc" to "[[sec-b]]\n== B\n\nVer xref:otro-libro.adoc#x[otro] y <<a.adoc#perdida,perdida>>.\n",
                    "manual/otro-libro.adoc" to "= Otro\n",
                ),
            )

        assertThat(analysis.references.map { it.target })
            .containsExactly("sec-b", "sec-b", "sec-b", "b.adoc#sec-b", "no-existe", "b.adoc", "otro-libro.adoc#x", "a.adoc#perdida")
        assertThat(analysis.defects)
            .containsExactly(
                Defect(DefectKind.BROKEN_REFERENCE, Location("manual/a.adoc", 2), "no-existe"),
                Defect(DefectKind.BROKEN_REFERENCE, Location("manual/b.adoc", 4), "a.adoc#perdida"),
            )
    }

    @Test
    fun `a reference to a document that is neither included nor present is broken`() {
        val analysis = analyze(mapOf("manual/libro.adoc" to "Ver xref:fantasma.adoc[] y <<fantasma.adoc#algo>>.\n"))

        assertThat(analysis.kinds()).containsExactly(DefectKind.BROKEN_REFERENCE, DefectKind.BROKEN_REFERENCE)
    }

    @Test
    fun `images resolve against imagesdir from the master document and the missing ones are reported`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to ":imagesdir: imagenes\n\ninclude::capitulos/uno.adoc[]\n",
                    "manual/capitulos/uno.adoc" to
                        "[[fig-inicio]]\n.Pantalla de inicio\nimage::inicio.png[Pantalla de inicio, 600]\n\n" +
                        "Pulse image:icono.svg[\"Icono, de ayuda\"] o vea image::../fuera.png[].\n" +
                        "image::https://ejemplo.invalid/logo.png[Logo]\nimage::falta.png[Falta]\nimage::../../../../x.png[]\n",
                ),
                existing = setOf("manual/imagenes/inicio.png", "manual/imagenes/icono.svg"),
            )

        assertThat(analysis.images.map { Triple(it.target, it.path, it.alt) })
            .containsExactly(
                Triple("inicio.png", "manual/imagenes/inicio.png", "Pantalla de inicio"),
                Triple("icono.svg", "manual/imagenes/icono.svg", "Icono, de ayuda"),
                Triple("https://ejemplo.invalid/logo.png", null, "Logo"),
                Triple("falta.png", "manual/imagenes/falta.png", "Falta"),
                Triple("../../../../x.png", null, ""),
            )
        assertThat(analysis.anchors.map { it.id }).containsExactly("fig-inicio")
        assertThat(analysis.defects)
            .containsExactly(
                Defect(DefectKind.MISSING_IMAGE, Location("manual/capitulos/uno.adoc", 7), "manual/imagenes/falta.png"),
                Defect(DefectKind.IMAGE_OUTSIDE_ROOT, Location("manual/capitulos/uno.adoc", 8), "../../../../x.png"),
            )
    }

    @Test
    fun `verbatim and comment blocks hide sections, anchors, references and images, but listings still include`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to
                        "== Real\n\n----\n== No es sección\n<<no-es-ref>> [[no-es-ancla]] image::no.png[]\ninclude::codigo.txt[]\n----\n\n" +
                        "////\ninclude::comentado.adoc[]\n== Comentada\n////\n\n// include::tampoco.adoc[]\n" +
                        "\\include::escapado.adoc[]\n\n```kotlin\n== Tampoco\n```\n\n== Fin\n",
                    "manual/codigo.txt" to "== Línea de código\n<<tampoco>>\n",
                ),
            )

        assertThat(analysis.sections.map { it.title }).containsExactly("Real", "Fin")
        assertThat(analysis.files).containsExactly("manual/libro.adoc", "manual/codigo.txt")
        assertThat(analysis.references).isEmpty()
        assertThat(analysis.images).isEmpty()
        assertThat(analysis.anchors.map { it.id }).containsExactly("_real", "_fin")
        assertThat(analysis.defects).isEmpty()
    }

    @Test
    fun `a missing master is a defect`() {
        val analysis = analyze(emptyMap())

        assertThat(analysis.files).isEmpty()
        assertThat(analysis.defects).containsExactly(Defect(DefectKind.MISSING_INCLUDE, Location("manual/libro.adoc", 0), "manual/libro.adoc"))
    }

    // aceptación de RF-IMP-04: una fuente con defectos sembrados los reporta todos
    @Test
    fun `a source with seeded defects reports every one of them`() {
        val analysis =
            analyze(
                mapOf(
                    "manual/libro.adoc" to
                        "= Libro\n:imagesdir: img\n\ninclude::uno.adoc[leveloffset=+1]\ninclude::falta.adoc[]\n" +
                        "include::../../fuera.adoc[]\ninclude::{indefinido}.adoc[]\ninclude::dos.adoc[tags=x]\n",
                    "manual/uno.adoc" to "[[dup]]\n= Uno\n\nVer <<rota>>.\n\nimage::nada.png[]\n\ninclude::uno.adoc[]\n",
                    "manual/dos.adoc" to "[[dup]]\n== Dos\n",
                ),
            )

        assertThat(analysis.kinds())
            .containsExactlyInAnyOrder(
                DefectKind.MISSING_INCLUDE,
                DefectKind.INCLUDE_OUTSIDE_ROOT,
                DefectKind.UNRESOLVED_ATTRIBUTE,
                DefectKind.UNSUPPORTED_INCLUDE_OPTION,
                DefectKind.INCLUDE_CYCLE,
                DefectKind.MISSING_IMAGE,
                DefectKind.DUPLICATE_ANCHOR,
                DefectKind.BROKEN_REFERENCE,
            )
    }
}
