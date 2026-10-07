plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
}

project(":awgTunnel") {
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            compileSdk = 36
            buildToolsVersion = "36.0.0"
            ndkVersion = "26.1.10909125"
            defaultConfig {
                minSdk = 29
                ndk {
                    abiFilters += listOf("arm64-v8a")
                }
            }
        }
    }
}
