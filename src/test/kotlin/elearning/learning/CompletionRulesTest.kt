package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletionRulesTest {
    private fun facts(
        lessons: Pair<Int, Int> = 2 to 2,
        assessments: Pair<Int, Int> = 1 to 1,
        attendance: Pair<Int, Int> = 4 to 4,
    ) = CompletionFacts(lessons.first, lessons.second, assessments.first, assessments.second, attendance.first, attendance.second)

    private fun Completion.rule(name: String) = rules.single { it.rule == name }

    @Test
    fun `each rule cites its own count`() {
        val result =
            CompletionRules.evaluate(
                DeclaredRules(lessons = true, assessments = true, minAttendancePercent = 75),
                facts(
                    lessons = 1 to 2,
                    assessments =
                        0 to 1,
                    attendance = 3 to 4,
                ),
            )

        assertFalse(result.met)
        assertEquals(listOf("LESSONS", "ASSESSMENTS", "ATTENDANCE"), result.rules.map { it.rule })
        assertEquals("1 de 2 lecciones", result.rule("LESSONS").detail)
        assertEquals("0 de 1 evaluaciones aprobadas", result.rule("ASSESSMENTS").detail)
        assertEquals("3 de 4 sesiones (75 %, mínimo 75 %)", result.rule("ATTENDANCE").detail)
        assertTrue(result.rule("ATTENDANCE").met)
    }

    @Test
    fun `all declared rules must be met, and only those appear`() {
        val only = CompletionRules.evaluate(DeclaredRules(lessons = false, assessments = true), facts(lessons = 0 to 2))
        assertEquals(listOf("ASSESSMENTS"), only.rules.map { it.rule })
        assertTrue(only.met, "las lecciones no se declararon: no cuentan")

        assertFalse(CompletionRules.evaluate(DeclaredRules(lessons = true, assessments = true), facts(assessments = 0 to 1)).met)
        assertTrue(CompletionRules.evaluate(DeclaredRules(lessons = true, assessments = true, minAttendancePercent = 100), facts()).met)
    }

    @Test
    fun `passing a test does not pass the course while another rule is unmet`() {
        val result = CompletionRules.evaluate(DeclaredRules(lessons = true, assessments = true), facts(lessons = 1 to 2, assessments = 1 to 1))

        assertTrue(result.rule("ASSESSMENTS").met)
        assertFalse(result.met)
    }

    @Test
    fun `with nothing declared the lessons are required, so nobody finishes an empty promise`() {
        val result = CompletionRules.evaluate(DeclaredRules(), facts(lessons = 1 to 2))

        assertEquals(listOf("LESSONS"), result.rules.map { it.rule })
        assertFalse(result.met)
    }

    @Test
    fun `nothing to do never counts as done, and attendance below the minimum rounds down`() {
        assertFalse(CompletionRules.evaluate(DeclaredRules(lessons = true), facts(lessons = 0 to 0)).met)
        assertFalse(CompletionRules.evaluate(DeclaredRules(assessments = true), facts(assessments = 0 to 0)).met)
        assertFalse(CompletionRules.evaluate(DeclaredRules(minAttendancePercent = 67), facts(attendance = 2 to 3)).met)
        assertFalse(CompletionRules.evaluate(DeclaredRules(minAttendancePercent = 50), facts(attendance = 0 to 0)).met)
    }
}
