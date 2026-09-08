import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.android.library") version "8.5.2"
}

group = "io.github.jamesgoodwin"
version = "0.1.0"

android {
    namespace = "io.github.jamesgoodwin.remotecompose"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // One XCFramework over the three Apple targets, which is what a Swift package's binary
    // target consumes. Dynamic rather than static: SwiftPM embeds and signs a dynamic framework
    // from a binary target on its own, where a static one leaves the consumer to supply the
    // linker flags Skia and the C++ runtime need.
    val xcframework = XCFramework("RemoteComposeShared")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "RemoteComposeShared"
            isStatic = false
            xcframework.add(this)
        }
    }

    // A release test binary as well as the debug one the test task builds. Kotlin/Native debug
    // is unoptimised, and on the frame-building benchmark it is ten times its own release — so a
    // measurement taken from the debug binary says nothing about what this renderer does on a
    // device. Links as `linkReleaseTestIosSimulatorArm64`; see docs/PERFORMANCE.md.
    iosSimulatorArm64 {
        binaries.test(listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE))
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        // Puts commonTest on the instrumented-test compilation, so `connectedAndroidTest` runs
        // the same suite the JVM and Kotlin/Native targets run — on ART, on a device. Without
        // this the Android target is the one platform that ships the code and never runs it.
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree.test)
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":fixtures"))
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

        // Drives the real composable through synthetic gestures, which is the only way to catch a
        // press that never reaches the document: the parser tests scroll it by calling it
        // directly, so they stayed green while nothing on a device could drag it.
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
                implementation(compose.desktop.currentOs)
            }
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

        // Just the runner: the tests themselves are commonTest, through kotlin("test").
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test.ext:junit:1.2.1")
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
// Compares device screenshots against a headless render of the same document; see HarnessMain.
// `PayloadDriftTest` and `GoldenOpcodeTest` read the writer's fixtures, which are not on the test
// classpath: without this the desktop suite stays up-to-date across a regenerated one and the
// drift check passes against bytes nobody compared.
tasks.named<Test>("desktopTest") {
    inputs.files(fileTree("tools/rc-writer") { include("*.rc") })
        .withPropertyName("fixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<JavaExec>("pixelHarness") {
    dependsOn("desktopMainClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles!!
    mainClass.set("io.github.jamesgoodwin.remotecompose.harness.HarnessMainKt")
}

tasks.register<JavaExec>("runDesktopDemo") {
    dependsOn("desktopMainClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles!!
    mainClass.set("io.github.jamesgoodwin.remotecompose.harness.RenderMainKt")
}
