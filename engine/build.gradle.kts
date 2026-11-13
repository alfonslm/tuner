import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The tuner itself: DSP, instrument knowledge and the search/lock logic.
// Pure Kotlin with no Android dependency, so it is unit-tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}
