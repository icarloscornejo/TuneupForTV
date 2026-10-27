plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The one place the version is written. Follows Semantic Versioning (MAJOR.MINOR.PATCH); the code is derived from it.
val appVersion = "1.0.0"
val (versionMajor, versionMinor, versionPatch) = appVersion.split(".").map(String::toInt)

android {
    namespace = "com.prototype.tuneupfortv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.prototype.tuneupfortv"
        minSdk = 26
        targetSdk = 34
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
        versionName = appVersion
    }

    // Only the two languages the app ships: the libraries bring dozens of translations nobody sees.
    androidResources {
        localeFilters += listOf("en", "es")
    }

    buildTypes {
        release {
            // Never ship a debuggable APK: `run-as` would expose the ADB private key.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded from GitHub, not published to Play: the debug key is enough.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dadb)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
