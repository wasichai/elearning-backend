package elearning.common

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// la cabecera del contrato. el nombre del producto sale de la configuración, como en el resto
@Configuration
class OpenApiConfiguration {
    @Bean
    fun lmsOpenApi(properties: LmsProperties): OpenAPI =
        OpenAPI().info(
            Info()
                .title("${properties.productName} — API del LMS")
                .version("v1")
                .description("Contrato propio bajo /api/lms/**. La API de la plataforma es la de Wasichai. Ver docs/api/lms.md."),
        )
}
