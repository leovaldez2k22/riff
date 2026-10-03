import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.musicglass.personal"
    compileSdk = 37
    // Device UI tests (W7.4) run against the stress build: -PriffTestBuildType=stress.
    testBuildType = if (providers.gradleProperty("releaseSmoke").orNull == "true") "release"
        else providers.gradleProperty("riffTestBuildType").orNull ?: "debug"

    defaultConfig {
        applicationId = "com.musicglass.personal"
        minSdk = 31
        targetSdk = 36
        versionCode = 19
        versionName = "0.9.0-rc18"
        buildConfigField("boolean", "LITE", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (providers.gradleProperty("releaseSmoke").orNull == "true") {
        sourceSets.getByName("release") {java.srcDir("src/verification/java");manifest.srcFile("src/verification/AndroidManifest.xml")}
    }

    // The device tests use the verification-only preview activity (not shipped in release or stress).
    if (providers.gradleProperty("riffTestBuildType").orNull == "stress") {
        sourceSets.maybeCreate("stress").java.srcDir("src/verification/java")
    }

    signingConfigs {
        create("localRelease") {
            val keystore = System.getenv("MUSIC_GLASS_KEYSTORE")
            val storePassword = System.getenv("MUSIC_GLASS_STORE_PASSWORD")
            val keyAliasName = System.getenv("MUSIC_GLASS_KEY_ALIAS")
            val keyPassword = System.getenv("MUSIC_GLASS_KEY_PASSWORD")
            if (!keystore.isNullOrBlank() && !storePassword.isNullOrBlank() &&
                !keyAliasName.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
                storeFile = file(keystore)
                this.storePassword = storePassword
                keyAlias = keyAliasName
                this.keyPassword = keyPassword
            }
        }
    }
    buildTypes {
        release {
            // R8 and resource shrinking (plan W4): the APK was 128.5 MiB, 61 MB of it unshrunk DEX.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The target phones are 64-bit ARM; x86/x86_64 (emulators) and armeabi-v7a added ~52 MB of
            // native code. Riff Lite (below) keeps armeabi-v7a for older phones; debug keeps every ABI.
            ndk { abiFilters += listOf("arm64-v8a") }
            // Unsigned when the key's environment variables are absent (CI never has the key).
            signingConfig = signingConfigs.getByName("localRelease").takeIf { it.storeFile != null }
        }
        // Release code and signing key, debuggable: installs over a release install without
        // wiping data, and allows run-as backups, heap dumps and StrictMode (src/stress).
        create("stress") {
            initWith(getByName("release"))
            isDebuggable = true
            // Not shrunk: AGP disables R8 optimisation for debuggable builds but still removes unused
            // code, which broke instrumentation (it uses the app's copy of the Kotlin stdlib). Check
            // shrinking with a real release build instead.
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "release"
        }
        // Separate app for low-RAM phones: own package id, so it installs beside Riff, never over it.
        // Glass/blur off by default, half-size artwork, smaller image cache, large heap (src/lite).
        create("lite") {
            initWith(getByName("release"))
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "LITE", "true")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Compose UI tests run on the JVM with Robolectric (plan W7.2).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric sets a FileDescriptor's fd through jdk.internal.access.SharedSecrets when it
            // creates shared memory; JDK 17+ blocks that without these.
            all {
                it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.compose.icons)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.innertubex)
    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization.json)
    debugImplementation(libs.compose.tooling)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
