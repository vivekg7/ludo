plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vivek.ludo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vivek.ludo"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    androidResources {
        // The game ships no translations, so drop every other locale's
        // resources rather than carrying them into the APK.
        localeFilters += listOf("en")
    }

    // Everything is drawn on a Canvas, so none of these are needed.
    buildFeatures {
        buildConfig = false
        resValues = false
        shaders = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "kotlin/**",
            "DebugProbesKt.bin",
        )
    }
}

dependencies {
    // Deliberately no AndroidX / Compose / Material: the whole UI is one
    // custom View, so the only runtime dependency is the Kotlin stdlib.
    testImplementation(libs.junit)
}
