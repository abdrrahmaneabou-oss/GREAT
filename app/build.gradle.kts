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
        versionCode = 1
        versionName = "0.1.0-build1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
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
    testImplementation("junit:junit:4.13.2")
}
