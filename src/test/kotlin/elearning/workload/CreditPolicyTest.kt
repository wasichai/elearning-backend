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
    fun `the instruction floor is checked against the credits actually proposed`() {
        // 100 h con 30 de contacto: calculado 2,22, propuesto 2; 2 créditos piden 30 h de instrucción y las hay
        val workload = Workload(weeks = 10, weekly = WeeklyHours(theory = d("3"), autonomous = d("7")))
        val proposal = SemesterCreditHourPolicy().propose(workload)

        same("2", proposal.proposed)
        assertTrue(proposal.warnings.isEmpty(), proposal.warnings.toString())
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

    // Ley 30220 art. 39 (ADR-0036): 16 semanas × 2 h de teoría = 32 h / 16 = 2; × 2 h de práctica = 32 h / 32 = 1
    @Test
    fun `peru counts lective hours only, theory at 16 and practice at 32 hours per credit`() {
        val course = Workload(weeks = 16, weekly = WeeklyHours(theory = d("2"), practice = d("2"), autonomous = d("4")))
        val proposal = PeruLectiveHoursPolicy().propose(course)

        same("3", proposal.calculated)
        same("3", proposal.proposed)
        assertEquals(CreditSystem.PE_CREDITO, proposal.system)
        assertTrue("Ley 30220" in proposal.source)
        // las horas autónomas no cambian el resultado
        val moreStudy = Workload(weeks = 16, weekly = WeeklyHours(theory = d("2"), practice = d("2"), autonomous = d("20")))
        same("3", PeruLectiveHoursPolicy().propose(moreStudy).proposed)
        // las supervisadas tampoco, pero se avisa: su equivalencia la decide la universidad
        val supervised = PeruLectiveHoursPolicy().propose(Workload(weeks = 16, weekly = WeeklyHours(theory = d("2"), practice = d("2"), supervised = d("1"))))
        same("3", supervised.proposed)
        assertTrue(supervised.warnings.any { "supervisadas" in it }, supervised.warnings.toString())
    }

    @Test
    fun `peru never goes below the legal minimum of 16 and 32 hours`() {
        assertThrows<IllegalArgumentException> { PeruLectiveHoursPolicy(theoryHoursPerCredit = d("12")) }
        assertThrows<IllegalArgumentException> { PeruLectiveHoursPolicy(practiceHoursPerCredit = d("31")) }
    }

    @Test
    fun `a policy in data picks a method and its parameters, validated against the rule`() {
        val ects = CreditMethods.of(CreditMethod.ECTS_CARGA_TOTAL, mapOf("horasPorCredito" to "25"), "ects-upc", "3", "Reglamento de la UPC")
        val proposal = ects.propose(example)
        assertEquals("ects-upc", proposal.policyId)
        assertEquals("3", proposal.policyVersion)
        assertEquals("Reglamento de la UPC", proposal.source)
        same("8.5", proposal.proposed)

        // sin parámetros, los de referencia
        same(
            "3",
            CreditMethods
                .of(CreditMethod.PE_LEY30220_HORAS_LECTIVAS, emptyMap(), "pe", "1", "Ley 30220")
                .propose(
                    Workload(weeks = 16, weekly = WeeklyHours(theory = d("2"), practice = d("2"))),
                ).proposed,
        )
        same(
            "4.5",
            CreditMethods.of(CreditMethod.US_SCH_34CFR600_2, mapOf("instruccionDirecta" to "teoria+practica"), "sch", "1", "34 CFR").propose(example).proposed,
        )

        assertThrows<IllegalArgumentException> { CreditMethods.of(CreditMethod.ECTS_CARGA_TOTAL, mapOf("horasPorCredito" to "20"), "x", "1", "s") }
        assertThrows<IllegalArgumentException> {
            CreditMethods.of(
                CreditMethod.PE_LEY30220_HORAS_LECTIVAS,
                mapOf("horasTeoriaPorCredito" to "12"),
                "x",
                "1",
                "s",
            )
        }
        assertThrows<IllegalArgumentException> { CreditMethods.of(CreditMethod.ECTS_CARGA_TOTAL, mapOf("horasPorCredito" to "veintisiete"), "x", "1", "s") }
        assertThrows<IllegalArgumentException> { CreditMethods.of(CreditMethod.ECTS_CARGA_TOTAL, mapOf("factorDeConversion" to "1"), "x", "1", "s") }
        assertThrows<IllegalArgumentException> { CreditMethods.of(CreditMethod.US_SCH_34CFR600_2, mapOf("instruccionDirecta" to "todo"), "x", "1", "s") }
    }

    @Test
    fun `every method belongs to one system and every system has a reference policy`() {
        assertEquals(CreditSystem.entries.toSet(), CreditMethod.entries.map { it.system }.toSet())
        CreditSystem.entries.forEach { assertEquals(it, ReferencePolicies.of(it).system) }
    }

    @Test
    fun `there is no conversion between systems`() {
        // las políticas solo saben proponer a partir de la carga; ninguna recibe créditos de otro sistema
        CreditPolicy::class.java.methods
            .filter { it.declaringClass == CreditPolicy::class.java }
            .forEach { method -> assertTrue(method.parameterTypes.none { it == CreditProposal::class.java }, method.name) }
    }
}
