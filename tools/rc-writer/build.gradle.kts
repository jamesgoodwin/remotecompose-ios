plugins {
    kotlin("jvm")
    application
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("androidx.compose.remote:remote-creation:1.0.0-alpha18")
    implementation("androidx.compose.remote:remote-creation-core:1.0.0-alpha18")
    implementation("androidx.compose.remote:remote-core:1.0.0-alpha18")
}

application {
    mainClass.set("MainKt")
}

// No JDK 17/21 toolchain is installed in this environment (only JDK 24), and Kotlin 2.0.21's
// compiler caps out at bytecode target 22 — align javac's release with that instead of letting
// each task pick a different default off the running JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
