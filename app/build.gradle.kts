import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android shell around the engine: microphone in, Compose UI out.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.screenshot)
}

android {
    namespace = "io.github.alfonslm.tuner.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.alfonslm.tuner"
        // 26: float PCM capture, the UNPROCESSED audio source and adaptive icons are all available.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-beta"
    }

    // A release is signed with the project's own key when one is given (the release workflow
    // passes it in from the repository's secrets) and with the machine's debug key otherwise.
    // The second kind installs fine, but cannot be installed over a build from another machine.
    val keystore = System.getenv("TUNER_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("TUNER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TUNER_KEY_ALIAS")
                keyPassword = System.getenv("TUNER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Not shrunk or obfuscated for now: every build tried on a phone so far was a debug
            // build, and a shrunk one that has never been run is not something to hand out.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Renders the @PreviewTest composables in src/screenshotTest on the JVM and
    // compares them with the reference images checked in next to them.
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}

// Unit tests that decide things from recorded audio (see ScaleFollowingTest) read the recordings
// that `:engine:recordingsTest` decoded, when there are any; without them those tests are skipped.
tasks.withType<Test>().configureEach {
    val decoded = project(":engine").layout.buildDirectory.dir("recordings").get().asFile
    if (decoded.isDirectory) environment("TUNER_RECORDING_SET", decoded.path)
    testLogging { showStandardStreams = true }
}
