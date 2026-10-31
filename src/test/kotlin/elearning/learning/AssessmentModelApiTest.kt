package elearning.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// las evaluaciones son temario (ADR-0016 §2): las escribe quien escribe el borrador, se congelan con su versión y el
// estudiante nunca las lee, porque llevan las soluciones
class AssessmentModelApiTest : ClassroomApiTestSupport() {
    private val creator by lazy { demoUser("creador") }

    private fun patch(
        token: String,
        path: String,
        attributes: Map<String, Any?>,
    ): HttpStatus =
        HttpStatus.valueOf(
            client
                .patch()
                .uri(path)
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )

    private fun create(
        token: String,
        obj: String,
        attributes: Map<String, Any?>,
    ): Pair<HttpStatus, String?> {
        val (status, body) = call(token, "/api/objects/$obj/records", mapOf("attributes" to attributes))
        return status to body?.get("id")?.asString()
    }

    @Test
    fun `a creator writes an evaluation with its questions in a draft`() {
        val draft = get("/api/objects/version_curso/records?estado=BORRADOR&autor_id=${userId(creator)}&size=1", admin)["content"][0]["id"].asString()
        val unit = get("/api/objects/unidad/records?version_curso=$draft&size=1", admin)["content"][0]["id"].asString()
        val (_, lesson) =
            create(
                creator,
                "leccion",
                mapOf("unidad" to unit, "orden" to 900 + (0..99).random(), "titulo" to "Evaluación", "tipo" to "EVALUACION"),
            )

        val (created, evaluation) =
            create(
                creator,
                "evaluacion",
                mapOf("leccion" to lesson, "titulo" to "Evaluación", "intentos_max" to 1, "nota_minima" to 70),
            )
        assertEquals(HttpStatus.CREATED, created)
        val (_, question) = create(creator, "pregunta", mapOf("evaluacion" to evaluation, "orden" to 1, "tipo" to "UNICA", "enunciado" to "¿?", "puntos" to 1))
        assertEquals(HttpStatus.CREATED, create(creator, "opcion", mapOf("pregunta" to question, "orden" to 1, "texto" to "Sí", "correcta" to true)).first)
        // una sola evaluación por lección
        assertTrue(
            create(creator, "evaluacion", mapOf("leccion" to lesson, "titulo" to "Otra", "intentos_max" to 1, "nota_minima" to 70)).first.is4xxClientError,
        )
    }

    @Test
    fun `a published evaluation and its questions, options and rubric do not change`() {
        val course = course(withEvaluation = true)
        val single = course.questions.getValue("SINGLE")
        val open = course.questions.getValue("OPEN")
        val option = get("/api/objects/opcion/records?pregunta=$single&size=1", admin)["content"][0]["id"].asString()

        assertEquals(HttpStatus.CONFLICT, patch(admin, "/api/objects/evaluacion/records/${course.evaluation}", mapOf("nota_minima" to 10)))
        assertEquals(HttpStatus.CONFLICT, patch(admin, "/api/objects/pregunta/records/$single", mapOf("enunciado" to "Otra")))
        assertEquals(HttpStatus.CONFLICT, patch(admin, "/api/objects/opcion/records/$option", mapOf("correcta" to false)))
        assertEquals(HttpStatus.CONFLICT, create(admin, "opcion", mapOf("pregunta" to single, "orden" to 9, "texto" to "Nueva", "correcta" to true)).first)
        assertEquals(
            HttpStatus.CONFLICT,
            create(admin, "criterio_rubrica", mapOf("pregunta" to open, "orden" to 9, "descripcion" to "Nueva", "puntos" to 1)).first,
        )
        assertEquals(
            HttpStatus.CONFLICT,
            create(
                admin,
                "evaluacion",
                mapOf(
                    "leccion" to course.reading,
                    "titulo" to "Tarde",
                    "intentos_max" to 1,
                    "nota_minima" to 50,
                ),
            ).first,
        )
    }

    @Test
    fun `teachers do not read the answer keys either, the snapshot is enough to grade`() {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }

        for (obj in listOf("evaluacion", "pregunta", "opcion", "criterio_rubrica")) {
            assertEquals(HttpStatus.FORBIDDEN, call(teacher, "/api/objects/$obj/records").first, obj)
        }
    }

    @Test
    fun `students never read evaluations, questions, options or rubrics, not even enrolled`() {
        val course = course(withEvaluation = true)
        val student = newStudent()
        enroll(student, course)

        for (obj in listOf("evaluacion", "pregunta", "opcion", "criterio_rubrica")) {
            assertEquals(HttpStatus.FORBIDDEN, call(student, "/api/objects/$obj/records").first, obj)
        }
        assertEquals(HttpStatus.FORBIDDEN, call(student, "/api/objects/pregunta/records/${course.questions.getValue("SINGLE")}").first)
    }
}
