pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "remotecompose-ios"

include(":tools:rc-writer")
include(":fixtures")
include(":demo")
include(":androidApp")
