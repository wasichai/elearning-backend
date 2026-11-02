package elearning.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// la gravedad de cada tipo de problema (E30.1): la que la interfaz derivaba con su propia tabla
class ProblemSeverityTest {
    @Test
    fun `what was not read is a gap and what was read but needs a look is a check`() {
        val gaps = setOf("MISSING_INCLUDE", "INCLUDE_OUTSIDE_ROOT", "INCLUDE_CYCLE", "INCLUDE_TOO_DEEP", "UNRESOLVED_ATTRIBUTE", "FILE_TOO_LARGE")
        val kinds = DefectKind.entries.map { it.name } + "FILE_TOO_LARGE"

        val graded = kinds.associateWith { ProblemSeverity.of(it) }

        assertEquals(kinds.associateWith { if (it in gaps) ProblemSeverity.GAP else ProblemSeverity.CHECK }, graded)
    }

    @Test
    fun `an unknown kind asks for attention`() {
        assertEquals(ProblemSeverity.GAP, ProblemSeverity.of("NUEVO"))
    }

    @Test
    fun `a stored report without severities gets them from its list`() {
        val report =
            ImportReport(
                "acme/manuales@sha:m.adoc",
                1,
                0,
                0,
                0,
                0,
                0,
                2,
                listOf(ImportProblem("INCLUDE_CYCLE", "o", 1, "d"), ImportProblem("MISSING_IMAGE", "o", 2, "d")),
            )

        val graded = report.withSeverity()

        assertEquals(listOf(ProblemSeverity.GAP, ProblemSeverity.CHECK), graded.problems.map { it.severity })
        assertEquals(mapOf(ProblemSeverity.GAP to 1, ProblemSeverity.CHECK to 1), graded.problemsBySeverity)
    }

    @Test
    fun `the stored counts win over the shown list`() {
        val report = ImportReport("s", 1, 0, 0, 0, 0, 0, 900, listOf(ImportProblem("MISSING_IMAGE", "o", 2, "d")), mapOf(ProblemSeverity.CHECK to 900))

        assertEquals(mapOf(ProblemSeverity.CHECK to 900), report.withSeverity().problemsBySeverity)
    }
}
