import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
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
        versionCode = 24
        versionName = "0.10.0-rc3"
        buildConfigField("boolean", "LITE", "false")
        // Q12A: the public release feed the update check asks, and the hosts a release page may be on. No feed
        // (the default until releases are published) means the check does not exist in the build and sends
        // nothing. See docs/quality/update-notice.md.
        buildConfigField("String", "UPDATE_FEED",
            groovy.json.JsonOutput.toJson(providers.gradleProperty("riffUpdateFeed").orNull ?: ""))
        buildConfigField("String", "UPDATE_PAGES",
            groovy.json.JsonOutput.toJson(providers.gradleProperty("riffUpdatePages").orNull ?: "github.com"))
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
    // Q14: the two build types the Baseline Profile plugin derives from release, benchmarkRelease (shrunk, not
    // debuggable, measured) and nonMinifiedRelease (the profile is collected from it), get the fixture feed of
    // src/benchmarkFeed and their own package id, so that neither can install over Riff or reach a provider.
    // Release itself has neither the source set nor the id. Both are set in finalizeDsl below.
    sourceSets.getByName("test").java.srcDir("src/testSupport/java")
    sourceSets.getByName("androidTest").java.srcDir("src/testSupport/java")
    sourceSets.getByName("test").resources.srcDir("src/androidTest/assets")
    // Q13: a test that runs without Android reads the default text from strings.xml itself (Strings.kt).
    sourceSets.getByName("test").resources.srcDir("src/main/res/values")

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
        // Q13: en-XA (longer, accented) and ar-XB (right to left) come with a debug build, to look at text by eye.
        // Q18C1: with -PriffSideBySide=true (the device and emulator lanes pass it) the debug build gets its own package
        // id and launcher name, so it and its test APK install beside an installed Riff or Riff Preview on a real phone
        // instead of failing on the certificate. Off by default: unit tests keep the shorter package, because
        // Robolectric's per-test folders hold it and the longest test names would pass Windows' 260-character limit.
        debug {
            isPseudoLocalesEnabled = true
            val sideBySide = providers.gradleProperty("riffSideBySide").orNull == "true"
            if (sideBySide) {
                applicationIdSuffix = ".debug"
                versionNameSuffix = "-debug"
            }
            manifestPlaceholders["riffDebugLabel"] = if (sideBySide) "Riff Debug" else "@string/app_name"
        }
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
        // Q18B4: with -PriffSideBySide=true it is its own app instead, "Riff Stress", so it installs beside Riff
        // and Riff Preview on the owner's phone; it then has nothing to update, so the debug key does when the
        // release key's variables are absent.
        create("stress") {
            initWith(getByName("release"))
            isDebuggable = true
            val sideBySide = providers.gradleProperty("riffSideBySide").orNull == "true"
            if (sideBySide) {
                applicationIdSuffix = ".stress"
                versionNameSuffix = "-stress"
                if (signingConfig == null) signingConfig = signingConfigs.getByName("debug")
            }
            manifestPlaceholders["riffStressLabel"] = if (sideBySide) "Riff Stress" else "@string/app_name"
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
        // The release build under its own package id, to try a candidate on a phone beside an installed
        // release without touching its data. Same code, shrinking, ABI and key; launcher name "Riff Preview"
        // (src/preview). Its data is its own: uninstalling it leaves the installed release as it was.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
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

// The plugin makes its build types when the DSL is finalized and gives each a copy of release's source
// directories. With AGP 8.13 that copy arrives as a provider the Kotlin plugin cannot read as a directory
// ("Could not stat file ...\provider(?)"), so the directories are set here, after the plugin has run. The
// package id is set here for the same reason: the plugin's copy of release would undo an earlier value.
androidComponents {
    finalizeDsl { extension ->
        for (derived in listOf("benchmarkRelease", "nonMinifiedRelease"))
            extension.buildTypes.getByName(derived).applicationIdSuffix = ".benchmark"
        for (derived in listOf("benchmarkRelease", "nonMinifiedRelease")) extension.sourceSets.getByName(derived).apply {
            java.setSrcDirs(listOf("src/benchmarkFeed/java")); kotlin.setSrcDirs(emptyList<String>())
            res.setSrcDirs(listOf("src/benchmarkFeed/res")); assets.setSrcDirs(listOf("src/test/resources/quality/provider"))
            manifest.srcFile("src/benchmarkFeed/AndroidManifest.xml")
        }
        for (derived in listOf("benchmarkLite", "nonMinifiedLite")) extension.sourceSets.getByName(derived).apply {
            java.setSrcDirs(emptyList<String>()); kotlin.setSrcDirs(emptyList<String>())
        }
    }
    // Q18C1: the instrumentations in src/androidTest name the package they test through this placeholder, so a
    // test APK always targets the app it was built with (com.musicglass.personal.debug for debug).
    onVariants { variant -> variant.androidTest?.manifestPlaceholders?.put("riffTargetPackage", variant.applicationId) }
}

// Q14: the profile is generated on request (:app:generateReleaseBaselineProfile) and kept as source under
// src/release/generated/baselineProfiles; an ordinary build only packages what is there.
baselineProfile {
    saveInSrc = true
    automaticGenerationDuringBuild = false
    // The fixture feed is in the build the profile is collected from and not in a release.
    filter {
        include("**")
        exclude("com.musicglass.personal.BenchmarkFeed*")
        exclude("com.musicglass.personal.ToneServer*")
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
    // Q14: installs the Baseline Profile packaged with a release; the profile comes from :benchmark.
    implementation(libs.profileinstaller)
    baselineProfile(project(":benchmark"))
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
