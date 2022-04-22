import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.gradle.versions.plugin)
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

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    val checker = DependencyVersionChecker()
    rejectVersionIf {
        checker.isNonStable(candidate.version)
    }
}

class DependencyVersionChecker {
    private val stableKeywords = listOf("RELEASE", "FINAL", "GA")
    private val regex = "^[0-9,.v-]+(-r)?$".toRegex()

    fun isNonStable(version: String): Boolean {
        val versionUppercase = version.toUpperCase(Locale.ROOT)
        val hasStableKeyword = stableKeywords.any(versionUppercase::contains)
        val isStable = hasStableKeyword || regex.matches(version)
        return isStable.not()
    }
}
