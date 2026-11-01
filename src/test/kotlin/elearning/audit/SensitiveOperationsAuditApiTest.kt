package elearning.audit

import elearning.learning.ClassroomApiTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// RF-ADM-03: cada operación sensible deja un registro que ADMIN consulta (docs/security/README.md §6). contenidos, roles y
// publicaciones van al audit_log de Wasichai (/api/audit); las calificaciones, a lms.calificacion, de solo inserción, que
// ADMIN lee en el espacio docente como historial del intento: solo leerlo, calificar sigue siendo del docente asignado.
// y nadie más lee la auditoría ajena
class SensitiveOperationsAuditApiTest : ClassroomApiTestSupport() {
    private class Person(
        val email: String,
        val token: String,
    )

    private fun person(vararg roles: String): Person {
        val email = "${uniqueName("aud")}@demo.wasichai-learning.test"
        client
            .post()
            .uri("/api/users")
            .header("Authorization", admin)
            .bodyValue(mapOf("email" to email, "displayName" to "Persona de auditoría", "password" to DEMO_PASSWORD, "roles" to roles.toList()))
            .exchange()
            .expectStatus()
            .isCreated
        return Person(email, bearer(email, DEMO_PASSWORD))
    }

    private fun audit(
        objectName: String,
        recordId: String,
        token: String = admin,
    ): List<JsonNode> = get("/api/audit?objectName=$objectName&recordId=$recordId", token).values().toList()

    private fun JsonNode.changed(
        field: String,
        after: String,
    ) = this["changes"].values().any { it["field"].asString() == field && it["after"].asString() == after }

    private fun patch(
        token: String,
        objectName: String,
        id: String,
        attributes: Map<String, Any?>,
    ): HttpStatus =
        HttpStatus.valueOf(
            client
                .patch()
                .uri("/api/objects/$objectName/records/$id")
                .header("Authorization", token)
                .bodyValue(mapOf("attributes" to attributes))
                .exchange()
                .returnResult(String::class.java)
                .status
                .value(),
        )

    private fun courseId(code: String) = get("/api/objects/curso/records?codigo=$code", admin)["content"][0]["id"].asString()

    // un borrador nuevo de una persona CREADOR, copiado de un curso propio: su versión y su primera unidad y lección
    private inner class Draft(
        val author: Person,
    ) {
        val version: String
        val unit: String
        val lesson: String

        init {
            val (status, body) = call(author.token, "/api/lms/studio/courses/${courseId(course().offering)}/versions", mapOf("fromVersionId" to null))
            assertEquals(HttpStatus.CREATED, status, body.toString())
            version = body!!["id"].asString()
            val first = ok(call(author.token, "/api/lms/studio/versions/$version"))["units"][0]
            unit = first["id"].asString()
            lesson = first["lessons"][0]["id"].asString()
        }
    }

    @Test
    fun `content changes are audited with who made them`() {
        val author = person("CREADOR")
        val draft = Draft(author)

        assertEquals(HttpStatus.OK, patch(author.token, "version_curso", draft.version, mapOf("objetivos" to "Escribir avisos claros")))
        assertEquals(HttpStatus.OK, patch(author.token, "leccion", draft.lesson, mapOf("titulo" to "Lección revisada")))
        val (status, created) =
            call(
                author.token,
                "/api/objects/leccion/records",
                mapOf("attributes" to mapOf("unidad" to draft.unit, "orden" to 9, "titulo" to "Lección nueva", "tipo" to "LECTURA")),
            )
        assertEquals(HttpStatus.CREATED, status, created.toString())
        val newLesson = created!!["id"].asString()

        val version = audit("version_curso", draft.version)
        assertTrue(version.any { it["operation"].asString() == "CREATE" && it["userEmail"].asString() == author.email }, version.toString())
        assertTrue(version.any { it["userEmail"].asString() == author.email && it.changed("objetivos", "Escribir avisos claros") }, version.toString())
        val lesson = audit("leccion", draft.lesson)
        assertTrue(lesson.any { it["operation"].asString() == "UPDATE" && it.changed("titulo", "Lección revisada") }, lesson.toString())
        val added = audit("leccion", newLesson)
        assertTrue(added.any { it["operation"].asString() == "CREATE" && it["userEmail"].asString() == author.email }, added.toString())
        // también por el historial del registro
        assertTrue(get("/api/objects/leccion/records/${draft.lesson}/history", admin).values().any { it.changed("titulo", "Lección revisada") })
    }

    @Test
    fun `a publication is audited with its decision and who published it`() {
        val author = person("CREADOR")
        val reviewer = person("REVISOR")
        val draft = Draft(author)
        assertEquals("EN_REVISION", ok(call(author.token, "/api/lms/studio/versions/${draft.version}/submit", emptyMap()))["status"].asString())
        val decision = mapOf("decision" to "APROBAR", "reason" to null)
        assertEquals("APROBADA", ok(call(reviewer.token, "/api/lms/review/versions/${draft.version}/decision", decision))["status"].asString())
        assertEquals("PUBLICADA", ok(call(admin, "/api/lms/review/versions/${draft.version}/publish", emptyMap()))["status"].asString())

        val entries = audit("version_curso", draft.version)
        assertTrue(entries.any { it["userEmail"].asString() == reviewer.email && it.changed("estado", "APROBADA") }, entries.toString())
        assertTrue(entries.any { it["userEmail"].asString() == ADMIN_EMAIL && it.changed("estado", "PUBLICADA") }, entries.toString())
    }

    @Test
    fun `role changes are audited in the admin trail`() {
        val target = person("ESTUDIANTE")
        val id = get("/api/auth/me", target.token)["userId"].asString()
        val (status, body) = put(admin, "/api/users/$id/roles", mapOf("roles" to listOf("ESTUDIANTE", "DOCENTE")))
        assertEquals(HttpStatus.OK, status, body.toString())

        val entries = audit("admin:user", id)
        assertTrue(entries.any { it["operation"].asString() == "UPDATE" && it["userEmail"].asString() == ADMIN_EMAIL }, entries.toString())
    }

    // una calificación ya cambiada con motivo: ADMIN encuentra el intento en la cola de lo corregido y lee su historial
    @Test
    fun `grades are read by the administrator from the attempt history, but only the assigned teacher grades`() {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }
        val student = newStudent()
        val enrollment = enroll(student, course)["id"].asString()
        val attempt = call(student, "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts", emptyMap()).second!!["id"].asString()
        val open = course.questions.getValue("OPEN")
        ok(call(student, "/api/lms/classroom/attempts/$attempt/submit", emptyMap()))
        val grade = mapOf("questionId" to open, "points" to 2, "comment" to "Bien", "reason" to "Llegó por correo")
        ok(call(teacher, "/api/lms/teaching/attempts/$attempt/grades", grade))

        val graded = ok(call(admin, "/api/lms/teaching/offerings/${course.offering}/attempts?status=GRADED"))
        assertEquals(listOf(attempt), graded.strings { it["attemptId"] })
        val history = ok(call(admin, "/api/lms/teaching/attempts/$attempt"))["history"].single()
        assertEquals(userId(teacher), history["authorId"].asString())
        assertEquals("Llegó por correo", history["reason"].asString())
        assertEquals(2.0, history["points"].asDouble())

        // leer no es calificar: el administrador no está asignado y su nota no entra
        val other = mapOf("questionId" to open, "points" to 3, "reason" to "Otra nota")
        assertEquals(HttpStatus.NOT_FOUND, call(admin, "/api/lms/teaching/attempts/$attempt/grades", other).first)
        assertEquals(1, ok(call(admin, "/api/lms/teaching/attempts/$attempt"))["history"].size())
        // y quien no es ADMIN ni el docente asignado no lo encuentra
        val outsider = newTeacher().also { assign(it, course()) }
        for (token in listOf(outsider, student, newStudent())) {
            assertEquals(HttpStatus.NOT_FOUND, call(token, "/api/lms/teaching/offerings/${course.offering}/attempts?status=GRADED").first)
            assertEquals(HttpStatus.NOT_FOUND, call(token, "/api/lms/teaching/attempts/$attempt").first)
        }
    }

    @Test
    fun `nobody but the administrator reads the audit of others`() {
        val author = person("CREADOR")
        val draft = Draft(author)
        val student = newStudent()
        val enrollment = enroll(student, course())["id"].asString()
        val target = person("ESTUDIANTE")
        val targetId = get("/api/auth/me", target.token)["userId"].asString()
        assertEquals(HttpStatus.OK, put(admin, "/api/users/$targetId/roles", mapOf("roles" to listOf("ESTUDIANTE", "CREADOR"))).first)

        for (token in listOf(author.token, person("REVISOR").token, person("DOCENTE").token, newStudent(), student)) {
            // la auditoría de la organización pide READ de toda ella, que ningún rol del LMS tiene
            assertEquals(HttpStatus.FORBIDDEN, call(token, "/api/audit?objectName=version_curso&recordId=${draft.version}").first)
            assertEquals(HttpStatus.FORBIDDEN, call(token, "/api/audit").first)
            // el rastro de administración no existe para quien no administra: lista vacía, no 403
            assertEquals(0, ok(call(token, "/api/audit?objectName=admin:user&recordId=$targetId")).size())
        }
        // el historial de un registro es una lectura de ese registro: lo ajeno es 404 (RecordReadScope)
        val other = newStudent()
        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/objects/matricula/records/$enrollment/history").first)
        assertEquals(HttpStatus.NOT_FOUND, call(other, "/api/objects/version_curso/records/${draft.version}/history").first)
        // y ADMIN sí lo lee todo
        assertTrue(audit("version_curso", draft.version).isNotEmpty())
        assertTrue(audit("admin:user", targetId).isNotEmpty())
        assertEquals(HttpStatus.OK, call(admin, "/api/objects/matricula/records/$enrollment/history").first)
    }
}
