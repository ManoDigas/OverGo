plugins {
    id("com.android.application")
}

val overgoAiUrl = providers.gradleProperty("OVERGO_AI_URL").orElse("").get()

android {
    namespace = "com.manodigas.overgo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.manodigas.overgo"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-dev"
        buildConfigField("String", "OVERGO_AI_URL", "\"$overgoAiUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
