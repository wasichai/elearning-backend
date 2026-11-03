import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.ktlint)
}

group = "elearning"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}

ktlint { version.set(libs.versions.ktlintTool) }

dependencies {
    implementation(platform(libs.wasichai.bom))
    implementation(libs.wasichai.starter)
    implementation(libs.wasichai.starter.files)
    // wasichai.files.store=s3 (RF-CON-02): ningún código propio lo usa, solo el almacén de Wasichai en ejecución
    runtimeOnly(libs.aws.sdk.s3)
    implementation(libs.wasichai.starter.notifications)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.springdoc.webflux.api)
    // el HTML que genera Asciidoctor se sanea con lista blanca antes de guardarse (ADR-0022)
    implementation(libs.jsoup)
    // canalización con IA (ADR-0033): cada proveedor arranca solo con su clave (elearning.ai.AiProviderGate). sin
    // embabel-agent-webmvc, -a2a ni -jdbc, que traen Spring MVC
    implementation(libs.embabel.agent.starter)
    implementation(libs.embabel.agent.starter.anthropic)
    implementation(libs.embabel.agent.starter.openai)
    testImplementation(libs.wasichai.test)
    // S3ResourceFilesApiTest crea el bucket y lee el objeto directamente en MinIO
    testImplementation(libs.aws.sdk.s3)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
}

// unitarias y de arquitectura: sin base de datos
tasks.test { useJUnitPlatform { excludeTags("integration") } }

// integración: PostgreSQL 18 (y MinIO para el almacén S3) en Testcontainers, nunca el 5432 de la máquina, y el modelo
// aplicado con model/apply.py
val integrationTest by tasks.registering(Test::class) {
    description = "Pruebas de integración contra PostgreSQL 18 en Testcontainers."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

// reactivo de verdad (CLAUDE.md, regla 2): ninguna dependencia, Embabel incluido, puede colar Spring MVC ni Tomcat en la
// aplicación; con los dos en el classpath, Spring Boot arrancaría un servidor de servlets en lugar de WebFlux
val noServletStack by tasks.registering {
    description = "Falla si Spring MVC o Tomcat llegan a runtimeClasspath."
    group = "verification"
    val artifacts =
        configurations.runtimeClasspath
            .get()
            .incoming.artifacts.resolvedArtifacts
    doLast {
        val forbidden =
            artifacts
                .get()
                .map { it.id.componentIdentifier }
                .filterIsInstance<ModuleComponentIdentifier>()
                .map { "${it.group}:${it.module}" }
                // tomcat-embed-core es el servidor; tomcat-embed-el, el lenguaje de expresiones de la validación, no lo es
                .filter { it.endsWith(":spring-webmvc") || it.endsWith(":tomcat-embed-core") }
                .sorted()
        check(forbidden.isEmpty()) { "runtimeClasspath trae una pila de servlets: $forbidden" }
    }
}

tasks.check { dependsOn(noServletStack) }

tasks.bootJar { archiveFileName.set("elearning-backend.jar") }

// desarrollo local: bootRun toma .env (ignorado por git) como variables de entorno reales, que es como las lee Wasichai.
// LMS_ENV_FILE nombra otro archivo: la demo poblada (model/demo/demo.sh) usa el suyo y nunca lee el .env de desarrollo
tasks.bootRun {
    val env = file(System.getenv("LMS_ENV_FILE")?.takeIf { it.isNotBlank() } ?: ".env")
    if (env.exists()) {
        env
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .map { it.split("=", limit = 2) }
            .filter { (_, value) -> value.isNotBlank() }
            .forEach { (key, value) -> environment(key.trim(), value.trim()) }
    }
}
