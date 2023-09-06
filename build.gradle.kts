import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.gradle.versions.plugin)
}

group = "org.equeim"
version = "0.1"

val javaVersion = JavaVersion.VERSION_1_8
tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = javaVersion.toString()
}
tasks.withType<JavaCompile> {
    options.release.set(javaVersion.majorVersion.toInt())
    sourceCompatibility = javaVersion.toString()
    targetCompatibility = javaVersion.toString()
}

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
        val versionUppercase = version.uppercase(Locale.ROOT)
        val hasStableKeyword = stableKeywords.any(versionUppercase::contains)
        val isStable = hasStableKeyword || regex.matches(version)
        return isStable.not()
    }
}
