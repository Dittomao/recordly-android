plugins {
    id("com.android.application") version "8.7.3"
}

android {
    namespace = "app.recordly.systemuirepro"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.recordly.systemuirepro"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    providers.gradleProperty("probeDebugKeystore").orNull?.let { path ->
        signingConfigs.getByName("debug").storeFile = file(path)
    }
}
