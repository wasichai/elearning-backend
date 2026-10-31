package elearning.learning

import elearning.StatementCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

// el coste del espacio docente (M4 de #183): el roster y la cola de corrección no deben leer por estudiante. antes, cada
// fila del roster armaba el aula entera del estudiante y leía su asistencia y sus intentos, y cada fila de la cola leía su
// matrícula y su lección: con 1000 estudiantes, miles de lecturas en un GET
@Import(StatementCount::class)
class TeachingCostApiTest : ClassroomApiTestSupport() {
    private inner class Scene {
        val course = course(withEvaluation = true)
        val teacher = newTeacher().also { assign(it, course) }

        // estudiantes con una lección completada y un intento enviado sin responder: la abierta vale 0 sola y queda GRADED
        fun students(count: Int) =
            repeat(count) {
                val student = newStudent()
                val enrollment = enroll(student, course)["id"].asString()
                ok(event(student, enrollment, course.reading, "MARKED_COMPLETE"))
                val attempt =
                    call(student, "/api/lms/classroom/enrollments/$enrollment/lessons/${course.assessment}/attempts", emptyMap())
                        .second!!["id"]
                        .asString()
                ok(call(student, "/api/lms/classroom/attempts/$attempt/submit", emptyMap()))
            }

        // el mínimo de varias lecturas: el catálogo se proyecta en segundo plano y puede sumar sentencias a una de ellas
        fun cost(
            path: String,
            rows: Int,
        ): Int =
            (1..3).minOf {
                StatementCount.statements.set(0)
                assertEquals(rows, ok(call(teacher, path)).size())
                StatementCount.statements.get()
            }
    }

    @Test
    fun `the roster costs about the same however many students it has`() {
        val s = Scene()
        val path = "/api/lms/teaching/offerings/${s.course.offering}/students"
        s.students(FEW)
        val small = s.cost(path, FEW)
        s.students(MORE)
        val big = s.cost(path, FEW + MORE)

        val perStudent = (big - small).toDouble() / MORE
        assertTrue(
            perStudent <= PER_STUDENT,
            "sentencias del roster: $small con $FEW estudiantes, $big con ${FEW + MORE} (≈$perStudent por estudiante)",
        )
    }

    @Test
    fun `the graded queue costs about the same however many attempts it has`() {
        val s = Scene()
        val path = "/api/lms/teaching/offerings/${s.course.offering}/attempts?status=GRADED"
        s.students(FEW)
        val small = s.cost(path, FEW)
        s.students(MORE)
        val big = s.cost(path, FEW + MORE)

        val perAttempt = (big - small).toDouble() / MORE
        assertTrue(
            perAttempt <= PER_STUDENT,
            "sentencias de la cola: $small con $FEW intentos, $big con ${FEW + MORE} (≈$perAttempt por intento)",
        )
    }

    companion object {
        const val FEW = 2
        const val MORE = 8

        // leer por lotes no depende del número de estudiantes; el margen cubre la proyección del catálogo en segundo plano
        const val PER_STUDENT = 0.5
    }
}
