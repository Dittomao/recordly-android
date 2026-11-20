plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.recordly.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.recordly.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        // Package metadata supplies the version and debug flag in this Kotlin-only app.
        buildConfig = false
    }

    providers.gradleProperty("recordlyDebugKeystore").orNull?.let { keyPath ->
        signingConfigs.getByName("debug").storeFile = file(keyPath)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // The renderer ships ~30 MB of JS/WASM; leave it uncompressed so the
    // server can stream it and the installer stays fast.
    androidResources {
        noCompress += listOf("wasm", "mp4", "webm")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
