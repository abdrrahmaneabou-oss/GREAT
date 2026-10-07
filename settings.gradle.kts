import com.android.build.api.dsl.SettingsExtension

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

plugins {
    id("com.android.settings") version "8.13.0"
}

configure<SettingsExtension> {
    buildToolsVersion = "36.0.0"
    compileSdk = 36
    minSdk = 29
    ndkVersion = "26.1.10909125"
}

rootProject.name = "GREAT"
include(":app")
include(":awgTunnel")
project(":awgTunnel").projectDir = file("third_party/amneziawg-android/tunnel")
