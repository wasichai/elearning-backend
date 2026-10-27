rootProject.name = "elearning-backend"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Wasichai solo se publica en GitHub Packages, que pide un token con read:packages incluso para leer (ADR-0002).
        // El token nunca va a un archivo: GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token) ./gradlew …
        maven {
            name = "wasichai"
            url = uri("https://maven.pkg.github.com/wasichai/wasichai")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("wasichai") }
        }
        // respaldo para trabajar contra un checkout de wasichai: ./gradlew publishToMavenLocal en ese checkout
        mavenLocal { content { includeGroup("wasichai") } }
    }
}
