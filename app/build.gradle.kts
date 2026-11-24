plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.home.alarmclock"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.home.alarmclock"
        minSdk = 28
        targetSdk = 34
        // GitHub Actions run number => every build is an upgrade of the previous one
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../keystore/release.p12")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "alarmclock"
            keyAlias = "alarmclock"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: "alarmclock"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
