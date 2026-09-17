plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ayuemin.umniklab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ayuemin.umniklab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-lab"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
