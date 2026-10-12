import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

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
        versionCode = 22
        versionName = "0.10.0-rc1"
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

    sourceSets.getByName("debug").java.srcDir("src/verification/java")
    sourceSets.getByName("test").java.srcDir("src/testSupport/java")
    sourceSets.getByName("androidTest").java.srcDir("src/testSupport/java")
    sourceSets.getByName("test").resources.srcDir("src/androidTest/assets")

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
                // Q10A: the live provider canary sends real requests, so it is not part of any ordinary test run.
                // scripts/quality/provider_canary.py asks for it with -PriffCanaryOut=<file>.
                val canaryOut = providers.gradleProperty("riffCanaryOut").orNull
                if (canaryOut == null) it.exclude("**/ProviderCanaryLive*")
                else { it.systemProperty("riff.canary.out", canaryOut); it.outputs.upToDateWhen { false } }
                // Q12: ScreenBaselineTest compares with the kept pictures; -PriffRecordScreens writes them instead.
                if (providers.gradleProperty("riffRecordScreens").isPresent) { it.systemProperty("riff.screens.record", "true"); it.outputs.upToDateWhen { false } }
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

abstract class GenerateBuildIdentityAssets : DefaultTask() {
    @get:Input abstract val identityJson: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val target = outputDirectory.file("quality/build-identity.json").get().asFile
        target.parentFile.mkdirs()
        target.writeText(identityJson.get() + "\n", Charsets.UTF_8)
    }
}

// Provider-backed processes are configuration inputs; missing/failed Git never means clean.
fun qualityGit(vararg arguments: String): String? = try {
    val result = providers.exec {
        workingDir(rootDir)
        commandLine(listOf("git") + arguments)
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim() else null
} catch (_: org.gradle.process.internal.ExecException) {
    null
}
val qualityHead = qualityGit("rev-parse", "HEAD")?.takeIf { it.matches(Regex("[a-f0-9]{40,64}")) }
val qualityStatus = qualityGit("status", "--porcelain", "--untracked-files=normal")
val qualityRevision = if (qualityHead != null && qualityStatus != null) qualityHead else "local-unknown"
val qualityDirty: Boolean? = if (qualityRevision == "local-unknown") null else !qualityStatus.isNullOrBlank()
val qualityState = when (qualityDirty) { true -> "dirty"; false -> "clean"; null -> "local-unknown" }
val qualitySourceFiles = rootProject.fileTree(rootDir) {
    include("app/src/**", "gradle/**", "app/build.gradle.kts", "app/proguard-rules.pro",
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat")
}.files.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
val qualityDigest = MessageDigest.getInstance("SHA-256").apply {
    qualitySourceFiles.forEach { source ->
        update(source.relativeTo(rootDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        update(byteArrayOf(0))
        update(providers.fileContents(rootProject.layout.projectDirectory.file(
            source.relativeTo(rootDir).invariantSeparatorsPath)).asBytes.get())
        update(byteArrayOf(0))
    }
}.digest().joinToString("") { "%02x".format(it) }

androidComponents {
    onVariants { variant ->
        val identity = groovy.json.JsonOutput.toJson(linkedMapOf<String, Any?>(
            "revision" to qualityRevision, "dirty" to qualityDirty, "sourceState" to qualityState,
            "sourceDigest" to qualityDigest, "variant" to variant.name,
            "versionName" to variant.outputs.first().versionName.get(),
            "versionCode" to variant.outputs.first().versionCode.get(),
            "toolchain" to mapOf("gradle" to gradle.gradleVersion, "jdk" to System.getProperty("java.version"),
                "agp" to libs.versions.agp.get(), "kotlin" to libs.versions.kotlin.get(),
                "compileSdk" to 37, "targetSdk" to 36, "minSdk" to 31),
        ))
        mapOf("SOURCE_REVISION" to qualityRevision, "SOURCE_STATE" to qualityState,
            "SOURCE_DIGEST" to qualityDigest).forEach { (name, value) ->
            requireNotNull(variant.buildConfigFields).put(name,
                com.android.build.api.variant.BuildConfigField("String", groovy.json.JsonOutput.toJson(value), null))
        }
        val taskName = "generate" + variant.name.replaceFirstChar { it.uppercaseChar() } + "BuildIdentityAssets"
        val identityTask = tasks.register<GenerateBuildIdentityAssets>(taskName) {
            identityJson.set(identity)
            outputDirectory.set(layout.buildDirectory.dir("generated/qualityIdentity/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(identityTask, GenerateBuildIdentityAssets::outputDirectory)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(identityTask, GenerateBuildIdentityAssets::outputDirectory)
    }
}
