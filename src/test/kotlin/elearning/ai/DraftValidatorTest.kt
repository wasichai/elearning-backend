package elearning.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// las reglas de la validación por código (RF-IA-05): un borrador limpio no tiene problemas; uno con un defecto sembrado
// por categoría los reporta todos, cada uno con su gravedad, y el hash cambia con el contenido
class DraftValidatorTest {
    private val sha = "a".repeat(40)
    private val trace = "wasichai/manual@$sha:capitulo.adoc#intro"

    private fun lesson(
        id: String = "l1",
        order: Int = 1,
        summary: String = "== Introducción\n\n=== Contexto\n\nVer https://docs.example.org/guia.\n\nimage::diagrama.png[Diagrama del flujo]",
        type: String = "EVALUACION",
        minutes: Int? = 30,
        sourceRef: String? = trace,
        labs: List<DraftLab> = emptyList(),
        evaluations: List<DraftEvaluation> = listOf(evaluation()),
        objective: String? = null,
    ) = DraftLesson(
        id = id,
        order = order,
        title = "Lección $order",
        type = type,
        minutes = minutes,
        sourceRef = sourceRef,
        texts = listOfNotNull("resumen" to summary, objective?.let { "objetivo" to it }).toMap(),
        resources = 0,
        labs = labs,
        evaluations = evaluations,
    )

    private fun evaluation(
        id: String = "e1",
        questions: List<DraftQuestion> = listOf(question()),
    ) = DraftEvaluation(id, "Evaluación", "Responde las preguntas", questions)

    private fun question(
        id: String = "q1",
        statement: String = "¿Qué es un padrón?",
        correct: Boolean = true,
    ) = DraftQuestion(id, 1, "UNICA", statement, null, listOf(DraftOption("o1", 1, "Un registro", correct), DraftOption("o2", 2, "Otro", false)))

    private fun unit(
        lessons: List<DraftLesson> = listOf(lesson()),
        objectives: String? = "Explicar el padrón",
        hours: String? = "2",
        sourceRef: String? = trace,
    ) = DraftUnit("u1", 1, "Unidad 1", hours, sourceRef, listOfNotNull(objectives?.let { "objetivos" to it }).toMap(), lessons)

    private fun draft(
        units: List<DraftUnit> = listOf(unit()),
        texts: Map<String, String> =
            mapOf(
                "objetivos" to "Usar el padrón",
                "resultados_aprendizaje" to "Consulta el padrón",
                "sistema_evaluacion" to "Una evaluación por unidad",
                "fuentes" to "wasichai/manual@$sha:manual.adoc",
            ),
    ) = DraftSnapshot("v1", texts, units)

    private fun kinds(snapshot: DraftSnapshot) =
        DraftValidator
            .validate(snapshot)
            .problems
            .map { it.kind }
            .toSet()

    @Test
    fun `a clean draft has no problems`() {
        val report = DraftValidator.validate(draft())
        assertEquals(emptyList<ValidationProblem>(), report.problems)
        assertFalse(report.blocking)
        assertEquals(0, report.problemCount)
        assertEquals(1, report.units)
        assertEquals(1, report.lessons)
        assertEquals(1, report.evaluations)
        assertEquals(DraftValidator.VERSION, report.validator)
    }

    @Test
    fun `a draft with a defect seeded per category reports them all, each with its severity`() {
        val defective =
            draft(
                // sin objetivos ni resultados de aprendizaje ni sistema de evaluación
                texts = mapOf("fuentes" to "wasichai/manual@$sha:manual.adoc"),
                units =
                    listOf(
                        unit(
                            hours = null,
                            lessons =
                                listOf(
                                    lesson(
                                        // salta de = a ===, imagen sin alt y un enlace http
                                        summary = "= Título\n\n=== Detalle\n\nimage::flujo.png[]\n\nVer http://docs.example.org/guia",
                                        evaluations = listOf(evaluation(questions = listOf(question(correct = false)))),
                                    ),
                                    lesson(
                                        id = "l2",
                                        order = 2,
                                        type = "LECTURA",
                                        summary = "= Título\n\n=== Detalle\n\nimage::flujo.png[]\n\nVer   http://docs.example.org/guia",
                                        evaluations = emptyList(),
                                        sourceRef = "no es una traza",
                                        minutes = null,
                                    ),
                                ),
                        ),
                    ),
            )
        val report = DraftValidator.validate(defective)
        val byKind = report.problems.associateBy { it.kind }

        assertEquals(ValidationCategory.entries.toSet(), report.problems.map { it.category }.toSet(), report.problems.toString())
        assertTrue(report.blocking)
        // estructura
        assertEquals(ValidationSeverity.BLOCKING, byKind.getValue(ValidationKind.QUESTION_WITHOUT_ANSWER).severity)
        // referencias
        assertEquals("leccion:l2", byKind.getValue(ValidationKind.INVALID_SOURCE_REF).origin)
        // alineación: sin resultados de aprendizaje
        assertEquals(ValidationCategory.ALIGNMENT, byKind.getValue(ValidationKind.MISSING_OUTCOMES).category)
        // enlaces, con su línea
        val link = byKind.getValue(ValidationKind.UNSAFE_LINK)
        assertEquals("http://docs.example.org/guia", link.detail)
        assertEquals("resumen", link.field)
        assertEquals(7, link.line)
        // duplicidad: el mismo resumen normalizado
        assertEquals("leccion:l2", byKind.getValue(ValidationKind.DUPLICATE_CONTENT).origin)
        // accesibilidad
        assertEquals(ValidationSeverity.BLOCKING, byKind.getValue(ValidationKind.IMAGE_WITHOUT_ALT).severity)
        assertEquals(ValidationSeverity.WARNING, byKind.getValue(ValidationKind.HEADING_LEVEL_SKIPPED).severity)
        // metadatos
        assertTrue(setOf(ValidationKind.MISSING_HOURS, ValidationKind.MISSING_DURATION, ValidationKind.MISSING_ASSESSMENT_SYSTEM).all { it in byKind })
        assertEquals(report.problems.size, report.problemCount)
        assertEquals(report.problems.count { it.severity == ValidationSeverity.BLOCKING }, report.problemsBySeverity[ValidationSeverity.BLOCKING])
    }

    @Test
    fun `structure, alignment and references have their own checks`() {
        assertTrue(ValidationKind.EMPTY_VERSION in kinds(draft(units = emptyList())))
        assertTrue(ValidationKind.EMPTY_UNIT in kinds(draft(units = listOf(unit(lessons = emptyList())))))
        val noEvaluation = kinds(draft(units = listOf(unit(lessons = listOf(lesson(type = "LECTURA", evaluations = emptyList()))))))
        assertTrue(ValidationKind.NO_ASSESSMENT in noEvaluation && ValidationKind.UNASSESSED_OBJECTIVES in noEvaluation, noEvaluation.toString())
        assertTrue(ValidationKind.LESSON_WITHOUT_EVALUATION in kinds(draft(units = listOf(unit(lessons = listOf(lesson(evaluations = emptyList())))))))
        assertTrue(
            ValidationKind.EMPTY_EVALUATION in
                kinds(draft(units = listOf(unit(lessons = listOf(lesson(evaluations = listOf(evaluation(questions = emptyList())))))))),
        )
        assertTrue(ValidationKind.UNALIGNED_EVALUATION in kinds(draft(units = listOf(unit(objectives = null)))))
        assertFalse(
            ValidationKind.UNALIGNED_EVALUATION in kinds(draft(units = listOf(unit(objectives = null, lessons = listOf(lesson(objective = "Consultar")))))),
        )
        assertTrue(ValidationKind.REPEATED_ORDER in kinds(draft(units = listOf(unit(lessons = listOf(lesson(), lesson(id = "l2", summary = "Otra")))))))
        assertTrue(ValidationKind.MISSING_SOURCE_REF in kinds(draft(units = listOf(unit(sourceRef = null)))))
        val elsewhere = "otra/fuente@${"b".repeat(40)}:x.adoc"
        assertTrue(ValidationKind.UNDECLARED_SOURCE in kinds(draft(units = listOf(unit(sourceRef = elsewhere)))))
    }

    @Test
    fun `links follow SafeLink and images need their alternative text in AsciiDoc, Markdown and HTML`() {
        fun with(summary: String) = kinds(draft(units = listOf(unit(lessons = listOf(lesson(summary = summary))))))
        for (bad in listOf("https://localhost/x", "https://10.0.0.1/x", "javascript:alert(1)", "ftp://example.org/a", "https://user@example.org/")) {
            assertTrue(ValidationKind.UNSAFE_LINK in with("Ver $bad"), bad)
        }
        assertFalse(ValidationKind.UNSAFE_LINK in with("Ver https://example.org/a, y luego https://example.com."))
        val labs = listOf(DraftLab("x", "Entorno", "http://lab.example.org"))
        assertTrue(ValidationKind.UNSAFE_LINK in kinds(draft(units = listOf(unit(lessons = listOf(lesson(labs = labs)))))))
        for (bad in listOf("![](a.png)", "<img src=\"a.png\">", "<img src='a.png' alt=' '>", "image:a.png[ , 200]")) {
            assertTrue(ValidationKind.IMAGE_WITHOUT_ALT in with(bad), bad)
        }
        for (good in listOf("![Un diagrama](a.png)", "<img alt=\"Diagrama\" src=\"a.png\">", "image::a.png[Diagrama, 200]")) {
            assertFalse(ValidationKind.IMAGE_WITHOUT_ALT in with(good), good)
        }
        assertTrue(ValidationKind.HEADING_LEVEL_SKIPPED in with("# Uno\n\n### Tres"))
        assertFalse(ValidationKind.HEADING_LEVEL_SKIPPED in with("## Dos\n\n### Tres\n\n## Dos\n\n# Uno"))
    }

    @Test
    fun `the hash is that of the content`() {
        assertEquals(DraftValidator.hash(draft()), DraftValidator.hash(draft()))
        assertNotEquals(DraftValidator.hash(draft()), DraftValidator.hash(draft(units = listOf(unit(hours = "3")))))
        assertEquals(DraftValidator.hash(draft()), DraftValidator.validate(draft()).contentHash)
    }
}
