package elearning.privacy

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// los términos de uso (ADR-0037), con sesión: leerlos y aceptarlos es de cualquier persona de la organización; publicar
// otra versión, de quien la administra
@RestController
@RequestMapping("/api/lms/terms")
class TermsController(
    private val terms: TermsService,
) {
    @GetMapping
    suspend fun view(): TermsView = terms.view()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun publish(
        @RequestBody body: PublishTermsRequest,
    ): TermsVersion = terms.publish(body)

    @PostMapping("/acceptance")
    suspend fun accept(
        @RequestBody body: AcceptTermsRequest,
    ): TermsAcceptance = terms.accept(body)

    // 202: la supresión corre en la cola
    @DeleteMapping("/acceptance")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun withdraw(): TermsWithdrawal = terms.withdraw()
}
