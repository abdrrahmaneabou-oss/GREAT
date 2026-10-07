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
        versionCode = 2
        versionName = "0.1.1-build1-awg"
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
    implementation(project(":awgTunnel"))
    testImplementation("junit:junit:4.13.2")
}
