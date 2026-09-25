plugins {
    id("com.android.application")
}

android {
    namespace = "com.edwardresearchlabs.aimotion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.edwardresearchlabs.aimotion"
        minSdk = 29
        targetSdk = 37
        versionCode = 10
        versionName = "0.6.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}
