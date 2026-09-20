// Umnik v1.19.6 — multimodal chat image output and zoomable viewer
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = System.getenv("UMNIK_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("UMNIK_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("UMNIK_KEY_ALIAS")
val releaseKeyPassword = System.getenv("UMNIK_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.ayuemin.ymnik"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ayuemin.umnik"
        minSdk = 26
        // Keep runtime behaviour on the proven Android 16 baseline while
        // the toolchain and libraries move to API 37. targetSdk 37 will be
        // a separate, testable migration step.
        targetSdk = 36
        versionCode = 139
        versionName = "1.19.6"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Test builds from the work branch must coexist with the installed
            // stable Umnik and must never require removing user data first.
            applicationIdSuffix = ".test"
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Official GitHub Releases are signed with the permanent project key.
            // Without all signing variables, a local release build stays unsigned;
            // it must never silently fall back to the debug certificate.
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
