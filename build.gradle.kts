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
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    sourceSets {
        val jvmMain by getting {
            languageSettings.useExperimentalAnnotation("kotlinx.serialization.ExperimentalSerializationApi")
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.1.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
            }
        }
    }
}