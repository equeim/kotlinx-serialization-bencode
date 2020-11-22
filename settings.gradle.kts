pluginManagement {
    val kotlin = "1.4.10"
    plugins {
        kotlin("multiplatform") version(kotlin)
        kotlin("plugin.serialization") version(kotlin)
    }
}

rootProject.name = "kotlinx-serialization-bencode"
