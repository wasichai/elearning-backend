package elearning.catalog

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers
import wasichai.core.autoconfigure.WasichaiSecurityAutoConfiguration

// la cadena del catálogo público (ADR-0006). una auto-configuración que corre después de la de Wasichai, y no un
// @Configuration escaneado: la cadena de Wasichai es @ConditionalOnMissingBean y, si la nuestra se registrara antes,
// retrocedería y la plataforma quedaría sin la suya. @Order(-1): esta se mira primero, solo para su ruta.
// sin servidor de recursos: un token no da nada aquí, todo es anónimo y de solo lectura
@AutoConfiguration(after = [WasichaiSecurityAutoConfiguration::class])
class PublicCatalogSecurityAutoConfiguration {
    @Bean
    @Order(-1)
    fun publicCatalogSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .securityMatcher(pathMatchers(PUBLIC_PATH))
            .authorizeExchange {
                it.pathMatchers(HttpMethod.GET, PUBLIC_PATH).permitAll()
                it.pathMatchers(HttpMethod.HEAD, PUBLIC_PATH).permitAll()
                it.anyExchange().denyAll()
            }
            // la misma CorsConfigurationSource que la cadena de Wasichai
            .cors { }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
            // 401 a secas: el punto de entrada por defecto añade WWW-Authenticate: Basic y el navegador abriría su
            // diálogo de credenciales; aquí no hay credenciales que pedir
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .build()

    companion object {
        const val PUBLIC_PATH = "/api/lms/public/**"
    }
}
