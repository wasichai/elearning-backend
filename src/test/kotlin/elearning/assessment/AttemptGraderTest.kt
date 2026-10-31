package elearning.assessment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AttemptGraderTest {
    private fun closed(
        id: String,
        type: String,
        vararg correct: String,
    ) = SnapshotQuestion(
        id = id,
        order = 1,
        type = type,
        prompt = "¿?",
        points = BigDecimal(2),
        options = listOf("a", "b", "c").map { SnapshotOption(it, it, it in correct) },
    )

    private fun snapshot(
        vararg questions: SnapshotQuestion,
        passing: Int = 60,
    ) = AssessmentSnapshot("e", "Evaluación", null, 1, null, passing, "NUNCA", questions.toList())

    private fun answer(
        id: String,
        vararg options: String,
        text: String? = null,
    ) = AttemptAnswer(id, options.toList(), text, null)

    @Test
    fun `closed questions score all or nothing`() {
        val s = snapshot(closed("single", "SINGLE", "a"), closed("multi", "MULTIPLE", "a", "b"))

        val all = AttemptGrader.grade(s, listOf(answer("single", "a"), answer("multi", "b", "a")))
        assertEquals(0, BigDecimal(4).compareTo(all.score))
        assertEquals(100, all.percent)
        assertEquals(true, all.passed)

        val partial = AttemptGrader.grade(s, listOf(answer("single", "b"), answer("multi", "a")))
        assertEquals(0, BigDecimal.ZERO.compareTo(partial.score), "marcar solo una de las dos correctas no puntúa")
        assertEquals(false, partial.passed)
    }

    @Test
    fun `a short answer ignores case and surrounding spaces and nothing else`() {
        val short = SnapshotQuestion("s", 1, "SHORT", "¿?", BigDecimal(1), acceptedAnswers = listOf("Padrón", "padron"))
        val s = snapshot(short)

        assertEquals(
            true,
            AttemptGrader
                .grade(s, listOf(answer("s", text = "  PADRÓN ")))
                .questions
                .single()
                .correct,
        )
        assertEquals(
            false,
            AttemptGrader
                .grade(s, listOf(answer("s", text = "el padrón")))
                .questions
                .single()
                .correct,
        )
    }

    @Test
    fun `an answered open question stays pending, an unanswered one scores zero`() {
        val open = SnapshotQuestion("o", 1, "OPEN", "¿?", BigDecimal(3))
        val s = snapshot(closed("c", "SINGLE", "a"), open)

        val pending = AttemptGrader.grade(s, listOf(answer("c", "a"), answer("o", text = "Porque sí")))
        assertTrue(pending.pending)
        assertNull(pending.percent)
        assertNull(pending.passed)
        assertNull(pending.questions.last().points)

        val blank = AttemptGrader.grade(s, listOf(answer("c", "a"), answer("o", text = "   ")))
        assertFalse(blank.pending)
        assertEquals(40, blank.percent)
    }

    @Test
    fun `the percent is rounded down so nobody passes by rounding`() {
        val s = snapshot(closed("a", "SINGLE", "a"), closed("b", "SINGLE", "a"), closed("c", "SINGLE", "a"), passing = 67)

        val two = AttemptGrader.grade(s, listOf(answer("a", "a"), answer("b", "a"), answer("c", "b")))

        assertEquals(66, two.percent)
        assertEquals(false, two.passed)
    }
}
