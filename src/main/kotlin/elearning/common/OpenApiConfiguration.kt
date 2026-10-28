package elearning.common

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// la cabecera del contrato. el nombre del producto sale de la configuración, como en el resto
@Configuration
class OpenApiConfiguration {
    @Bean
    fun lmsOpenApi(properties: LmsProperties): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("${properties.productName} — API del LMS")
                    .version("v1")
                    .description("Contrato público bajo /api/lms/public/**. La API de la plataforma es la de Wasichai. Ver docs/api/lms.md."),
            )
            // servidor relativo: si no, springdoc lo arma con la cabecera Host de la petición, que controla el cliente
            .servers(listOf(Server().url("/")))
}
