import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The demo app's shared screen: the list of documents, the page that shows one, and the platform
 * back gesture and system-bar handling behind them.
 *
 * Separate from the library because it is not part of it. An app that pulls in RemoteCompose to
 * render its own documents has no use for a list of ours, and until this module existed it got
 * one anyway — 23 fixtures and the screen that shows them, compiled into the iOS framework and
 * exported to Swift.
 *
 * `RemoteComposeDemoShared` is this module's own framework, which `iosApp` embeds. It contains the
 * library too, because Kotlin/Native links the whole graph into one framework; that is the demo
 * app's business and not the published package's.
 */
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
}

android {
    namespace = "io.github.jamesgoodwin.remotecompose.demo"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "RemoteComposeDemoShared"
            isStatic = false
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":"))
                implementation(project(":fixtures"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
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

        val androidMain by getting {
            dependsOn(commonMain)
            dependencies {
                // For `PredictiveBackHandler`, so the demo's back is the system's own rather than
                // a control drawn over the page. Compose Multiplatform only gained a common one in
                // 1.8; this project is on 1.7, so it is an expect/actual.
                implementation("androidx.activity:activity-compose:1.9.2")
            }
        }
    }
}
