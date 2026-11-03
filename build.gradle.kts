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
    // doble HTTP de los proveedores de IA (elearning.ai.ProviderDouble, ADR-0033 §11)
    testImplementation(libs.wiremock.standalone)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
}

// unitarias y de arquitectura: sin base de datos
tasks.test { useJUnitPlatform { excludeTags("integration", "ai-eval") } }

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
    // la comparación de modelos contra los proveedores reales es aiEval, nunca esta
    useJUnitPlatform {
        includeTags("integration")
        excludeTags("ai-eval")
    }
    shouldRunAfter(tasks.test)
}

// comparación reproducible de modelos (RF-IA-09, docs/ai §9): fuera de build, check e integrationTest, porque llama a los
// proveedores reales, necesita sus claves (ANTHROPIC_API_KEY, OPENAI_API_KEY) y cuesta dinero. Solo a mano; necesita Docker
// como integrationTest. Deja run.json, cada caso y report.md en build/ai-eval/<inicio>/. En CI corre su prueba de humo contra
// el doble HTTP (ModelEvaluationSmokeTest, dentro de integrationTest)
val aiEval by tasks.registering(Test::class) {
    description = "Compara los modelos configurados sobre los conjuntos fijos de docs/ai/evaluations (proveedores reales)."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("ai-eval") }
    systemProperty("lms.ai.eval.sets", file("docs/ai/evaluations").absolutePath)
    systemProperty("lms.ai.eval.out", layout.buildDirectory.dir("ai-eval").get().asFile.absolutePath)
    // cada ejecución es una medición nueva: nunca al día
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
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

// el render de vídeo es opcional (ADR-0039 §3-4): Remotion es solo una dependencia npm de render-worker/, nunca del backend.
// falla si algo con «remotion» en su grupo o nombre llega a los classpath de la aplicación o de las pruebas
val noRemotion by tasks.registering {
    description = "Falla si Remotion llega al classpath del backend."
    group = "verification"
    val artifacts =
        listOf(configurations.runtimeClasspath, configurations.testRuntimeClasspath).map { configuration ->
            configuration
                .get()
                .incoming.artifacts.resolvedArtifacts
        }
    doLast {
        val forbidden =
            artifacts
                .flatMap { it.get() }
                .map { it.id.componentIdentifier }
                .filterIsInstance<ModuleComponentIdentifier>()
                .map { "${it.group}:${it.module}" }
                .filter { "remotion" in it.lowercase() }
                .distinct()
                .sorted()
        check(forbidden.isEmpty()) { "el classpath del backend trae Remotion, que solo puede ser del trabajador de render: $forbidden" }
    }
}

tasks.check { dependsOn(noServletStack, noRemotion) }

// el esquema del guion de vídeo es uno solo, el del trabajador (ADR-0040 §2): ScriptSchema lo lee del classpath
tasks.processResources {
    from("render-worker/schema") {
        include("guion.schema.json")
        into("render")
    }
}

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
