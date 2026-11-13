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

// The engine against recordings of a real instrument. Not part of `test`: it needs ffmpeg to
// decode the files in /recordings. Run it before a build is handed to anyone.
val recordingsDirectory = rootProject.layout.projectDirectory.dir("recordings")
val decodedRecordings = layout.buildDirectory.dir("recordings")

val decodeRecordings by tasks.registering {
    description = "Decodes the recordings in /recordings to WAV with ffmpeg."
    inputs.dir(recordingsDirectory)
    outputs.dir(decodedRecordings)
    val sources = recordingsDirectory.asFile
    val target = decodedRecordings.map { it.asFile }
    doLast {
        val out = target.get().apply { mkdirs() }
        val files = sources.listFiles { file -> file.extension == "m4a" }.orEmpty().sorted()
        check(files.isNotEmpty()) { "no recordings in $sources" }
        for (file in files) {
            val wav = File(out, file.nameWithoutExtension + ".wav")
            val process = try {
                ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", file.path, "-ac", "1", "-ar", "48000", "-c:a", "pcm_f32le", wav.path)
                    .inheritIO().start()
            } catch (e: java.io.IOException) {
                throw GradleException("ffmpeg is needed to decode the recordings and was not found on the PATH", e)
            }
            check(process.waitFor() == 0) { "ffmpeg could not decode ${file.name}" }
        }
    }
}

tasks.register<Test>("recordingsTest") {
    description = "Runs the engine over the recordings of a real instrument. Run before handing a build to anyone."
    group = "verification"
    dependsOn(decodeRecordings)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*RecordingsTest") }
    environment("TUNER_RECORDING_SET", decodedRecordings.get().asFile.path)
    // The recordings do not change, but the answer is wanted every time it is asked for.
    outputs.upToDateWhen { false }
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}
