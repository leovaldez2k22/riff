import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

_SCRIPT = Path(__file__).resolve().parents[1] / "desktop" / "soak-status.py"
_SPEC = importlib.util.spec_from_file_location("soak_status", _SCRIPT)
soak = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(soak)

_FIXTURES = Path(__file__).resolve().parent / "fixtures" / "soak-status"
# Values used in running.json.
_PID, _START, _HEARTBEAT = 4242, 1762300000000, 1762300600000


def _fixture(name):
    return json.loads((_FIXTURES / name).read_text(encoding="utf-8"))


def _alive(pid):
    return _START if pid == _PID else None


def _gone(pid):
    return None


class SoakStatusTest(unittest.TestCase):
    def test_pass_and_fail_are_reported_as_written(self):
        # A finished run is final whatever the clock or the process table says.
        self.assertEqual(soak.PASS, soak.evaluate(_fixture("pass.json"), _HEARTBEAT * 2, _gone)[0])
        status, reason = soak.evaluate(_fixture("fail.json"), _HEARTBEAT * 2, _gone)
        self.assertEqual(soak.FAIL, status)
        self.assertIn("24000 underrun frames", reason)

    def test_live_process_with_fresh_heartbeat_is_running(self):
        self.assertEqual(soak.RUNNING, soak.evaluate(_fixture("running.json"), _HEARTBEAT + 30_000, _alive)[0])

    def test_heartbeat_older_than_two_minutes_is_stale(self):
        self.assertEqual(soak.RUNNING, soak.evaluate(_fixture("running.json"), _HEARTBEAT + 120_000, _alive)[0])
        status, reason = soak.evaluate(_fixture("running.json"), _HEARTBEAT + 120_001, _alive)
        self.assertEqual(soak.STALE, status)
        self.assertIn("heartbeat", reason)

    def test_dead_process_is_stale(self):
        status, reason = soak.evaluate(_fixture("running.json"), _HEARTBEAT + 30_000, _gone)
        self.assertEqual(soak.STALE, status)
        self.assertIn("not running", reason)

    def test_reused_pid_is_stale(self):
        status, reason = soak.evaluate(_fixture("running.json"), _HEARTBEAT + 30_000, lambda pid: _START + 60_000)
        self.assertEqual(soak.STALE, status)
        self.assertIn("reused", reason)

    def test_running_without_liveness_fields_is_stale(self):
        # The shape of the 4 November 2026 result: RUNNING at 2,600 s, written before heartbeats existed.
        status, reason = soak.evaluate(_fixture("legacy-running.json"), _HEARTBEAT, _alive)
        self.assertEqual(soak.STALE, status)
        self.assertIn("can't prove", reason)

    def test_unknown_status_is_an_error(self):
        for bad in ({"status": "STALE"}, {"status": "running"}, {}, []):
            with self.assertRaises(ValueError):
                soak.evaluate(bad, _HEARTBEAT, _alive)

    def test_main_reads_a_directory_and_sets_the_exit_code(self):
        expected = {"pass.json": 0, "fail.json": 1, "running.json": 0, "legacy-running.json": 1}
        with tempfile.TemporaryDirectory(prefix="riff soak ") as directory:
            for name, code in expected.items():
                (Path(directory) / "result.json").write_text((_FIXTURES / name).read_text(encoding="utf-8"),
                                                             encoding="utf-8")
                out = io.StringIO()
                with redirect_stdout(out):
                    self.assertEqual(code, soak.main([directory, "--json"], _HEARTBEAT + 30_000, _alive), name)
                self.assertEqual(_fixture(name)["status"], json.loads(out.getvalue())["written"])

    def test_missing_or_broken_file_exits_2(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(2, soak.main([directory], _HEARTBEAT, _alive))
            (Path(directory) / "result.json").write_text("{half", encoding="utf-8")
            self.assertEqual(2, soak.main([directory], _HEARTBEAT, _alive))
        self.assertEqual(2, errors.getvalue().count("ERROR:"))

    @unittest.skipUnless(sys.platform == "win32", "process start times are read through the Windows API")
    def test_this_process_is_found_and_a_free_pid_is_not(self):
        started = soak.process_start_ms(os.getpid())
        self.assertIsNotNone(started)
        self.assertLess(started, int(__import__("time").time() * 1000))
        self.assertIsNone(soak.process_start_ms(0x7FFFFFF0))


if __name__ == "__main__":
    unittest.main()
