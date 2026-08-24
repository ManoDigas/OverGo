plugins {
    id("com.android.application")
}

android {
    namespace = "com.manodigas.overgo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.manodigas.overgo"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
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
