package elearning.workload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class CreditPolicyTest {
    private fun d(value: String) = BigDecimal(value)

    private fun same(
        expected: String,
        actual: BigDecimal,
    ) = assertEquals(0, d(expected).compareTo(actual), "esperaba $expected, fue $actual")

    // 6 × (12 + 12 + 12) = 216 h
    private val example = Workload(weeks = 6, weekly = WeeklyHours(theory = d("12"), practice = d("12"), autonomous = d("12")))

    @Test
    fun `ects divides the whole student workload by the hours of one credit`() {
        same("8.64", EctsPolicy(hoursPerCredit = d("25")).propose(example).calculated)
        same("8", EctsPolicy(hoursPerCredit = d("27")).propose(example).calculated)
        same("7.2", EctsPolicy(hoursPerCredit = d("30")).propose(example).calculated)
    }

    @Test
    fun `the proposal rounds down to the policy step, never awarding unearned credit`() {
        same("8.5", EctsPolicy(hoursPerCredit = d("25")).propose(example).proposed)
        same("7", EctsPolicy(hoursPerCredit = d("30")).propose(example).proposed)
        same("8", EctsPolicy(hoursPerCredit = d("25"), step = d("1")).propose(example).proposed)
    }

    @Test
    fun `ects hours per credit must stay within the 25 to 30 of the users guide`() {
        assertThrows<IllegalArgumentException> { EctsPolicy(hoursPerCredit = d("24")) }
        assertThrows<IllegalArgumentException> { EctsPolicy(hoursPerCredit = d("31")) }
    }

    @Test
    fun `semester credit hours follow 34 CFR 600_2 as equivalent total work`() {
        // 1 h directa + 2 h fuera de clase por semana, durante 15 semanas = 45 h por crédito
        val proposal = SemesterCreditHourPolicy().propose(example)

        same("4.8", proposal.calculated)
        same("4.5", proposal.proposed)
        assertEquals(CreditSystem.SCH, proposal.system)
    }

    @Test
    fun `semester credit hours also check the direct instruction floor`() {
        // 45 h de trabajo, pero solo 5 de instrucción directa: no alcanza el piso de 15 h por crédito
        val reading = Workload(weeks = 15, weekly = WeeklyHours(theory = d("0.3333"), autonomous = d("2.6667")))
        val proposal = SemesterCreditHourPolicy().propose(reading)

        same("1", proposal.calculated.setScale(0, java.math.RoundingMode.HALF_UP))
        assertTrue(proposal.warnings.any { "instrucción directa" in it }, proposal.warnings.toString())
    }

    @Test
    fun `a proposal carries everything needed to audit it and is never an approval`() {
        val proposal = EctsPolicy(hoursPerCredit = d("27")).propose(example)

        assertEquals(CreditSystem.ECTS, proposal.system)
        assertEquals("ects-carga-total", proposal.policyId)
        assertEquals("1", proposal.policyVersion)
        assertEquals("27", proposal.parameters["horasPorCredito"])
        assertTrue("ECTS Users' Guide" in proposal.source)
        assertEquals(CreditStatus.PROPUESTO, proposal.status)
        same("216", proposal.totalHours)
    }

    @Test
    fun `a course without hours earns no credit`() {
        val proposal = EctsPolicy().propose(Workload(weeks = 2, weekly = WeeklyHours()))

        same("0", proposal.calculated)
        same("0", proposal.proposed)
    }

    @Test
    fun `there is no conversion between systems`() {
        // las políticas solo saben proponer a partir de la carga; ninguna recibe créditos de otro sistema
        CreditPolicy::class.java.methods
            .filter { it.declaringClass == CreditPolicy::class.java }
            .forEach { method -> assertTrue(method.parameterTypes.none { it == CreditProposal::class.java }, method.name) }
    }
}
