plugins {
    id("com.android.application")
}

android {
    namespace = "com.great.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.great.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.2-build2b-fox-core"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":awgTunnel"))
    testImplementation("junit:junit:4.13.2")
}
