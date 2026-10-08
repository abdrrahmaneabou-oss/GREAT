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
        versionCode = 8
        versionName = "0.5.0-trigger-engine"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        aidl = true
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
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
