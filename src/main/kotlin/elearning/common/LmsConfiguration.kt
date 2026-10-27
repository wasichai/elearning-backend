package elearning.common

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import wasichai.core.platform.ModuleMigration
import java.time.Clock
import java.time.Duration

// lms.* — el nombre del producto es configuración, nunca dominio
@ConfigurationProperties("lms")
data class LmsProperties(
    val productName: String = "wasichai-learning",
    val public: Public = Public(),
    val catalog: Catalog = Catalog(),
) {
    // slug de la organización cuyo catálogo es público; vacío: no hay catálogo público
    data class Public(
        val organization: String = "",
    )

    data class Catalog(
        val refresh: Duration = Duration.ofSeconds(30),
        val scheduling: Boolean = true,
    )
}

@Configuration
@EnableScheduling
class LmsConfiguration {
    // las tablas fijas del LMS (esquema lms), con su propio historial de Flyway; después de los módulos de Wasichai
    @Bean
    fun lmsMigration(): ModuleMigration = ModuleMigration(MIGRATION_NAME, "classpath:db/lms", ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()

    companion object {
        const val MIGRATION_NAME = "lms"
        const val ORDER = 200
    }
}
