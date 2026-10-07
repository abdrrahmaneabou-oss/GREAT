plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
}

// GREAT keeps the upstream AmneziaWG submodule pinned and clean in Git.
// At build time we copy only our tiny packet-device adapter beside the upstream
// Android wrapper, so the cryptographic/protocol engine remains upstream code.
val prepareGreatAwgBridge = tasks.register<Copy>("prepareGreatAwgBridge") {
    from(layout.projectDirectory.dir("native/awg-bridge"))
    into(layout.projectDirectory.dir("third_party/amneziawg-android/tunnel/tools/libwg-go"))
    include("great_bridge.go", "great_bridge_jni.c")
}

project(":awgTunnel") {
    tasks.configureEach {
        if (name == "preBuild") dependsOn(prepareGreatAwgBridge)
    }

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
