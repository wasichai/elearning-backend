package elearning.learning

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// el espacio docente (ADR-0016), con sesión: lo decide la asignación, no el rol
@RestController
@RequestMapping("/api/lms/teaching")
class TeachingController(
    private val reader: TeachingReader,
) {
    @GetMapping("/offerings")
    suspend fun offerings(): List<TeachingOffering> = reader.offerings()

    @GetMapping("/offerings/{code}/students")
    suspend fun students(
        @PathVariable code: String,
    ): List<RosterRow> = reader.students(code)
}
