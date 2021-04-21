plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "org.equeim"
version = "0.1"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        compilations.configureEach {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    sourceSets.named("jvmMain") {
        languageSettings.useExperimentalAnnotation("kotlinx.serialization.ExperimentalSerializationApi")
        dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.1.0")
        }
    }
}
