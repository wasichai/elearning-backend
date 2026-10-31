package elearning.importer

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// la importación del estudio (docs/api/lms.md): encolar responde 202 con el trabajo; el borrador y el informe llegan con él
@RestController
@RequestMapping("/api/lms/studio/import")
class ImportController(
    private val imports: ImportService,
) {
    @GetMapping("/sources")
    suspend fun sources(): List<ImportSource> = imports.sources()

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun start(
        @RequestBody body: ImportRequest,
    ): ImportJob = imports.start(body)

    @GetMapping("/{id}")
    suspend fun job(
        @PathVariable id: UUID,
    ): ImportJob = imports.job(id)
}
