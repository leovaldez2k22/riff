buildscript {
    repositories {
        google()
    }
    dependencies {
        // Q18-024: AGP 8.13.2 bundles R8 8.13.19, which cannot read Kotlin 2.4's metadata (179 warnings per shrunk
        // build). R8 9.1.29 or newer reads it; this pin goes when AGP bundles such an R8.
        classpath("com.android.tools:r8:9.1.56")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
