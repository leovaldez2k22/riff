import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import check_performance as perf

PHONE = {"brand": "vivo", "device": "PD2217", "model": "I2217", "fingerprint": "vivo/I2217/I2217:14/UP1A/1:user/release-keys", "version": {"sdk": 34}}
EMULATOR = {"brand": "google", "device": "emulator64_x86_64_arm64", "model": "sdk_gphone64_x86_64",
            "fingerprint": "google/sdk_gphone64_x86_64/emulator64_x86_64_arm64:12/SE1A/1:userdebug/dev-keys", "version": {"sdk": 31}}


def bench(name, metrics=None, sampled=None):
    return {"name": name, "className": "com.musicglass.personal.benchmark.X",
            "metrics": {key: {"runs": runs} for key, runs in (metrics or {}).items()},
            "sampledMetrics": {key: {"runs": runs} for key, runs in (sampled or {}).items()}}


def everything(start=900.0, action=100.0, queue=1500.0, local=300.0, online=2000.0, missed=1.0, loops=5):
    return [
        bench("coldStartWithBaselineProfile", {"timeToFullDisplayMs": [start] * 20, "timeToInitialDisplayMs": [400.0] * 20}),
        bench("coldStartWithoutProfile", {"timeToFullDisplayMs": [start + 200] * 20}),
        bench("playerAndQueueActions", sampled={name: [[action] * 5] * 6 for name in perf.ACTIONS}),
        bench("queueOfTwoThousand", sampled={"queue_prepareMs": [[queue]] * 10, "queue_mainMs": [[40.0]] * 10}),
        bench("firstAudioLocalBundle", sampled={"first_audioMs": [[local]] * 20}),
        bench("firstAudioOnline", sampled={"first_audioMs": [[online]] * 20}),
        *(bench(journey, {"deadlineFrameCount": [200.0] * loops, "missedDeadlinePercent": [missed] * loops}) for journey in perf.FRAME_JOURNEYS),
    ]


class Files(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def results(self, benchmarks, build=PHONE, name="a.json"):
        path = Path(self.dir.name) / name
        path.write_text(json.dumps({"context": {"build": build, "memTotalBytes": 8 * 1024 ** 3}, "benchmarks": benchmarks}), encoding="utf-8")
        return str(path)

    def summary(self, benchmarks, build=PHONE, tier="R", hz=60.0):
        results, devices = perf.load([self.results(benchmarks, build)])
        rows, information = perf.summarize(results, devices, tier, hz)
        return rows, information, devices


class PercentileTest(unittest.TestCase):
    def test_nearest_rank(self):
        samples = list(range(1, 21))
        self.assertEqual(19, perf.percentile(samples, .95))
        self.assertEqual(10, perf.percentile(samples, .5))
        self.assertEqual(7, perf.percentile([7], .95))

    def test_one_slow_start_in_twenty_is_the_p95_of_nineteen_fast_ones(self):
        self.assertEqual(900, perf.percentile([900] * 19 + [5000], .95))
        self.assertEqual(5000, perf.percentile([900] * 18 + [5000] * 2, .95))

    def test_no_samples_is_an_error(self):
        with self.assertRaises(perf.PerformanceError):
            perf.percentile([], .95)


class SummaryTest(Files):
    def verdicts(self, rows):
        return {row[0]: row[5] for row in rows}

    def test_a_phone_inside_every_budget_passes(self):
        rows, information, _ = self.summary(everything())
        self.assertEqual({"PASS"}, set(self.verdicts(rows).values()))
        self.assertEqual(0, perf.status(rows))
        self.assertIn("Cold start without the profile", [row[0] for row in information])

    def test_a_slow_start_fails_tier_r_and_passes_tier_l(self):
        rows, _, _ = self.summary(everything(start=1700.0))
        self.assertEqual("FAIL", self.verdicts(rows)["Cold start to usable shell, p95 ms"])
        self.assertEqual(2, perf.status(rows))
        rows, _, _ = self.summary(everything(start=1700.0), tier="L")
        self.assertEqual("PASS", self.verdicts(rows)["Cold start to usable shell, p95 ms"])

    def test_an_emulator_is_never_a_pass_or_a_fail(self):
        rows, _, devices = self.summary(everything(start=5000.0), build=EMULATOR)
        self.assertTrue(devices[0]["emulator"])
        self.assertEqual({"NOT EVIDENCE (emulator)"}, set(self.verdicts(rows).values()))
        self.assertEqual(3, perf.status(rows))
        self.assertIn("EMULATOR", perf.render(rows, [], devices, "R"))

    def test_fewer_samples_than_the_protocol_is_not_a_verdict(self):
        benchmarks = everything()
        benchmarks[0] = bench("coldStartWithBaselineProfile", {"timeToFullDisplayMs": [900.0] * 3})
        rows, _, _ = self.summary(benchmarks)
        self.assertEqual("INSUFFICIENT (3 of 20)", self.verdicts(rows)["Cold start to usable shell, p95 ms"])
        self.assertEqual(3, perf.status(rows))

    def test_a_missing_benchmark_is_reported_missing(self):
        rows, _, _ = self.summary([item for item in everything() if item["name"] != "firstAudioLocalBundle"])
        self.assertEqual("MISSING", self.verdicts(rows)["Local bundle first audio, p95 ms"])
        self.assertEqual(3, perf.status(rows))

    def test_thirty_actions_come_from_six_blocks_of_five(self):
        rows, _, _ = self.summary(everything())
        action = next(row for row in rows if row[0] == "Action player_open, p95 ms")
        self.assertEqual(30, action[1])

    def test_missed_frames_are_weighted_by_frames_and_held_to_the_refresh_rate(self):
        benchmarks = [item for item in everything() if item["name"] != "homeScroll"]
        # 100 frames with 10% missed and 900 with none: 1% of all frames.
        benchmarks.append(bench("homeScroll", {"deadlineFrameCount": [100.0, 900.0, 0.0, 0.0, 0.0], "missedDeadlinePercent": [10.0, 0.0, 0.0, 0.0, 0.0]}))
        rows, _, _ = self.summary(benchmarks)
        home = next(row for row in rows if "homeScroll" in row[0])
        self.assertAlmostEqual(1.0, home[3])
        self.assertEqual("PASS", home[5])

        def verdict(missed, tier, hz):
            rows, _, _ = self.summary(everything(missed=missed), tier=tier, hz=hz)
            return next(row for row in rows if "homeScroll" in row[0])[5]
        self.assertEqual("FAIL", verdict(2.5, "R", 60.0))
        self.assertEqual("PASS", verdict(2.5, "L", 60.0))
        self.assertEqual("PASS", verdict(2.5, "R", 120.0))
        self.assertEqual("FAIL", verdict(3.5, "L", 120.0))

    def test_four_loops_of_frames_are_insufficient(self):
        rows, _, _ = self.summary(everything(loops=4))
        self.assertEqual("INSUFFICIENT (4 of 5)", next(row for row in rows if "homeScroll" in row[0])[5])

    def test_results_from_several_files_are_joined(self):
        first = self.results(everything()[:3], name="a.json")
        second = self.results(everything()[3:], name="b.json")
        results, devices = perf.load([first, second])
        rows, _ = perf.summarize(results, devices, "R", 60.0)
        self.assertEqual(0, perf.status(rows))

    def test_one_emulator_file_spoils_the_set(self):
        first = self.results(everything()[:3], name="a.json")
        second = self.results(everything()[3:], build=EMULATOR, name="b.json")
        results, devices = perf.load([first, second])
        self.assertEqual(3, perf.status(perf.summarize(results, devices, "R", 60.0)[0]))

    def test_main_prints_the_table_and_returns_the_status(self):
        self.assertEqual(0, perf.main(["summary", "--tier", "R", self.results(everything())]))
        self.assertEqual(2, perf.main(["summary", "--tier", "R", self.results(everything(queue=2500.0))]))


class ApkTest(Files):
    PROFILE = b"pro\x00010\x00" + b"x" * 64

    def apk(self, entries, pad=0):
        path = Path(self.dir.name) / "app.apk"
        with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
            if pad:
                archive.writestr("lib/arm64-v8a/libpad.so", b"\0" * pad)
        return path

    def good(self, **more):
        return {"AndroidManifest.xml": b"manifest", "classes.dex": b"dex com.musicglass.personal.MainActivity",
                "assets/dexopt/baseline.prof": self.PROFILE, "assets/dexopt/baseline.profm": b"prm\x00", **more}

    def failed(self, rows):
        return [name for name, ok, _ in rows if not ok]

    def test_a_release_with_its_profile_and_no_feed_passes(self):
        self.assertEqual([], self.failed(perf.check_apk(self.apk(self.good()))))

    def test_a_missing_profile_fails(self):
        entries = self.good()
        del entries["assets/dexopt/baseline.prof"]
        self.assertEqual(["Baseline Profile packaged"], self.failed(perf.check_apk(self.apk(entries))))

    def test_the_feed_in_a_release_fails_whichever_part_leaked(self):
        for entries in (self.good(**{"classes2.dex": b"Lcom/musicglass/personal/BenchmarkFeed;"}),
                        self.good(**{"AndroidManifest.xml": "com.musicglass.personal.benchmark-feed".encode("utf-16-le")}),
                        self.good(**{"assets/home.json": b"{}"})):
            self.assertEqual(["benchmark fixture feed absent"], self.failed(perf.check_apk(self.apk(entries))))

    def test_an_apk_over_forty_mebibytes_fails(self):
        self.assertEqual(["size at most 40 MiB"], self.failed(perf.check_apk(self.apk(self.good(), pad=perf.APK_LIMIT_BYTES))))

    def test_the_committed_profile_must_cover_the_journeys(self):
        path = Path(self.dir.name) / "baseline-prof.txt"
        self.assertEqual(["Baseline Profile committed as source"], self.failed(perf.check_profile_source(path)))
        rules = [f"HSPLcom/musicglass/personal/Filler{n};->a()V" for n in range(perf.PROFILE_MIN_RULES)]
        path.write_text("\n".join(rules + list(perf.PROFILE_CLASSES)), encoding="utf-8")
        self.assertEqual([], self.failed(perf.check_profile_source(path)))
        path.write_text("\n".join(rules + list(perf.PROFILE_CLASSES[:-1])), encoding="utf-8")
        self.assertEqual(["profile covers shell, collection, player and queue"], self.failed(perf.check_profile_source(path)))
        path.write_text("\n".join(perf.PROFILE_CLASSES), encoding="utf-8")
        self.assertEqual(["Baseline Profile committed as source"], self.failed(perf.check_profile_source(path)))

    def test_adopting_a_collected_profile_drops_the_feed_rules_and_nothing_else(self):
        collected = Path(self.dir.name) / "pulled.txt"
        out = Path(self.dir.name) / "src" / "baseline-prof.txt"
        app = [f"HSPLcom/musicglass/personal/Filler{n};->a()V" for n in range(perf.PROFILE_MIN_RULES)]
        feed = ["Lcom/musicglass/personal/BenchmarkFeed;", "PLcom/musicglass/personal/BenchmarkFeedKt;->toneWav(II)[B",
                "HSPLcom/musicglass/personal/ToneServer;->answer(Ljava/net/Socket;)V",
                "PLcom/musicglass/personal/BenchmarkFeed;->a(Lcom/musicglass/personal/ToneServer;)V"]
        collected.write_text("\n".join(app[:10] + feed + app[10:]) + "\n\n", encoding="utf-8")
        self.assertEqual((len(app) + len(feed), len(app)), perf.adopt_profile(collected, out))
        self.assertEqual(app, out.read_text(encoding="utf-8").splitlines())
        collected.write_text("\n".join(feed), encoding="utf-8")
        with self.assertRaises(perf.PerformanceError):
            perf.adopt_profile(collected, out)

    def test_main_returns_two_for_a_bad_apk(self):
        entries = self.good()
        del entries["assets/dexopt/baseline.profm"]
        self.assertEqual(2, perf.main(["apk", "--apk", str(self.apk(entries))]))
        self.assertEqual(0, perf.main(["apk", "--apk", str(self.apk(self.good()))]))


if __name__ == "__main__":
    unittest.main()
