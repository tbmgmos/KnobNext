plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes the release keystore through env vars; without them release falls back to the debug key.
val releaseKeystore: String? = System.getenv("KNOB_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.knobnext"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.knobnext"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 3
        versionName = providers.gradleProperty("versionName").orNull ?: "3.0.0-local"
    }

    // github: checks GitHub Releases for updates (needs INTERNET). rustore: the store delivers updates,
    // so that build has no network permission at all.
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store" }
        create("rustore") { dimension = "store" }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KNOB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KNOB_KEY_ALIAS")
                keyPassword = System.getenv("KNOB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Gemini Nano on phones that have it (AICore); nothing is bundled.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")
}
