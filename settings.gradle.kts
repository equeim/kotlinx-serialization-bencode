pluginManagement {
    val kotlin = "1.5.0"
    plugins {
        kotlin("multiplatform") version(kotlin)
        kotlin("plugin.serialization") version(kotlin)
    }
}

rootProject.name = "kotlinx-serialization-bencode"
