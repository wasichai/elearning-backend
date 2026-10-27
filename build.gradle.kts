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
    testImplementation(libs.wasichai.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
}

// unitarias y de arquitectura: sin base de datos
tasks.test { useJUnitPlatform { excludeTags("integration") } }

// integración: PostgreSQL 18 en Testcontainers (nunca el 5432 de la máquina) y el modelo aplicado con model/apply.py
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

tasks.bootJar { archiveFileName.set("elearning-backend.jar") }
