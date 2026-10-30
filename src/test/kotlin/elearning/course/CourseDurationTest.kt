package elearning.course

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// RF-ACA-05: de 1 a 8 semanas o semestral (de 12 a 26 semanas), con sus casos límite
class CourseDurationTest {
    @Test
    fun `a course by weeks lasts from 1 to 8 weeks`() {
        assertThat(CourseDuration.problem("SEMANAS", 0)).isNotNull()
        assertThat(CourseDuration.problem("SEMANAS", 1)).isNull()
        assertThat(CourseDuration.problem("SEMANAS", 8)).isNull()
        assertThat(CourseDuration.problem("SEMANAS", 9)).isNotNull()
        assertThat(CourseDuration.problem("SEMANAS", -1)).isNotNull()
    }

    @Test
    fun `a semester course lasts a real semester, from 12 to 26 weeks`() {
        assertThat(CourseDuration.problem("SEMESTRAL", 11)).isNotNull()
        assertThat(CourseDuration.problem("SEMESTRAL", 12)).isNull()
        assertThat(CourseDuration.problem("SEMESTRAL", 16)).isNull()
        assertThat(CourseDuration.problem("SEMESTRAL", 26)).isNull()
        assertThat(CourseDuration.problem("SEMESTRAL", 27)).isNotNull()
    }

    @Test
    fun `an unknown kind of duration is a problem, not a pass`() {
        assertThat(CourseDuration.problem("TRIMESTRAL", 10)).isNotNull()
    }
}
