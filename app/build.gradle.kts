plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.plx.lx04cast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.plx.lx04cast"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.1.10"
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Personal sideload build: keep JNI callback names, then shrink what is left.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
