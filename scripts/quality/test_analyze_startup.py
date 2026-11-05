import unittest

from analyze_startup import analyze


def event(stage, at, pid=10, epoch=1, lookup=1, phase='mark', **fields):
    values = dict(index=0, lookup=lookup, pid=pid, epoch=epoch, stage=stage,
                  kind='source', phase=phase, at=at, **fields)
    return 'startup ' + ' '.join(f'{k}={v}' for k, v in values.items())


class StartupTimelineTest(unittest.TestCase):
    def test_controller_wait_and_solver_spans_use_monotonic_window(self):
        result = analyze('\n'.join([
            event('warmup', 50, phase='begin'),
            event('minitap', 100),
            event('dispatch', 2100),
            event('ciphercall', 2200, pid=20, lookup=1, phase='begin'),
            event('warmup', 2300, phase='end'),
            event('ciphercall', 2500, pid=20, lookup=1, phase='end'),
            event('audioadvancing', 3100),
        ]))
        run = result['run']
        self.assertTrue(result['valid_single_tap'])
        self.assertEqual(2, run['tap_to_dispatch_ms'])
        self.assertEqual(3, run['tap_to_audio_callback_ms'])
        self.assertEqual(2, len(run['overlapping_spans']))
        self.assertEqual({10, 20}, {s['pid'] for s in result['completed_spans']})

    def test_ui_playing_never_supplies_missing_retained_track_callback(self):
        result = analyze(event('minitap', 100) + '\n' + event('playing', 200))
        self.assertIsNone(result['run']['tap_to_audio_callback_ms'])
        self.assertEqual('unavailable', result['run']['output_endpoint'])

    def test_new_process_cannot_complete_old_process_span_or_output(self):
        result = analyze('\n'.join([
            event('minitap', 100),
            event('httpcall', 110, phase='begin'),
            event('httpcall', 200, epoch=2, phase='end'),
            event('audioadvancing', 300, epoch=2),
        ]))
        self.assertEqual([], result['completed_spans'])
        self.assertEqual(1, len(result['run']['pending_spans']))
        self.assertEqual('unavailable', result['run']['output_endpoint'])

    def test_ambiguous_multiple_taps_rejected(self):
        result = analyze(event('minitap', 100) + '\n' + event('tap', 200))
        self.assertFalse(result['valid_single_tap'])
        self.assertIsNone(result['run'])

    def test_redacted_malformed_event_does_not_break_analysis(self):
        result = analyze(event('minitap', '<redacted>') + '\n' + event('minitap', 100))
        self.assertEqual(1, result['malformed_lines'])
        self.assertTrue(result['valid_single_tap'])

    def test_explicit_pid_works_without_logcat_prefix_and_state_survives(self):
        result = analyze(event('minitap', 100) + '\n' + event(
            'entrystate', 200, state=3, buffered_ms=5000, suppression=0, source='unknown'))
        self.assertEqual(3, result['run']['timeline'][-1]['state'])
        self.assertEqual('unknown', result['run']['timeline'][-1]['source'])


if __name__ == '__main__':
    unittest.main()
