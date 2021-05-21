import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "org.equeim"
version = "0.1"

repositories {
    mavenCentral()
}

tasks.named<KotlinCompile>("compileKotlin") {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.2.1")
}
