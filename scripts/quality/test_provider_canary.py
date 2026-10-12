import contextlib
from datetime import datetime, timedelta, timezone
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

import provider_canary as canary

T0 = datetime(2026, 10, 12, 6, 0, 30, tzinfo=timezone.utc)


def phase(name="search", classification="Healthy", reason="ok", millis=420, requests=1):
    return {"phase": name, "classification": classification, "reason": reason, "millis": millis, "requests": requests}


def phases_text(*items):
    return json.dumps({"schema": 1, "phases": list(items)})


def result(classification):
    return {"classification": classification}


class Run:
    """Runs main() against a temporary state folder, with the phases a harness would have written."""

    def __init__(self):
        self.folder = tempfile.TemporaryDirectory()
        self.state = Path(self.folder.name) / "state"
        self.moment = T0

    def go(self, text=None, trigger="manual", stream_log=None, hours=24, extra=()):
        args = ["--state", str(self.state), "--trigger", trigger]
        if text is not None:
            path = Path(self.folder.name) / "phases.json"
            path.write_text(text, encoding="utf-8")
            args += ["--phases", str(path)]
        if stream_log is not None:
            path = Path(self.folder.name) / "stream.log"
            path.write_text(stream_log, encoding="utf-8")
            args += ["--stream-log", str(path)]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = canary.main(args + list(extra), self.moment)
        self.moment += timedelta(hours=hours)
        return code, out.getvalue()

    def history(self):
        return canary.load_history(self.state)

    def close(self):
        self.folder.cleanup()


class Sanitizing(unittest.TestCase):
    def test_a_well_formed_result_is_kept_as_it_is(self):
        kept = canary.sanitize_phases(phases_text(phase(), phase("collection")), ("search", "collection"))
        self.assertEqual([phase(), phase("collection")], kept)

    def test_anything_beyond_the_allowed_fields_and_words_is_refused(self):
        bad = [
            dict(phase(), url="https://rr1---sn-x.googlevideo.com/videoplayback?sig=abc"),
            dict(phase(), reason="https://music.youtube.com/"),
            dict(phase(), reason="HTTP 403 for dQw4w9WgXcQ"),
            dict(phase(), classification="Broken"),
            dict(phase(), phase="player"),
            dict(phase(), millis="12"),
            dict(phase(), millis=-1),
            dict(phase(), requests=51),
            dict(phase(), millis=True),
            {key: value for key, value in phase().items() if key != "requests"},
        ]
        for item in bad:
            with self.assertRaises(canary.Unsanitary, msg=item):
                canary.sanitize_phases(phases_text(item), canary.PHASES)
        for text in ["", "<html>", "[]", json.dumps({"schema": 2, "phases": []}), json.dumps({"schema": 1, "phases": [], "body": "x"}),
                     phases_text(phase(), phase()), phases_text(phase("stream"))]:
            with self.assertRaises(canary.Unsanitary, msg=text):
                canary.sanitize_phases(text, ("search", "collection"))

    def test_unsanitary_harness_output_is_recorded_as_a_fault_and_none_of_it_is_stored(self):
        run = Run()
        self.addCleanup(run.close)
        code, out = run.go(phases_text(dict(phase(), reason="ok", url="https://rr1.googlevideo.com/videoplayback?sig=SECRETSECRET")))
        self.assertEqual(2, code)
        stored = (run.state / "history.jsonl").read_text(encoding="utf-8") + (run.state / "latest.md").read_text(encoding="utf-8") + out
        self.assertIn("unsanitary_output", stored)
        for word in ("googlevideo", "SECRET", "https://", "sig="):
            self.assertNotIn(word, stored)

    def test_the_store_refuses_a_record_that_holds_an_address_or_a_long_token(self):
        with tempfile.TemporaryDirectory() as folder:
            for value in ("https://music.youtube.com/x", "AIzaSyA" + "b" * 40, "rr1.googlevideo.com"):
                entry = canary.record([phase()], "manual", "none", T0)
                entry["dependency"] = value
                with self.assertRaises(canary.Unsanitary, msg=value):
                    canary.store(folder, entry)
            self.assertFalse((Path(folder) / "history.jsonl").exists())


class Classifying(unittest.TestCase):
    def test_the_run_takes_the_class_that_claims_least_about_the_app(self):
        cases = [
            (["Healthy", "Healthy", "Healthy"], "Healthy"),
            (["Healthy", "ParserBreak"], "ParserBreak"),
            (["Healthy", "Healthy", "ResolutionBreak"], "ResolutionBreak"),
            (["ParserBreak", "ResolutionBreak"], "ParserBreak"),
            (["ParserBreak", "Blocked"], "Blocked"),
            (["Blocked", "Outage", "ResolutionBreak"], "Outage"),
            (["Healthy", "HarnessFault", "ParserBreak"], "HarnessFault"),
            ([], "HarnessFault"),
        ]
        for classes, expected in cases:
            self.assertEqual(expected, canary.overall([phase(classification=name) for name in classes]), classes)

    def test_two_strikes(self):
        cases = [
            (["ParserBreak"], False),
            (["Healthy", "ParserBreak"], False),
            (["ParserBreak", "ParserBreak"], True),
            (["ParserBreak", "ResolutionBreak"], True),
            (["ParserBreak", "Healthy", "ParserBreak"], False),
            (["ParserBreak", "Blocked", "Outage", "HarnessFault", "ParserBreak"], True),
            (["ParserBreak", "ParserBreak", "Blocked"], False),
            (["ParserBreak", "ParserBreak", "Outage"], False),
            (["ParserBreak", "ParserBreak", "Healthy"], False),
            (["Blocked", "Blocked", "Blocked"], False),
            (["Outage", "Outage"], False),
            (["HarnessFault", "HarnessFault"], False),
            ([], False),
        ]
        for classes, expected in cases:
            self.assertEqual(expected, canary.confirmed([result(name) for name in classes]), classes)


class Running(unittest.TestCase):
    def setUp(self):
        self.run_ = Run()
        self.addCleanup(self.run_.close)

    def test_a_healthy_run_is_recorded_and_says_what_it_is_not(self):
        code, out = self.run_.go(phases_text(phase(), phase("collection")))
        self.assertEqual(0, code)
        self.assertIn("Healthy.", out)
        self.assertIn("| stream | not run |", out)
        self.assertIn("satisfies no release gate", out)
        stored = self.run_.history()[0]
        self.assertEqual({"schema", "utc", "trigger", "catalogLane", "streamLane", "dependency", "classification", "phases", "gate"}, set(stored))
        self.assertEqual(("2026-10-12T06:00:00+00:00", "manual", "jvm", "none", "none", "Healthy"),
                         (stored["utc"], stored["trigger"], stored["catalogLane"], stored["streamLane"], stored["gate"], stored["classification"]))
        self.assertRegex(stored["dependency"], r"^innertubex \d+\.\d+")

    def test_one_break_warns_and_the_second_in_a_row_fails_the_run(self):
        broken = phases_text(phase(), phase("collection", "ParserBreak", "empty"))
        code, out = self.run_.go(broken)
        self.assertEqual(0, code)
        self.assertIn("seen once. Not confirmed", out)
        self.assertNotIn("::error", out)
        code, out = self.run_.go(broken)
        self.assertEqual(1, code)
        self.assertIn("CONFIRMED BREAK (ParserBreak", out)
        self.assertIn("::error title=Provider canary::", out)
        self.assertIn(canary.RUNBOOK, out)
        code, out = self.run_.go(phases_text(phase(), phase("collection")))
        self.assertEqual(0, code)
        code, out = self.run_.go(broken)
        self.assertEqual(0, code, "a healthy result in between starts the count again")

    def test_a_block_or_an_outage_is_never_reported_as_a_break_however_often_it_repeats(self):
        for classification, reason, words in (("Blocked", "refused", "nothing about the app"), ("Outage", "unreachable", "nothing about the app")):
            for _ in range(4):
                code, out = self.run_.go(phases_text(phase(classification=classification, reason=reason), phase("collection", "ParserBreak", "empty")))
                self.assertEqual(0, code)
                self.assertIn(words, out)
                self.assertNotIn("BREAK", out)
                self.assertNotIn("::error", out)
                self.assertNotIn("::warning", out)

    def test_a_block_between_two_breaks_does_not_clear_the_first(self):
        broken = phases_text(phase(classification="ParserBreak", reason="unreadable"))
        self.assertEqual(0, self.run_.go(broken)[0])
        self.assertEqual(0, self.run_.go(phases_text(phase(classification="Blocked", reason="rate_limited")))[0])
        self.assertEqual(1, self.run_.go(broken)[0])

    def test_a_missing_result_is_the_canarys_own_fault(self):
        code, out = self.run_.go(extra=["--phases", str(Path(self.run_.folder.name) / "absent.json")])
        self.assertEqual(2, code)
        self.assertIn("Harness fault", out)
        self.assertEqual("no_output", self.run_.history()[0]["phases"][0]["reason"])

    def test_a_scheduled_run_within_twenty_hours_of_the_last_one_sends_nothing(self):
        healthy = phases_text(phase(), phase("collection"))
        self.assertEqual(0, self.run_.go(healthy, trigger="schedule", hours=3)[0])
        code, out = self.run_.go(healthy, trigger="schedule", hours=3)
        self.assertEqual(0, code)
        self.assertIn("Skipped", out)
        self.assertEqual(1, len(self.run_.history()))
        self.assertEqual(0, self.run_.go(healthy, trigger="manual", hours=14)[0])
        self.assertEqual(2, len(self.run_.history()), "a manual run is always allowed")
        self.assertEqual(0, self.run_.go(healthy, trigger="schedule")[0])
        self.assertEqual(3, len(self.run_.history()), "twenty hours after the last scheduled run")

    def test_the_stream_phase_is_read_from_one_status_line_of_the_device_log(self):
        line = "INSTRUMENTATION_STATUS: riffCanary=" + json.dumps(phase("stream", millis=3100, requests=2))
        log = "INSTRUMENTATION_STATUS: class=com.musicglass.personal.ProviderCanaryDeviceTest\n%s\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n" % line
        code, out = self.run_.go(phases_text(phase(), phase("collection")), stream_log=log, extra=["--stream-lane", "emulator"])
        self.assertEqual(0, code)
        stored = self.run_.history()[0]
        self.assertEqual(("emulator", ["search", "collection", "stream"]), (stored["streamLane"], [item["phase"] for item in stored["phases"]]))
        self.assertNotIn("not run", out)

    def test_a_device_log_without_the_line_or_with_more_in_it_is_a_fault(self):
        self.assertEqual("no_output", canary.stream_phase("OK (1 test)\n")["reason"])
        twice = "INSTRUMENTATION_STATUS: riffCanary=%s\n" % json.dumps(phase("stream")) * 2
        self.assertEqual("no_output", canary.stream_phase(twice)["reason"])
        leaking = 'INSTRUMENTATION_STATUS: riffCanary={"phase":"stream","classification":"Healthy","reason":"ok","millis":1,"requests":2,"url":"https://x.googlevideo.com/"}\n'
        self.assertEqual("unsanitary_output", canary.stream_phase(leaking)["reason"])
        wrong = "INSTRUMENTATION_STATUS: riffCanary=%s\n" % json.dumps(phase("search"))
        self.assertEqual("unsanitary_output", canary.stream_phase(wrong)["reason"])

    def test_status_repeats_the_last_result_without_running_anything(self):
        self.assertEqual((0, "No canary result is stored.\n"), self.run_.go(extra=["--status"]))
        self.run_.go(phases_text(phase(classification="ParserBreak", reason="empty")))
        self.run_.go(phases_text(phase(classification="ParserBreak", reason="empty")))
        code, out = self.run_.go(extra=["--status"])
        self.assertEqual(1, code)
        self.assertEqual(2, len(self.run_.history()))

    def test_the_summary_reaches_the_ci_summary_file(self):
        target = Path(self.run_.folder.name) / "step-summary.md"
        os.environ["GITHUB_STEP_SUMMARY"] = str(target)
        self.addCleanup(os.environ.pop, "GITHUB_STEP_SUMMARY", None)
        self.run_.go(phases_text(phase(), phase("collection")))
        self.assertIn("## Provider canary", target.read_text(encoding="utf-8"))


class Workflow(unittest.TestCase):
    TEXT = (canary.ROOT / ".github" / "workflows" / "provider-canary.yml").read_text(encoding="utf-8")

    def test_it_runs_once_a_day_and_on_request_and_never_on_a_pull_request_or_a_push(self):
        triggers = self.TEXT.split("\non:\n", 1)[1].split("\n\n", 1)[0]
        self.assertEqual(1, triggers.count("- cron:"))
        self.assertRegex(triggers, r"cron: '\d+ \d+ \* \* \*'")
        self.assertIn("workflow_dispatch:", triggers)
        self.assertNotIn("pull_request", self.TEXT)
        self.assertNotIn("push:", self.TEXT)

    def test_it_needs_no_secret_and_cannot_write_to_the_repository(self):
        self.assertNotIn("secrets.", self.TEXT)
        self.assertIn("permissions:\n  contents: read", self.TEXT)
        for word in ("git push", "git commit", "gh release", "gh pr"):
            self.assertNotIn(word, self.TEXT)


if __name__ == "__main__":
    unittest.main()
