import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Q14: Macrobenchmark and Baseline Profile generation. This module holds tests only; they drive the app from
// outside its process through the benchmark build types the Baseline Profile plugin adds to :app
// (benchmarkRelease, nonMinifiedRelease). See docs/quality/performance.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    // Not ".benchmark": that is the package id of the app variants this module measures.
    namespace = "com.musicglass.personal.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // An emulator is good for checking that the journeys run and for generating the profile. Its timings are
        // not evidence: -PriffBenchmarkOnEmulator lets the run go ahead and docs/quality/performance.md says so.
        if (providers.gradleProperty("riffBenchmarkOnEmulator").isPresent)
            testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        providers.gradleProperty("riffBenchmarkIterations").orNull?.let { testInstrumentationRunnerArguments["riff.iterations"] = it }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The device already attached is used: the disposable emulator, or a phone the user has approved.
baselineProfile {
    useConnectedDevices = true
}

// Only the APK of :app is wanted from these configurations. Followed further, they pick desktop variants of the
// app's multiplatform libraries, which the offline cache has no reason to hold.
configurations.configureEach { if (name.endsWith("TestedApks")) isTransitive = false }

dependencies {
    implementation(libs.benchmark.macro)
    implementation("androidx.test.ext:junit:1.1.5")
}
