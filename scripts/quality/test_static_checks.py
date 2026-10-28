import tempfile
import unittest
from pathlib import Path

import static_checks
from static_checks import Findings, check, strip_comments

SUSPENDING = {"await", "withContext", "delay", "fetchPage"}


def run(source, reviewed=None, name="Sample.kt", measures=None):
    findings = Findings(reviewed or {}, measures)
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / name
        path.write_text(source, encoding="utf-8")
        check(path, findings, SUSPENDING)
    findings.stale()
    return findings


def rules(findings):
    return [item.split()[0] for item in findings.items]


class CancellationRule(unittest.TestCase):
    def test_broad_catch_around_suspending_work_is_found(self):
        found = run("suspend fun a() { try { delay(5) } catch (e: Exception) { log(e) } }\n")
        self.assertEqual(["cancellation"], rules(found))

    def test_rethrown_cancellation_passes(self):
        self.assertEqual([], rules(run("suspend fun a() { try { delay(5) } catch (e: CancellationException) { throw e } catch (e: Exception) { log(e) } }\n")))
        self.assertEqual([], rules(run("suspend fun a() { try { delay(5) } catch (e: Exception) { currentCoroutineContext().ensureActive(); log(e) } }\n")))

    def test_cancellation_caught_earlier_in_a_longer_chain_passes(self):
        source = "suspend fun a() { try { delay(5) } catch (e: CancellationException) { throw e } catch (e: Stopped) { done() } catch (e: Exception) { log(e) } }\n"
        self.assertEqual([], rules(run(source)))

    def test_blocking_work_is_not_reported(self):
        self.assertEqual([], rules(run("fun a() = runCatching { JSONObject(text) }.getOrNull()\nfun b() { try { file.readText() } catch (e: Exception) { } }\n")))

    def test_run_catching_around_suspending_work_is_found_and_the_safe_form_passes(self):
        self.assertEqual(["cancellation"], rules(run("suspend fun a() = runCatching { fetchPage(1) }\n")))
        self.assertEqual([], rules(run("suspend fun a() = suspendCatching { fetchPage(1) }\n")))

    def test_text_in_comments_and_strings_is_ignored(self):
        self.assertEqual([], rules(run('// runCatching { delay(1) }\nval text = "runCatching { await() }"\n/* try { delay(1) } catch (e: Exception) { } */\n')))


class ReviewedList(unittest.TestCase):
    SOURCE = "fun a() { runBlocking { work() } }\n"

    def test_a_reviewed_site_passes_and_is_listed(self):
        found = run(self.SOURCE, {"blocking": [{"file": "Sample.kt", "contains": "runBlocking { work() }", "reason": "bounded"}]})
        self.assertEqual([], found.items)
        self.assertEqual([("blocking", "Sample.kt", 1, "bounded")], found.covered)

    def test_an_unreviewed_site_fails(self):
        self.assertEqual(["blocking"], rules(run(self.SOURCE)))

    def test_an_entry_for_another_file_does_not_cover_it(self):
        found = run(self.SOURCE, {"blocking": [{"file": "Other.kt", "contains": "runBlocking", "reason": "x"}]})
        self.assertEqual(["blocking", "reviewed"], rules(found))

    def test_an_entry_that_matches_nothing_fails(self):
        found = run("fun a() = 1\n", {"blocking": [{"file": "Sample.kt", "contains": "runBlocking", "reason": "x"}]})
        self.assertEqual(["reviewed"], rules(found))


class DirectionRule(unittest.TestCase):
    def test_a_screen_may_not_open_the_database_or_read_cookies(self):
        found = run("@Composable fun Screen() { val db = LibraryStateDb(context) }\nfun cookies() = CookieManager.getInstance()\n")
        self.assertEqual(["direction", "direction"], rules(found))

    def test_a_file_that_imports_screen_packages_counts_as_a_screen(self):
        found = run("import androidx.compose.ui.Modifier\nval client = HttpClient(engine)\n")
        self.assertEqual(["direction"], rules(found))

    def test_a_store_without_screen_imports_passes(self):
        self.assertEqual([], rules(run("import androidx.compose.runtime.mutableStateOf\nclass Store { val db = LibraryStateDb(context) }\n")))


class ScreenRules(unittest.TestCase):
    def test_per_frame_reads_in_a_screen_are_found(self):
        found = run("@Composable fun Bar(playback: Gateway) { Text(playback.positionMs.toString()); Box(Modifier.alpha(morph.position.value)) }\n")
        self.assertEqual(["position"], rules(found))

    def test_writing_the_position_is_not_a_read(self):
        self.assertEqual([], rules(run("@Composable fun Bar(state: State) { Button({ state.positionMs = 0 }) }\n")))

    def test_plain_flow_collection_in_a_screen_is_found(self):
        self.assertEqual(["lifecycle"], rules(run("@Composable fun S(flow: StateFlow<Int>) { val v by flow.collectAsState() }\n")))
        self.assertEqual([], rules(run("@Composable fun S(flow: StateFlow<Int>) { val v by flow.collectAsStateWithLifecycle() }\n")))

    def test_an_oversized_file_needs_a_reason(self):
        self.assertEqual(["size"], rules(run("val a = 1\n" * (static_checks.SIZE_CEILING + 1))))


class MeasuresRule(unittest.TestCase):
    SCREEN = "@Composable fun S() { Box(Modifier.padding(12.dp).size(RiffSpace.touch)) { Text(title, fontSize = 14.sp) } }"

    def test_a_screen_with_its_recorded_count_passes(self):
        self.assertEqual([], rules(run(self.SCREEN, measures={"Sample.kt": 2})))

    def test_a_screen_that_gains_a_raw_measure_fails(self):
        self.assertEqual(["measures"], rules(run(self.SCREEN, measures={"Sample.kt": 1})))

    def test_a_new_screen_may_have_none(self):
        self.assertEqual(["measures"], rules(run(self.SCREEN, measures={})))
        self.assertEqual([], rules(run("@Composable fun S() { Box(Modifier.size(RiffSpace.touch)) }", measures={})))

    def test_a_count_that_is_too_high_must_be_lowered(self):
        self.assertEqual(["measures"], rules(run(self.SCREEN, measures={"Sample.kt": 3})))

    def test_only_screens_are_counted_and_the_tokens_file_is_not(self):
        self.assertEqual([], rules(run("val gap = 12.dp", measures={})))
        self.assertEqual([], rules(run("@Composable fun S() {}; val gap = 12.dp", name="DesignTokens.kt", measures={})))

    def test_measures_in_comments_and_names_are_not_counted(self):
        self.assertEqual([], rules(run("@Composable fun S() { val x = size12.dp; val y = a.b2.dp } // 12.dp", measures={})))

    def test_the_rule_is_off_without_a_list(self):
        self.assertEqual([], rules(run(self.SCREEN)))


class StringsRule(unittest.TestCase):
    def test_words_written_into_a_screen_are_found(self):
        self.assertEqual(["strings"], rules(run('@Composable fun S() { Text("Play next") }')))
        self.assertEqual(["strings"], rules(run('fun m() = Modifier.semantics { contentDescription = "Close player" }')))
        self.assertEqual(["strings"], rules(run('fun m() = Modifier.semantics { stateDescription = "$n of $size" }')))
        self.assertEqual(["strings"], rules(run('fun n(b: Notification.Builder) = b.setContentTitle("Downloading")')))
        self.assertEqual(["strings"], rules(run('fun n() = onNotice("Could not save")')))

    def test_text_from_resources_passes(self):
        self.assertEqual([], rules(run('@Composable fun S() { Text(string(R.string.play_next)); Text(keyLabel("Songs")); Text(title) }')))

    def test_literals_without_words_pass(self):
        self.assertEqual([], rules(run('@Composable fun S() { Text("${a.label}${b?.let { " - $it" }.orEmpty()}") }')))
        self.assertEqual(["strings"], rules(run('@Composable fun S() { Text("${if (on) "+" else "-"}$n seconds") }')))
        self.assertEqual([], rules(run('@Composable fun S() { Text("$minutes:$seconds"); Text("\\u00B7"); Text("${count}x"); Text(" ") }')))

    def test_comments_and_other_strings_are_not_read(self):
        self.assertEqual([], rules(run('// Text("Play next")\nval sample = "Text(\\"Play next\\")"\n')))

    def test_a_reviewed_name_passes_and_a_stale_review_is_found(self):
        reviewed = {"strings": [{"file": "Sample.kt", "contains": 'Text("YouTube Music")', "reason": "a name"}]}
        self.assertEqual([], rules(run('@Composable fun S() { Text("YouTube Music") }', reviewed)))
        self.assertEqual(["reviewed"], rules(run('@Composable fun S() { Text(name) }', reviewed)))


class MotionRule(unittest.TestCase):
    def test_literal_timing_is_found(self):
        self.assertEqual(["motion"], rules(run("fun a() = tween<Float>(300)\n")))
        self.assertEqual(["motion"], rules(run("fun a() = spring<Float>(dampingRatio = .8f, stiffness = 500f)\n")))
        self.assertEqual(["motion"], rules(run("fun a(m: MotionPolicy) = GlassMotion.timed<Float>(150, m)\n")))

    def test_timing_from_the_tokens_passes(self):
        self.assertEqual([], rules(run("fun a(m: MotionPolicy) = GlassMotion.timed<Float>(GlassMotion.screenMs, m)\n")))
        self.assertEqual([], rules(run("fun a(ms: Int) = tween<Float>(ms, easing = LinearEasing)\n")))

    def test_the_tokens_file_itself_is_not_checked(self):
        self.assertEqual([], rules(run("fun a() = tween<Float>(300)\n", name="GlassMotion.kt")))

    def test_a_reviewed_gesture_passes(self):
        reviewed = {"motion": [{"file": "Sample.kt", "contains": "stiffness = 700f", "reason": "gesture"}]}
        self.assertEqual([], rules(run("fun a() = spring<Float>(stiffness = 700f)\n", reviewed)))


class Stripping(unittest.TestCase):
    def test_offsets_and_lines_are_kept(self):
        source = 'val a = "x{y" // note {\n/* { */ val b = \'{\'\n'
        stripped = strip_comments(source)
        self.assertEqual(len(source), len(stripped))
        self.assertEqual(source.count("\n"), stripped.count("\n"))
        self.assertNotIn("{", stripped)


class TheSources(unittest.TestCase):
    def test_the_app_sources_pass(self):
        self.assertEqual(0, static_checks.main())


if __name__ == "__main__":
    unittest.main()
