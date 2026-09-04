import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.android.library") version "8.5.2"
}

group = "com.example.remotecompose"
version = "0.1.0"

android {
    namespace = "com.example.remotecompose"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    // No JDK 17/21 toolchain is installed in this environment (only JDK 24), and Kotlin 2.0.21's
    // compiler caps out at bytecode target 22 — align javac's release with Kotlin's target
    // instead of letting each default off the running JDK (see the same fix in
    // tools/rc-writer/build.gradle.kts).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "RemoteComposeShared"
            isStatic = true
        }
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.components.resources)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }

        val desktopMain by getting {
            dependsOn(commonMain)
            dependencies {
                // KotlinCompilation.runtimeDependencyFiles (used by the manual runDesktopDemo
                // JavaExec task below) doesn't pick up Skiko's OS-specific native runtime jar the
                // way compose.desktop.application{}'s own run task does, so it's declared
                // explicitly here. Machine-specific (macOS/arm64) — fine for this dev-only demo
                // task, not something a real multi-OS target list should hardcode.
                runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.9.4.2")
            }
        }

        val androidMain by getting {
            dependsOn(commonMain)
        }
    }
}

// Runs DemoMain.kt (src/desktopMain) against the `desktop` JVM target's own compiled output +
// runtime classpath. Kept as a manual JavaExec rather than the `application` plugin, since that
// plugin assumes a single non-multiplatform `main` source set and doesn't compose cleanly with
// Kotlin Multiplatform's per-target compilations.
tasks.register<JavaExec>("runDesktopDemo") {
    dependsOn("desktopMainClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles!!
    mainClass.set("com.example.remotecompose.demo.DemoMainKt")
}
