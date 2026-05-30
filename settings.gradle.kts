plugins {
    // Auto-provision the JDK 21 toolchain (jvmToolchain(21)) on machines/CI/sandboxes
    // that don't have it installed. Without a resolver, `./gradlew check` fails with
    // "Cannot find a Java installation matching languageVersion=21".
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Intellij-Defold"
