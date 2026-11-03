package elearning.ai

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// el registro de las ejecuciones de IA de un curso (RF-IA-03, docs/api/lms.md); solo lectura: lo escriben los pasos
@RestController
@RequestMapping("/api/lms/ai/runs")
class AgentExecutionController(
    private val reader: AgentExecutionReader,
) {
    @GetMapping
    suspend fun ofCourse(
        @RequestParam courseId: UUID,
    ): List<AgentExecutionLine> = reader.ofCourse(courseId)

    @GetMapping("/{id}")
    suspend fun find(
        @PathVariable id: UUID,
    ): AgentExecution = reader.find(id)
}
