package elearning.learning

// lo que una versión exige para finalizar (RF-ACA-09). sin nada declarado se exigen las lecciones
data class DeclaredRules(
    val lessons: Boolean = false,
    val assessments: Boolean = false,
    val minAttendancePercent: Int? = null,
)

data class CompletionFacts(
    val lessonsDone: Int,
    val lessonsTotal: Int,
    val assessmentsPassed: Int,
    val assessmentsTotal: Int,
    val sessionsAttended: Int,
    val sessionsTotal: Int,
)

data class RuleResult(
    val rule: String,
    val met: Boolean,
    val detail: String,
)

data class Completion(
    val met: Boolean,
    val rules: List<RuleResult>,
)

// dominio puro: cada regla se cumple o no y cita sus cifras; nada por hacer nunca cuenta como hecho
object CompletionRules {
    fun evaluate(
        declared: DeclaredRules,
        facts: CompletionFacts,
    ): Completion {
        val nothing = !declared.lessons && !declared.assessments && declared.minAttendancePercent == null
        val rules =
            buildList {
                if (declared.lessons || nothing) {
                    add(
                        RuleResult(
                            "LESSONS",
                            facts.lessonsTotal > 0 && facts.lessonsDone >= facts.lessonsTotal,
                            "${facts.lessonsDone} de ${facts.lessonsTotal} lecciones",
                        ),
                    )
                }
                if (declared.assessments) {
                    add(
                        RuleResult(
                            "ASSESSMENTS",
                            facts.assessmentsTotal > 0 && facts.assessmentsPassed >= facts.assessmentsTotal,
                            "${facts.assessmentsPassed} de ${facts.assessmentsTotal} evaluaciones aprobadas",
                        ),
                    )
                }
                declared.minAttendancePercent?.let { minimum ->
                    val percent = if (facts.sessionsTotal == 0) 0 else facts.sessionsAttended * 100 / facts.sessionsTotal
                    add(
                        RuleResult(
                            "ATTENDANCE",
                            facts.sessionsTotal > 0 && percent >= minimum,
                            "${facts.sessionsAttended} de ${facts.sessionsTotal} sesiones ($percent %, mínimo $minimum %)",
                        ),
                    )
                }
            }
        return Completion(rules.all { it.met }, rules)
    }
}
