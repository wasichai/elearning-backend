package elearning.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

// cada regla de ArchitectureTest muerde: el mismo escáner, con la misma tabla, sobre fuentes sintéticas con una
// violación de cada tipo. Nada de esto vive en src/main
class SourceScannerTest {
    private val scanner = SourceScanner(ArchitectureTest.ALLOWED_DEPENDENCIES)

    private fun scan(
        path: String,
        text: String,
    ) = scanner.scan(SourceFile(path, text.trimIndent()))

    // regla, línea y que el mensaje nombra archivo:línea
    private fun assertSingle(
        violations: List<Violation>,
        rule: Rule,
        path: String,
        line: Int,
    ) {
        assertThat(violations.map { Triple(it.rule, it.path, it.line) }).containsExactly(Triple(rule, path, line))
        assertThat(violations.single().toString()).startsWith("$path:$line: ")
    }

    @Test
    fun `a file under the wasichai package is caught`() {
        val path = "src/main/kotlin/wasichai/core/Sneaky.kt"
        val violations = scan(path, "package wasichai.core\n\nclass Sneaky")

        assertThat(violations.filter { it.rule == Rule.WASICHAI_PACKAGE }.map { it.line }).containsExactly(1)
        assertThat(violations.first { it.rule == Rule.WASICHAI_PACKAGE }.toString()).startsWith("$path:1: declara el paquete 'wasichai.core'")
        // y de paso queda fuera de elearning
        assertThat(violations.map { it.rule }).containsExactlyInAnyOrder(Rule.WASICHAI_PACKAGE, Rule.PACKAGE_DAG)
    }

    @Test
    fun `jpa, hibernate, spring data repositories and blocking jdbc are caught, imported or fully qualified`() {
        val path = "src/main/kotlin/elearning/course/Persistence.kt"
        val violations =
            scan(
                path,
                """
                package elearning.course

                import jakarta.persistence.Entity
                import org.hibernate.Session
                import org.springframework.data.jpa.repository.JpaRepository
                import org.springframework.data.repository.CrudRepository
                import org.springframework.data.r2dbc.repository.R2dbcRepository
                import java.sql.Connection
                import org.springframework.jdbc.core.JdbcTemplate

                class Persistence(val ds: javax.sql.DataSource) {
                    fun rows(c: java.sql.Connection) = c
                }
                """,
            )

        assertThat(violations).allMatch { it.rule == Rule.BLOCKING_PERSISTENCE }
        assertThat(violations.map { it.line }).containsExactly(3, 4, 5, 6, 7, 8, 9, 11, 12)
        assertThat(violations.last().toString()).startsWith("$path:12: usa java.sql.Connection")
    }

    @Test
    fun `internal classes of wasichai are caught`() {
        val path = "src/main/kotlin/elearning/course/Internals.kt"
        val violations =
            scan(
                path,
                """
                package elearning.course

                import wasichai.core.data.RecordStore
                import wasichai.core.data.PhysicalTableRecordStore
                import wasichai.core.metadata.ObjectSchemaManager

                class Internals(private val audit: wasichai.core.audit.AuditService)
                """,
            )

        assertThat(violations).allMatch { it.rule == Rule.WASICHAI_INTERNALS }
        assertThat(violations.map { it.line to it.detail })
            .containsExactly(3 to "usa RecordStore", 4 to "usa PhysicalTableRecordStore", 5 to "usa ObjectSchemaManager", 7 to "usa AuditService")
    }

    @Test
    fun `every role name literal is caught, in kotlin strings and in sql`() {
        val path = "src/main/kotlin/elearning/course/Roles.kt"
        val violations =
            scan(
                path,
                """
                package elearning.course

                fun a(role: String) = role == "ESTUDIANTE"
                fun b(role: String) = role == "DOCENTE"
                fun c(roles: Set<String>) = "CREADOR" in roles
                fun d(role: String) = role.equals("revisor", ignoreCase = true)
                fun e() = hasRole("ADMIN")
                fun f() = hasAuthority("ROLE_ADMIN")
                const val SQL = "SELECT 1 FROM user_roles WHERE name = 'DOCENTE'"
                """,
            )

        assertThat(violations).allMatch { it.rule == Rule.ROLE_NAME_LITERAL }
        assertThat(violations.map { it.line }).containsExactly(3, 4, 5, 6, 7, 8, 9)
        assertThat(violations.first().toString()).startsWith("$path:3: literal de rol \"ESTUDIANTE\"")
    }

    @Test
    fun `workload importing spring, wasichai or r2dbc is caught`() {
        val path = "src/main/kotlin/elearning/workload/Impure.kt"
        val violations =
            scan(
                path,
                """
                package elearning.workload

                import org.springframework.stereotype.Component
                import wasichai.core.identity.CurrentUser
                import io.r2dbc.spi.ConnectionFactory

                @Component
                class Impure(val factory: ConnectionFactory)
                """,
            )

        assertThat(violations).allMatch { it.rule == Rule.PURE_WORKLOAD }
        assertThat(violations.map { it.line }).containsExactly(3, 4, 5)
        assertThat(violations.first().toString()).startsWith("$path:3: elearning.workload usa org.springframework.stereotype.Component")
    }

    @Test
    fun `the same imports outside workload are not a purity problem`() {
        val violations =
            scan(
                "src/main/kotlin/elearning/course/CourseService.kt",
                """
                package elearning.course

                import org.springframework.stereotype.Service
                import wasichai.core.identity.CurrentUser
                import org.springframework.r2dbc.core.DatabaseClient

                @Service
                class CourseService(val db: DatabaseClient, val user: CurrentUser)
                """,
            )

        assertThat(violations).isEmpty()
    }

    @Test
    fun `concrete ai, storage and videoconference providers are caught in the core, imported, qualified or by their host`() {
        val path = "src/main/kotlin/elearning/learning/Providers.kt"
        val violations =
            scan(
                path,
                """
                package elearning.learning

                import com.openai.client.OpenAIClient
                import com.anthropic.client.AnthropicClient
                import software.amazon.awssdk.services.s3.S3Client
                import io.minio.MinioClient
                import com.google.cloud.storage.Storage
                import us.zoom.sdk.ZoomSDK
                import com.google.apps.meet.v2.SpacesServiceClient
                import org.springframework.ai.openai.OpenAiChatModel

                class Providers(val bucket: com.amazonaws.services.s3.AmazonS3) {
                    val endpoint = "https://api.openai.com/v1/chat/completions"
                    val zoom = "https://api.zoom.us/v2/users/me/meetings"
                    val github = "https://api.github.com/repos"
                }
                """,
            )

        assertThat(violations).allMatch { it.rule == Rule.CONCRETE_PROVIDER }
        assertThat(violations.map { it.line }).containsExactly(3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14, 15)
        assertThat(violations.first().toString()).startsWith("$path:3: usa com.openai.client.OpenAIClient")
    }

    // el adaptador de cada proveedor es el único sitio donde vive su SDK (docs/architecture/overview.md §2.3)
    @Test
    fun `a provider sdk inside its adapter package, or named in a comment, is not a violation`() {
        val adapters =
            listOf("elearning.ai", "elearning.media.storage", "elearning.course.meeting", "elearning.importer.github").flatMap { pkg ->
                scan(
                    "src/main/kotlin/${pkg.replace('.', '/')}/Adapter.kt",
                    "package $pkg\n\nimport com.anthropic.client.AnthropicClient\nimport us.zoom.sdk.ZoomSDK\n\nval host = \"api.anthropic.com\"\n",
                )
            }
        val comment =
            scan("src/main/kotlin/elearning/course/Port.kt", "package elearning.course\n\n// nunca com.openai.client ni api.openai.com aquí\nclass Port")

        assertThat(adapters.filter { it.rule == Rule.CONCRETE_PROVIDER } + comment).isEmpty()
    }

    // ADR-0018 §7: la fuente de un documento no es de confianza; el único proceso externo es el generador, en su adaptador
    @Test
    fun `an external process outside the renderer adapter is caught`() {
        val path = "src/main/kotlin/elearning/authoring/Shell.kt"
        val violations =
            scan(
                path,
                """
                package elearning.authoring

                class Shell {
                    fun a() = ProcessBuilder("sh", "-c", "ls").start()
                    fun b() = Runtime.getRuntime().exec(arrayOf("ls"))
                    fun c() = ProcessHandle.current()
                }
                """,
            )
        val adapter =
            scan(
                "src/main/kotlin/elearning/media/asciidoctor/Adapter.kt",
                "package elearning.media.asciidoctor\n\nval p = ProcessBuilder(\"asciidoctor\")\n",
            )
        val comment = scan("src/main/kotlin/elearning/media/Port.kt", "package elearning.media\n\n// nunca un ProcessBuilder aquí\nclass Port")

        assertThat(violations).allMatch { it.rule == Rule.EXTERNAL_PROCESS }
        assertThat(violations.map { it.line }).containsExactly(4, 5, 6)
        assertThat(adapter + comment).isEmpty()
    }

    @Test
    fun `workload depending on another capability is caught`() {
        val path = "src/main/kotlin/elearning/workload/Leak.kt"
        val violations = scan(path, "package elearning.workload\n\nimport elearning.common.Problem\n\nclass Leak")

        assertSingle(violations, Rule.PACKAGE_DAG, path, 3)
        assertThat(violations.single().detail).isEqualTo("elearning.workload no puede usar elearning.common")
    }

    @Test
    fun `depending on catalog is caught for every capability`() {
        val offenders =
            ArchitectureTest.ALLOWED_DEPENDENCIES.keys.filter { it != "catalog" }.map { capability ->
                val path = "src/main/kotlin/elearning/$capability/UsesCatalog.kt"
                scan(path, "package elearning.$capability\n\nimport elearning.catalog.CatalogQuery\n\nclass UsesCatalog")
                    .also { assertSingle(it, Rule.PACKAGE_DAG, path, 3) }
            }

        assertThat(offenders).hasSize(ArchitectureTest.ALLOWED_DEPENDENCIES.size - 1)
    }

    @Test
    fun `an arrow against the diagram is caught, even fully qualified`() {
        val path = "src/main/kotlin/elearning/course/Upwards.kt"
        val violations = scan(path, "package elearning.course\n\nfun seats(e: elearning.enrollment.Enrollment) = e")

        assertSingle(violations, Rule.PACKAGE_DAG, path, 3)
    }

    @Test
    fun `a capability missing from the table is caught at its package line`() {
        val path = "src/main/kotlin/elearning/gradebook/Grades.kt"
        val violations = scan(path, "// libro de calificaciones\npackage elearning.gradebook\n\nclass Grades")

        assertSingle(violations, Rule.PACKAGE_DAG, path, 2)
        assertThat(violations.single().detail).contains("'gradebook' no está en la tabla")
    }

    @Test
    fun `arrows of the diagram and the application itself are allowed`() {
        val catalog =
            scan(
                "src/main/kotlin/elearning/catalog/CatalogQuery.kt",
                "package elearning.catalog\n\nimport elearning.common.Problem\nimport elearning.course.Course\nimport elearning.workload.Workload\n",
            )
        val application =
            scan(
                "src/main/kotlin/elearning/ElearningApplication.kt",
                "package elearning\n\nimport elearning.catalog.CatalogQuery\nimport elearning.workload.Workload\n",
            )

        assertThat(catalog + application).isEmpty()
    }

    // los comentarios pueden nombrar lo prohibido (los de este repositorio lo hacen); las cadenas que solo se parecen a
    // un rol, o que llevan "//" o "/*", no confunden al escáner
    @Test
    fun `comments and look-alike strings are not violations`() {
        val violations =
            scan(
                "src/main/kotlin/elearning/course/Comments.kt",
                """
                package elearning.course

                // nunca RecordStore, ni java.sql.Connection, ni import jakarta.persistence.Entity, ni "ADMIN"
                /* ni "DOCENTE" /* anidado: org.hibernate.Session */ ni elearning.catalog.CatalogQuery */
                /**
                 * KDoc: package wasichai.core, AuditService
                 */
                const val URL = "https://example.org/*no-es-comentario"
                const val NOTE = "administrador // DOCENTES ${'$'}{URL.length} // ADMINISTRADOR"
                val quote = '"'
                fun `nombre con ' y "`() = "ok"
                """,
            )

        assertThat(violations).isEmpty()
    }

    @Test
    fun `code after a string with a comment marker or a template is still scanned`() {
        val path = "src/main/kotlin/elearning/course/AfterStrings.kt"
        val violations =
            scan(
                path,
                """
                package elearning.course

                val a = "http://x/*" + "${'$'}{listOf("a", "b").joinToString("//")}"; val b = "ADMIN"
                val c = '"'; val d = "REVISOR"
                """,
            )

        assertThat(violations.map { it.rule to it.line }).containsExactly(Rule.ROLE_NAME_LITERAL to 3, Rule.ROLE_NAME_LITERAL to 4)
    }

    // la ruta del disco es la misma que usa ArchitectureTest: readSources + scan
    @Test
    fun `sources read from disk report their path and line`(
        @TempDir dir: File,
    ) {
        val file = dir.resolve("elearning/workload/Bad.kt").apply { parentFile.mkdirs() }
        file.writeText("package elearning.workload\n\nimport org.springframework.jdbc.core.JdbcTemplate\n")

        val violations = scanner.scan(readSources(dir))

        assertThat(violations.map { it.toString() }).containsExactlyInAnyOrder(
            "${file.path}:3: usa org.springframework.jdbc.core.JdbcTemplate [BLOCKING_PERSISTENCE: ${Rule.BLOCKING_PERSISTENCE.description}]",
            "${file.path}:3: elearning.workload usa org.springframework.jdbc.core.JdbcTemplate [PURE_WORKLOAD: ${Rule.PURE_WORKLOAD.description}]",
        )
    }
}
