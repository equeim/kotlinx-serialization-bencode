import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
}
tasks.withType<JavaCompile> {
    options.release.set(javaVersion.majorVersion.toInt())
    sourceCompatibility = javaVersion.toString()
    targetCompatibility = javaVersion.toString()
}

dependencies {
    api(libs.serialization.core)
    api(libs.coroutines.core)
    implementation(libs.androidx.collection)
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    gradleReleaseChannel = "current"
    val channelProvider = VersionChannelProvider()
    rejectVersionIf {
        val currentChannel = channelProvider.getChannel(currentVersion)
        val candidateChannel = channelProvider.getChannel(candidate.version)
        candidateChannel < currentChannel
    }
}

class VersionChannelProvider {
    enum class Channel(private val keywords: List<String>) {
        Alpha("alpha"),
        Beta("beta"),
        RC("rc"),
        Stable("release", "final", "ga");

        constructor(vararg keywords: String) : this(keywords.asList())

        fun matches(versionLowercase: String): Boolean = keywords.any { versionLowercase.contains(it) }
    }
    private val channels = Channel.values()
    private val stableVersionRegex = "^[0-9.]+$".toRegex()

    fun getChannel(version: String): Channel {
        val versionLowercase = version.lowercase(Locale.ROOT)
        if (versionLowercase.matches(stableVersionRegex)) {
            return Channel.Stable
        }
        val channelFromKeyword = channels.find { it.matches(versionLowercase) }
        if (channelFromKeyword != null) return channelFromKeyword
        throw RuntimeException("Failed to determine channel for version '$version'")
    }
}
