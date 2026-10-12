import tempfile
import unittest
from pathlib import Path

import static_checks
from static_checks import Findings, check, strip_comments

SUSPENDING = {"await", "withContext", "delay", "fetchPage"}


def run(source, reviewed=None, name="Sample.kt"):
    findings = Findings(reviewed or {})
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
