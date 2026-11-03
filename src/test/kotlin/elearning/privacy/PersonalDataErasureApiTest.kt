package elearning.privacy

import elearning.common.listAll
import elearning.jobs.Job
import elearning.jobs.JobRunner
import elearning.learning.ClassroomApiTestSupport
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import java.util.UUID

// la supresión de los datos personales de una persona de la demo (ADR-0037, RNF-SEG-11): al retirar su aceptación o vencer
// su plazo, un trabajo de la cola borra lo suyo de lms y de los Custom Objects y disocia lo que la organización conserva,
// con una constancia sin datos personales. repetirla no cambia nada (el @Scheduled está apagado: las vueltas, a mano)
class PersonalDataErasureApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var erasure: PersonalDataErasure

    // una estudiante con de todo: un curso terminado con certificado, otro en curso con un intento calificado y otro
    // abierto, eventos, progreso y un perfil docente propio
    private inner class Scene {
        val student = newStudent()
        val id: String = userId(student)
        val finished = course()
        val finishedEnrollment = enroll(student, finished)["id"].asString()
        val ongoing = course(withEvaluation = true)
        val ongoingEnrollment = enroll(student, ongoing)["id"].asString()
        val certificate: String
        val graded: String
        val open: String
        val profile: String

        init {
            ok(event(student, finishedEnrollment, finished.reading, "MARKED_COMPLETE"))
            ok(event(student, finishedEnrollment, finished.video, "VIDEO_ENDED"))
            certificate =
                call(student, "/api/lms/classroom/enrollments/$finishedEnrollment/certificate", mapOf("holderName" to "Ana Pérez"))
                    .second!!["code"]
                    .asString()
            ok(event(student, ongoingEnrollment, ongoing.reading, "STARTED"))
            graded = attempt()
            put(
                student,
                "/api/lms/classroom/attempts/$graded/answers",
                mapOf("answers" to listOf(mapOf("questionId" to ongoing.questions.getValue("SHORT"), "text" to "Ana Pérez dice padrón"))),
            )
            // calificado como lo dejaría su docente: solo cambia el estado y la nota, lo que el disparador permite
            sql("UPDATE lms.intento SET estado = 'GRADED', porcentaje = 70, aprobado = true WHERE id = '$graded'")
            open = attempt()
            profile =
                call(
                    admin,
                    "/api/objects/perfil_docente/records",
                    mapOf("attributes" to mapOf("nombre_publico" to "Ana Pérez", "usuario_id" to id, "biografia" to "Vive en Lima", "perfil_publico" to true)),
                ).second!!["id"]
                    .asString()
        }

        private fun attempt(): String =
            call(student, "/api/lms/classroom/enrollments/$ongoingEnrollment/lessons/${ongoing.assessment}/attempts", emptyMap()).let { (status, body) ->
                assertEquals(HttpStatus.CREATED, status, body.toString())
                body!!["id"].asString()
            }

        fun withdraw(): String {
            val r =
                client
                    .delete()
                    .uri("/api/lms/terms/acceptance")
                    .header("Authorization", student)
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
            assertEquals(HttpStatus.ACCEPTED.value(), r.status.value(), r.responseBody)
            return json.readTree(r.responseBody)["jobId"].asString()
        }
    }

    private fun sql(statement: String): Long =
        runBlocking {
            db
                .sql(statement)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    private fun count(
        table: String,
        condition: String,
    ): Long =
        runBlocking {
            db
                .sql("SELECT count(*) AS n FROM lms.$table WHERE $condition")
                .map { row, _ -> row.get("n", Number::class.java)!!.toLong() }
                .one()
                .awaitSingle()
        }

    private fun drain() {
        while (runner.runOnce() > 0) Unit
    }

    private fun constancy(job: String): Map<String, String> =
        runBlocking {
            db
                .sql(
                    "SELECT motivo, suprimidos::text AS suprimidos, disociados::text AS disociados, row_to_json(s)::text AS fila FROM lms.supresion s WHERE trabajo_id = :job",
                ).bind("job", UUID.fromString(job))
                .map { row, _ -> listOf("motivo", "suprimidos", "disociados", "fila").associateWith { row.get(it, String::class.java)!! } }
                .one()
                .awaitSingle()
        }

    private fun record(
        objectName: String,
        id: String,
    ): Map<String, Any?> = runBlocking { records.asPlatform(ORG) { records.get(objectName, UUID.fromString(id)).attributes } }

    @Test
    fun `withdrawing the acceptance erases the student's personal data and dissociates what is kept`() {
        val s = Scene()

        val job = s.withdraw()
        drain()

        // nada de lms ni de los Custom Objects la nombra
        for (table in listOf("evento_aprendizaje", "intento", "certificado")) assertEquals(0, count(table, "estudiante_id = '${s.id}'"), table)
        assertEquals(0, count("aceptacion_terminos", "usuario_id = '${s.id}'"))
        assertEquals(0, count("trabajo", "solicitante = '${s.id}'"))
        runBlocking {
            records.asPlatform(ORG) {
                for (name in listOf("matricula", "progreso_leccion", "asistencia", "entrega")) {
                    assertEquals(emptyList<Any>(), records.listAll(name, mapOf("estudiante_id" to s.id)), name)
                }
            }
        }
        // el intento abierto ya no existe; el calificado se queda sin sus respuestas ni su estudiante
        assertEquals(0, count("intento", "id = '${s.open}'"))
        assertEquals(1, count("intento", "id = '${s.graded}' AND respuestas = '[]'::jsonb AND porcentaje = 70"))
        // las matrículas se quedan con su resultado, sin la persona ni su correo; la que estaba activa, retirada
        val finished = record("matricula", s.finishedEnrollment)
        assertEquals(listOf("FINALIZADA", "APROBADO"), listOf(finished["estado"], finished["resultado"]).map { it.toString() })
        assertNotEquals(s.id, finished["estudiante_id"].toString())
        assertEquals(null, finished["estudiante_correo"])
        assertEquals("RETIRADA", record("matricula", s.ongoingEnrollment)["estado"].toString())
        // el certificado sigue verificando el código del papel, sin nombre
        val (found, verified) = call(null, "/api/lms/public/certificates/${s.certificate}")
        assertEquals(HttpStatus.OK, found)
        assertTrue(verified!!["holderName"].isNull, verified.toString())
        assertEquals(s.finished.offering, verified["offering"]["code"].asString())
        // el perfil docente queda sin contenido y fuera del catálogo
        val profile = record("perfil_docente", s.profile)
        assertEquals(PersonalDataErasure.ANONYMOUS_TEACHER, profile["nombre_publico"].toString())
        assertEquals(listOf(null, false), listOf(profile["biografia"], profile["perfil_publico"]))

        // la constancia lista lo suprimido y lo disociado, sin la persona
        val done = constancy(job)
        assertEquals("WITHDRAWN", done["motivo"])
        val erased = json.readTree(done["suprimidos"])
        val dissociated = json.readTree(done["disociados"])
        assertEquals(
            listOf(1, 1, 2, 1),
            listOf("intento", "certificado", "matricula", "perfil_docente").map { dissociated[it].asInt() },
            dissociated.toString(),
        )
        assertEquals(1, erased["intento"].asInt())
        assertEquals(1, erased["aceptacion_terminos"].asInt())
        assertTrue(erased["evento_aprendizaje"].asInt() >= 3, erased.toString())
        assertTrue(erased["progreso_leccion"].asInt() >= 3, erased.toString())
        assertFalse(s.id in done.getValue("fila") || "Ana" in done.getValue("fila"), done.toString())

        // sin aceptación no vuelve a matricularse
        assertEquals(HttpStatus.FORBIDDEN, call(s.student, "/api/lms/enrollments", mapOf("offering" to course().offering)).first)
    }

    @Test
    fun `running the same erasure again changes nothing and writes no second record of it`() {
        val s = Scene()
        val job = s.withdraw()
        drain()
        val graded = count("intento", "id = '${s.graded}'")

        val again =
            runBlocking {
                erasure.handle(Job(UUID.fromString(job), ORG, PersonalDataErasure.TYPE, """{"userId": "${s.id}", "reason": "WITHDRAWN"}""", 1, 3))
            }

        val result: JsonNode = json.readTree(again)
        assertTrue(result["erased"].values().all { it.asInt() == 0 }, again)
        assertTrue(result["dissociated"].values().all { it.asInt() == 0 }, again)
        assertEquals(1, count("supresion", "trabajo_id = '$job'"))
        assertEquals(graded, count("intento", "id = '${s.graded}'"))
    }

    @Test
    fun `an expired acceptance is erased by the sweep, and then the person can accept afresh`() {
        val student = newStudent()
        val id = userId(student)
        val enrollment = enroll(student, course())["id"].asString()
        sql("UPDATE lms.aceptacion_terminos SET aceptada_en = now() - interval '2 days', vence_en = now() - interval '1 day' WHERE usuario_id = '$id'")

        // vencida: ni matrícula ni progreso, y renovar espera a la supresión
        assertEquals(HttpStatus.FORBIDDEN, call(student, "/api/lms/enrollments", mapOf("offering" to course().offering)).first)
        val version = ok(call(student, "/api/lms/terms"))["current"]["version"].asInt()
        assertEquals(HttpStatus.CONFLICT, call(student, "/api/lms/terms/acceptance", mapOf("version" to version)).first)

        assertTrue(runBlocking { erasure.enqueueDue() } >= 1)
        drain()

        assertTrue(ok(call(student, "/api/lms/terms"))["acceptance"].isNull)
        assertEquals(0, count("evento_aprendizaje", "estudiante_id = '$id'"))
        assertNotEquals(id, record("matricula", enrollment)["estudiante_id"].toString())
        assertEquals(1, count("supresion s", "motivo = 'EXPIRED' AND EXISTS (SELECT 1 FROM lms.trabajo t WHERE t.id = s.trabajo_id AND t.clave = '$id')"))

        assertEquals(HttpStatus.OK, call(student, "/api/lms/terms/acceptance", mapOf("version" to version)).first)
        assertEquals(HttpStatus.CREATED, call(student, "/api/lms/enrollments", mapOf("offering" to course().offering)).first)
    }
}
