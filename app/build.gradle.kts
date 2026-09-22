import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Signing credentials live in local/, which is gitignored, so the keystore and
// its password never reach the repository. A checkout without them still
// builds release — it just comes out unsigned — so a machine or a CI runner
// that has no key is not blocked.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("local/keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.crylo.ludo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.crylo.ludo"
        minSdk = 31
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"
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
            // Null when local/keystore.properties is absent, which leaves the
            // build unsigned rather than failing.
            signingConfig = signingConfigs.findByName("release")

            // AGP otherwise stamps the git commit into the APK. It is not
            // worth the bytes here, and a release artefact does not need to
            // carry the working tree's VCS state.
            vcsInfo { include = false }

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

    // Play's dependency metadata is a signed blob appended to the APK. Nothing
    // here is published through Play, and on an APK this small it is a
    // measurable fraction of the download.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        dex { useLegacyPackaging = true }
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
