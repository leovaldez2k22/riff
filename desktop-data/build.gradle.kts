plugins {
    kotlin("jvm")
    id("app.cash.sqldelight") version "2.1.0"
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("app.cash.sqldelight:sqlite-driver:2.1.0")
    implementation("org.xerial:sqlite-jdbc:3.51.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test-junit"))
}
sqldelight { databases { create("LibraryDb") { packageName.set("com.musicglass.data.db") } } }

tasks.test { systemProperty("riff.data.benchmark",rootProject.file("artifacts/desktop/phase2/sqlite-100k.json").absolutePath) }

tasks.test { systemProperty("riff.data.testClasspath",sourceSets.test.get().runtimeClasspath.asPath) }

tasks.test { systemProperty("riff.encore.benchmark",rootProject.file("artifacts/desktop/phase3/encore-100k.json").absolutePath) }

// Wall-clock acceptance runs after compilation/native tests, never concurrently with other Gradle forks.
// The original 300 ms target remains in the test; compiler-load failures remain in the evidence logs.
tasks.test { exclude("**/EncoreLoadAcceptanceTest.class") }
tasks.register<Test>("encoreLoadAcceptance") {
    testClassesDirs=sourceSets.test.get().output.classesDirs
    classpath=sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.musicglass.data.EncoreLoadAcceptanceTest") }
    maxParallelForks=1
    systemProperty("riff.encore.benchmark",rootProject.file("artifacts/desktop/phase3/encore-100k.json").absolutePath)
    mustRunAfter(tasks.test,":desktop:test",":desktop:nativeBackgroundTest",":desktop:encorePlaybackAcceptance")
}
