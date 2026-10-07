pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GREAT"
include(":app")
include(":awgTunnel")
project(":awgTunnel").projectDir = file("third_party/amneziawg-android/tunnel")
