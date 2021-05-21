import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version("1.5.0")
}

group = "org.equeim"
version = "0.1"

tasks.named<KotlinCompile>("compileKotlin") {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.2.1")
}
