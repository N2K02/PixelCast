plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pixelcast.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pixelcast.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
    }

    signingConfigs {
        // Fixed key committed to the repo so every CI build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            // R8 removes unused code (most of Compose) and resources: ~32 MB -> a few MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same fixed key as debug, so it installs over earlier builds.
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

dependencies {
    // Material 3 Expressive (MaterialExpressiveTheme, expressive shapes/motion/components)
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.activity:activity-compose:1.11.0")
}
