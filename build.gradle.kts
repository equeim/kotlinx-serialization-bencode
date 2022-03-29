import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.equeim"
version = "0.1"

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
    }
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(libs.versions.java.version.get()))

repositories {
    mavenCentral()
}

dependencies {
    api(libs.serialization.core)
    api(libs.coroutines.core)
}
