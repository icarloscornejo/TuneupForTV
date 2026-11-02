import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing key: keystore.properties at the root of the repo (storeFile, storePassword, keyAlias, keyPassword),
// never committed. It points at a backed-up copy of the key that signed v1.0.0, so every release is signed by the same
// key and the in-app update installs over the old one. Without it a release build is signed with this machine's debug key.
val keystoreProperties: Properties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// The one place the version is written. Follows Semantic Versioning (MAJOR.MINOR.PATCH); the code is derived from it.
val appVersion = "1.1.0"
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

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Never ship a debuggable APK: `run-as` would expose the ADB private key.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded from GitHub, not published to Play: the key of v1.0.0 (the debug key of the release machine) is enough,
            // as long as every release is signed with that same one.
            signingConfig = signingConfigs.getByName(if (keystoreProperties.isNotEmpty()) "release" else "debug")
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
