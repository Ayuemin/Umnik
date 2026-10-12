// Umnik development branch: bundled offline Russian neural dictation
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
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
        versionCode = 204
        versionName = "1.20.2"

        // Local Shell embeds Python for the supported 64-bit release ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
            // Test builds from work branches must coexist with the installed
            // stable Umnik and must never require removing user data first.
            applicationIdSuffix = ".test"
        }

        release {
            // Keep shrinking and optimisation enabled for release builds;
            // app-specific keep rules live in proguard-rules.pro and are
            // exercised by CI via assembleRelease.
            isMinifyEnabled = true
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

chaquopy {
    defaultConfig {
        version = "3.13"
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
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Vosk's neural acoustic model is bundled in the APK, not downloaded on the phone.
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}


// The Russian model is an Apache-2.0 asset shipped INSIDE the APK.
// We intentionally pin the archive SHA-256 and fail the build on a mismatch.
// Source: https://alphacephei.com/vosk/models (vosk-model-small-ru-0.22)
// sha256: model mirrors at rhasspy/vosk-models and localstack/vosk-models.
val voskZipUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
val voskZipSha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
val bundledRussianModel = layout.buildDirectory.dir("generated/umnik-vosk-assets")
val prepareRussianVoskModel by tasks.registering {
    inputs.property("archiveUrl", voskZipUrl)
    inputs.property("archiveSha256", voskZipSha256)
    outputs.dir(bundledRussianModel)
    doLast {
        val root = bundledRussianModel.get().asFile
        val dest = File(root, "vosk-ru-small")
        if (File(dest, ".ready").isFile && File(dest, "am/final.mdl").isFile) return@doLast
        val archive = File(layout.buildDirectory.get().asFile, "downloads/vosk-model-small-ru-0.22.zip")
        archive.parentFile.mkdirs()
        if (!archive.exists()) {
            val temporary = File(archive.parentFile, archive.name + ".tmp")
            try {
                val connection = URI(voskZipUrl).toURL().openConnection().apply {
                    connectTimeout = 45_000
                    readTimeout = 180_000
                }
                connection.getInputStream().use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(archive)) { "Cannot cache Vosk archive" }
            } finally {
                temporary.delete()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val len = input.read(buf)
                if (len == -1) break
                digest.update(buf, 0, len)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        check(actualHash == voskZipSha256) {
            "Vosk model checksum mismatch: expected $voskZipSha256, got $actualHash"
        }
        val staging = File(root, "vosk-ru-small.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                var total = 0L
                val modelPrefix = "vosk-model-small-ru-0.22/"
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.startsWith(modelPrefix)) continue
                    val subpath = entry.name.removePrefix(modelPrefix)
                    if (subpath.isEmpty()) continue
                    val target = File(staging, subpath)
                    val basePath = staging.canonicalPath + File.separator
                    check(target.canonicalPath.startsWith(basePath)) { "Unsafe model archive path" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        total += entry.size.coerceAtLeast(0)
                        check(total < 350L * 1024 * 1024) { "Model archive too large" }
                        target.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().buffered().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            check(File(staging, "am/final.mdl").isFile) { "Model archive is missing am/final.mdl" }
            dest.deleteRecursively()
            check(staging.renameTo(dest)) { "Cannot publish Vosk model assets" }
            File(dest, ".ready").writeText("sha256:$voskZipSha256")
        } finally {
            staging.deleteRecursively()
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(bundledRussianModel.get().asFile)

// Gradle 9 tracks the generated directory as output of prepareRussianVoskModel.
// Every Android task which can inspect sourceSets (including lint model writers
// and asset merges) must explicitly depend on that producer. A proper AGP
// variant-source task type can replace this conservative wiring later.
tasks.configureEach {
    if (name != "prepareRussianVoskModel" && !name.startsWith("clean", ignoreCase = true)) {
        dependsOn(prepareRussianVoskModel)
    }
}
