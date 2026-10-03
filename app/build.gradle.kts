import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.musicglass.personal"
    compileSdk = 37
    testBuildType = if (providers.gradleProperty("releaseSmoke").orNull == "true") "release" else "debug"

    defaultConfig {
        applicationId = "com.musicglass.personal"
        minSdk = 31
        targetSdk = 36
        versionCode = 18
        versionName = "0.9.0-rc17"
        buildConfigField("boolean", "LITE", "false")
    }

    if (providers.gradleProperty("releaseSmoke").orNull == "true") {
        sourceSets.getByName("release") {java.srcDir("src/verification/java");manifest.srcFile("src/verification/AndroidManifest.xml")}
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localRelease")
        }
        // Release code and signing key, debuggable: installs over a release install without
        // wiping data, and allows run-as backups, heap dumps and StrictMode (src/stress).
        create("stress") {
            initWith(getByName("release"))
            isDebuggable = true
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
}
