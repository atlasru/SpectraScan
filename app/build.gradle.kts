plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.atlas.spectrascan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.atlas.spectrascan"
        minSdk = 29
        targetSdk = 36
        versionCode = 35
        versionName = "0.16.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("spectrascan-debug.keystore")
            storePassword = "spectrascan"
            keyAlias = "spectrascan"
            keyPassword = "spectrascan"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("org.opencv:opencv:4.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
