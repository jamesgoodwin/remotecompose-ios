import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The writer's `.rc` documents, carried as Base64 so every target can read them.
 *
 * They are a module of their own because two things need the same bytes and neither can reach the
 * other: the library's own test suite, and the demo app that shows them. Putting them in the demo
 * would make the library's tests depend on the demo, which depends on the library.
 *
 * Nothing here is published. It has no dependencies — not even Compose — because a fixture is a
 * byte array.
 */
plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

android {
    namespace = "com.example.remotecompose.fixtures"
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
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()
}
