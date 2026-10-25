import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// AGP 9 provides Kotlin support built in, so the `org.jetbrains.kotlin.android`
// plugin is intentionally NOT applied here. Compose still needs its compiler
// plugin.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Optional release signing.
//
// Provide the values either locally in keystore.properties (git-ignored) or,
// in CI, as environment variables. If none are supplied the release build falls
// back to the debug key, so `assembleRelease` always produces an installable
// APK — just one that isn't suitable for distribution.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

val releaseStorePath = signingValue("storeFile", "KEYSTORE_FILE")
val releaseStorePass = signingValue("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val releaseKeyPass = signingValue("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = releaseStorePath != null && releaseStorePass != null &&
    releaseKeyAlias != null && releaseKeyPass != null

android {
    namespace = "dev.kr1901.openvid"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kr1901.openvid"
        minSdk = 31
        targetSdk = 36
        versionCode = 10
        versionName = "0.10.0"
        // Ship only the locales we actually have strings for; this drops the
        // translated resources every androidx library carries.
        resourceConfigurations += listOf("en", "ru")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Emulators are x86_64; real devices are arm64.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // arm64 only. Every Android 12+ phone that matters is 64-bit ARM, and
            // each extra ABI would add ~15 MB of bundled Python for yt-dlp.
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // With AGP 9's built-in Kotlin, Kotlin compiler options live inside the
    // `android` block (replacing the old `kotlinOptions {}`).
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // youtubedl-android requires the native libraries to be extracted at
        // install time; it also means the .so files are compressed in the APK,
        // which is the smaller download.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    // The dependency metadata block is only used by Play; drop it from the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // yt-dlp, used purely as an extractor (see YtDlp.kt). The `ffmpeg` and
    // `aria2c` modules are deliberately NOT included: we never ask yt-dlp to
    // download or merge, so they would add ~40 MB for nothing.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------------------
// Native Rust core.
//
// Builds ../rust with cargo-ndk and copies the resulting .so files into
// src/main/jniLibs so AGP packages them into the APK.
//
// One-time prerequisites on the build machine:
//   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
//   cargo install cargo-ndk
//   export ANDROID_NDK_HOME=/path/to/ndk
// ---------------------------------------------------------------------------
val rustDir = rootProject.layout.projectDirectory.dir("rust")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust engine for the Android ABIs via cargo-ndk."
    workingDir = rustDir.asFile
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release", "--features", "android",
    )
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(cargoNdkBuild)
}
