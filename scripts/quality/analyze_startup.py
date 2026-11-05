"""Analyze one deliberate Riff mini-player tap captured on one device during one boot.

Process-local index/lookup fields are not distributed request IDs. Spans across
processes are related only by this controlled capture's boot-relative time window.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

PID = re.compile(r'^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+')
SPAN_STAGES = {
    'queueencode', 'queuedecode', 'queueapply', 'resolve', 'flightwait',
    'warmup', 'tokenlock', 'javascript', 'webviewload', 'solverbind',
    'solverrpc', 'ciphercall', 'sourcegate', 'httpcall',
}
INTEGER_FIELDS = {
    'at', 'lookup', 'index', 'count', 'pid', 'epoch', 'state', 'buffered_ms',
    'ahead_ms', 'position_ms', 'queue_index', 'suppression',
}


def analyze(text: str) -> dict:
    events = []
    malformed = 0
    for line in text.splitlines():
        if 'startup index=' not in line:
            continue
        fields = dict(re.findall(r'(\w+)=([^\s]+)', line.split('startup ', 1)[1]))
        if not {'at', 'lookup', 'stage', 'phase'} <= fields.keys():
            malformed += 1
            continue
        try:
            for key in INTEGER_FIELDS & fields.keys():
                fields[key] = int(fields[key])
        except ValueError:
            malformed += 1
            continue
        match = PID.match(line)
        if 'pid' not in fields:
            fields['pid'] = int(match.group(1)) if match else None
        events.append(fields)
    events.sort(key=lambda e: e['at'])
    starts, spans = {}, []
    for event in events:
        # epoch is the logger incarnation's monotonic origin; handles reused PIDs.
        key = (event['pid'], event.get('epoch'), event['lookup'])
        if event['pid'] is None:
            continue
        if event['phase'] == 'begin' and event['stage'] in SPAN_STAGES:
            starts[key] = event
        elif event['phase'] == 'end' and key in starts:
            begin = starts.pop(key)
            spans.append({
                'pid': event['pid'], 'epoch': event.get('epoch'), 'span': event['lookup'],
                'stage': begin['stage'], 'kind': begin.get('kind'),
                'begin_us': begin['at'], 'end_us': event['at'],
                'duration_ms': (event['at'] - begin['at']) / 1000,
                'result': event.get('result'),
            })
    taps = [e for e in events if e['stage'] in {'tap', 'minitap'} and e['phase'] == 'mark']
    valid = len(taps) == 1 and taps[0]['pid'] is not None
    run = None
    if valid:
        tap = taps[0]
        after = [e for e in events if e['at'] >= tap['at']]
        same_process = [e for e in after if e['pid'] == tap['pid'] and e.get('epoch') == tap.get('epoch')]
        audio = next((e for e in same_process if e['stage'] == 'audioadvancing' and e['phase'] == 'mark'), None)
        dispatch = next((e for e in same_process if e['stage'] == 'dispatch' and e['phase'] == 'mark'), None)
        run = {
            'tap_pid': tap['pid'], 'tap_epoch': tap.get('epoch'), 'tap_us': tap['at'],
            'origin': tap['stage'],
            'tap_to_dispatch_ms': None if dispatch is None else (dispatch['at'] - tap['at']) / 1000,
            'tap_to_audio_callback_ms': None if audio is None else (audio['at'] - tap['at']) / 1000,
            'output_endpoint': 'native_callback_estimate' if audio else 'unavailable',
            'timeline': [dict(e, offset_ms=(e['at'] - tap['at']) / 1000) for e in events if e['phase'] == 'mark'],
            # Include warm-up already running at the tap; never sum overlapping spans.
            'overlapping_spans': [s for s in spans if s['end_us'] >= tap['at']],
            'pending_spans': list(starts.values()),
        }
    return {
        'event_count': len(events), 'malformed_lines': malformed,
        'valid_single_tap': valid, 'tap_count': len(taps), 'run': run,
        'completed_spans': spans,
        'notes': [
            'Capture one deliberate tap on one device in one boot; multiple taps are ambiguous and rejected.',
            'Cross-process attribution uses the capture time window, not index/lookup propagation.',
            'An absent AudioAdvancing callback is unavailable, never replaced by UI playing.',
            'AudioAdvancing is a native estimate, not acoustic or Bluetooth output latency.',
            'Span durations overlap and must not be summed; streaming HTTP may remain pending.',
            'Service foreground observations and missing processes do not establish a crash.',
        ],
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path)
    args = parser.parse_args()
    print(json.dumps(analyze(args.log.read_text(encoding='utf-8', errors='replace')), indent=2))
