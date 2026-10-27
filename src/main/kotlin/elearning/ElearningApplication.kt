package elearning

import org.springframework.boot.runApplication
import wasichai.core.autoconfigure.WasichaiApplication

// nunca bajo el paquete wasichai: el escaneo registraría dos veces los controladores de la plataforma (ADR-024 de Wasichai)
@WasichaiApplication
class ElearningApplication

fun main(args: Array<String>) {
    runApplication<ElearningApplication>(*args)
}
