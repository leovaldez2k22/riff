package com.musicglass.personal.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the first usable shell: 20 cold launches on the fixture feed. timeToInitialDisplayMs is the
 * first frame; timeToFullDisplayMs is Home with its shelves and the startup logo gone (the app reports it), and
 * is the budgeted number. The logo plays only while system animations are on, so they must be on.
 * Run once with nothing compiled and once with the shipped Baseline Profile, so the report shows what the
 * profile is worth.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    @Before fun begin() = cleanSlate()

    @Test fun coldStartWithoutProfile() = coldStart(CompilationMode.None())
    @Test fun coldStartWithBaselineProfile() = coldStart(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET, metrics = listOf(StartupTimingMetric()), compilationMode = mode,
        startupMode = StartupMode.COLD, iterations = iterations(20),
        setupBlock = { plainFeed(); pressHome() },
    ) {
        startActivityAndWait()
        home()
    }
}
