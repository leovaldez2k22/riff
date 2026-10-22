import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'stress'))
import soak  # noqa: E402

PKG = 'com.musicglass.personal.preview'
DUMP = """Sessions Stack - have 2 sessions:
  Original Riff
    package=com.musicglass.personal
      state=PlaybackState {state=PAUSED(2), position=1120, buffered position=0, speed=1.0, updated=11397083, actions=8615}
      metadata: size=16, description=Old song, Someone, null
      queueTitle=null, size=7
  Riff Preview
    package=com.musicglass.personal.preview
      state=PlaybackState {state=PLAYING(3), position=158571, buffered position=230501, speed=1.0, updated=13709588, actions=7340027}
      metadata: size=16, description=Summer, Calvin Harris, Pop Summer Hits 2024
      queueTitle=Pop Summer Hits 2024, size=100
"""


class SoakParsingTest(unittest.TestCase):
    def test_reads_only_the_named_packages_session(self):
        s = soak.parse_session(DUMP, PKG)
        self.assertEqual(('PLAYING', 158571, 13709588, 'Summer', 100),
                         (s['state'], s['position'], s['updated'], s['title'], s['queue']))
        original = soak.parse_session(DUMP, 'com.musicglass.personal')
        self.assertEqual(('PAUSED', 'Old song', 7), (original['state'], original['title'], original['queue']))
        self.assertIsNone(soak.parse_session(DUMP, 'com.example.absent'))

    def test_position_is_extrapolated_only_while_playing(self):
        s = soak.parse_session(DUMP, PKG)
        self.assertEqual(158571 + 5000, soak.live_position(s, 13709588 + 5000))
        self.assertEqual(1120, soak.live_position(soak.parse_session(DUMP, 'com.musicglass.personal'), 99_999_999))

    def test_progress_needs_a_moving_position_or_a_new_song(self):
        a = {'title': 'Summer'}
        self.assertTrue(soak.progressed(a, {'title': 'Summer'}, 10_000, 70_000))
        self.assertFalse(soak.progressed(a, {'title': 'Summer'}, 10_000, 10_500))
        self.assertTrue(soak.progressed(a, {'title': 'Girlfriend'}, 200_000, 1_000))
        self.assertFalse(soak.progressed(None, a, 0, 1))

    def test_pss_from_both_meminfo_layouts(self):
        self.assertEqual(221959, soak.parse_pss_kb('  App Summary\n ...\n           TOTAL PSS:   221959       TOTAL RSS:  300000'))
        self.assertEqual(123642, soak.parse_pss_kb('        TOTAL   123642    100000     5000'))
        self.assertIsNone(soak.parse_pss_kb('No process found for: x'))

    def test_crash_lines_keep_only_the_package(self):
        text = ('E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: com.musicglass.personal.preview, PID: 4242\n'
                'E AndroidRuntime: Process: com.other.app, PID: 77\n')
        self.assertEqual(1, len(soak.crash_lines(text, PKG)))
        self.assertEqual(1, len(soak.crash_lines('F libc: Fatal signal 11 in tid 4242\n', PKG, pid='4242')))

    def test_memory_budget_from_hour_one(self):
        hour = 3_600_000
        flat = [{'t': 0, 'pss': 100_000}, {'t': hour, 'pss': 200_000}, {'t': 4 * hour, 'pss': 215_000}]
        self.assertEqual('PASS', soak.memory_verdict(flat)['verdict'])  # 15 MB under max(20 MiB, 10 %)
        grown = [{'t': 0, 'pss': 100_000}, {'t': hour, 'pss': 300_000}, {'t': 4 * hour, 'pss': 340_000}]
        v = soak.memory_verdict(grown)
        self.assertEqual(('FAIL', 40_000, 30_000), (v['verdict'], v['growthKb'], v['allowedKb']))
        self.assertEqual('BLOCKED', soak.memory_verdict([{'t': 0, 'pss': 1}, {'t': 60_000, 'pss': 2}])['verdict'])
        self.assertEqual('BLOCKED', soak.memory_verdict([{'t': 0, 'clock': 'x'}])['verdict'])

    def test_the_original_app_is_refused(self):
        with self.assertRaises(SystemExit) as stop:
            soak.main(['--serial', 'x', '--package', 'com.musicglass.personal', '--adb', 'adb'])
        self.assertIn('never soaked', str(stop.exception))


if __name__ == '__main__':
    unittest.main()
