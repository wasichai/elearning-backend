package elearning.learning

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.PageRequest

// el espacio docente (ADR-0016), con sesión: lo decide la asignación, no el rol
@RestController
@RequestMapping("/api/lms/teaching")
class TeachingController(
    private val reader: TeachingReader,
) {
    @GetMapping("/offerings")
    suspend fun offerings(): List<TeachingOffering> = reader.offerings()

    // page y size opcionales, como en la API de registros: sin ninguno, la edición entera
    @GetMapping("/offerings/{code}/students")
    suspend fun students(
        @PathVariable code: String,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): List<RosterRow> = reader.students(code, if (page == null && size == null) null else PageRequest.of(page, size))
}
