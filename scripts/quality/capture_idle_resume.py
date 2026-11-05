"""Controlled single-tap physical baseline. Uses the unchanged diagnostic APK.

Requires the compiled IdleBaselineControl helper at /data/local/tmp/riff-idle-control.dex.
Never clears data/logs, changes settings, downloads media or replaces a queue.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

from analyze_startup import analyze

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.musicglass.personal.debug'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', choices=['warm', 'idle-immediate', 'idle-settled', 'cold-restored'], required=True)
    parser.add_argument('--repetitions', type=int, default=1, choices=range(1, 6))
    parser.add_argument('--idle-seconds', type=int, default=660)
    parser.add_argument('--capture-seconds', type=int, default=30)
    parser.add_argument('--cold-tap-mode', choices=['cached', 'observed'], default='cached')
    parser.add_argument('--trace-files', action='store_true', help='Read only the opt-in sanitized startup trace files')
    args = parser.parse_args()
    if args.case.startswith('idle-') and not 660 <= args.idle_seconds <= 900:
        parser.error('Real idle must be 660–900 seconds')
    if not 10 <= args.capture_seconds <= 60:
        parser.error('Capture must last 10–60 seconds')
    adb = [str(ROOT / '.toolchain/sdk/platform-tools/adb.exe'), '-s', args.serial]

    def command(*parts, check=True):
        result = subprocess.run(adb + list(parts), capture_output=True, text=True, errors='replace', timeout=30)
        if check and result.returncode:
            raise RuntimeError('ADB command failed: ' + parts[0])
        return result.stdout

    def control(action, *values):
        text = command('shell', 'CLASSPATH=/data/local/tmp/riff-idle-control.dex app_process /system/bin IdleBaselineControl '
                       + ' '.join([action] + [str(v) for v in values]))
        marker = 'RIFF_BASELINE_DEVICE ' if action == 'device' else 'RIFF_BASELINE_STATE '
        for line in text.splitlines():
            if line.startswith(marker):
                return json.loads(line[len(marker):])
        raise RuntimeError('No numeric helper state returned')

    def snapshot():
        thermal = command('shell', 'dumpsys', 'thermalservice')
        status = re.search(r'Thermal Status: (\d+)', thermal)
        return {
            'main_pid': command('shell', 'pidof', PACKAGE, check=False).strip() or None,
            'solver_pid': command('shell', 'pidof', PACKAGE + ':solver', check=False).strip() or None,
            'thermal_status': int(status.group(1)) if status else None,
            'device': control('device'),
        }

    def play_bounds():
        command('shell', 'uiautomator', 'dump', '/data/local/tmp/riff-idle-ui.xml')
        root = ET.fromstring(command('exec-out', 'cat', '/data/local/tmp/riff-idle-ui.xml'))
        for node in root.iter('node'):
            if node.get('resource-id', '').split('/')[-1] == 'mini_play' and node.get('enabled') == 'true':
                bounds = [int(v) for v in re.findall(r'\d+', node.get('bounds', ''))]
                if len(bounds) == 4:
                    return [(bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2]
        raise RuntimeError('Visible mini-player required; unlock phone and show Riff Debug')

    def write(path, data):
        path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')

    args.output.mkdir(parents=True, exist_ok=True)
    reference_path = args.output / 'reference.json'
    if reference_path.exists():
        reference = json.loads(reference_path.read_text(encoding='utf-8'))
    else:
        reference = control('state')
        if reference['state'] != 2 or reference['queue_size'] < 2:
            raise RuntimeError('A paused multi-song debug queue is required')
        write(reference_path, reference)
    for repetition in range(1, args.repetitions + 1):
        run_dir = args.output / (args.case + '-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
        run_dir.mkdir(exist_ok=False)
        before = control('state')
        allowed_states = {0, 1, 2} if args.case == 'cold-restored' else {2}
        if before['state'] not in allowed_states or before['error'] or before['queue_size'] != reference['queue_size'] or before['queue_index'] != reference['queue_index']:
            raise RuntimeError('Paused queue/index changed; no sample taken')
        if abs(before['position_ms'] - reference['position_ms']) > 5:
            raise RuntimeError('Paused position changed; no sample taken')
        if before['volume_index'] != reference['volume_index']:
            raise RuntimeError('Output volume changed; no sample taken')
        if before['locked'] or not before['interactive']:
            raise RuntimeError('Unlock and wake the phone before starting this sample')
        if before['duration_ms'] > 0 and before['position_ms'] + args.capture_seconds * 1000 + 5000 >= before['duration_ms']:
            raise RuntimeError('Too close to track end for a comparable sample')
        tap = play_bounds()
        metadata = {'case': args.case, 'reference': reference, 'before': before, 'conditions_before': snapshot(),
                    'capture_seconds': args.capture_seconds, 'account': 'owner_confirmed_signed_in',
                    'source': 'owner_confirmed_streamed', 'byte_cache_status': 'not_measured',
                    'quality_network_route': 'owner_confirmed_unchanged', 'status': 'capturing'}
        write(run_dir / 'metadata.json', metadata)
        boot_us = int(float(command('shell', 'cat', '/proc/uptime').split()[0]) * 1_000_000)
        log_path = run_dir / 'startup.log'
        process = subprocess.Popen(adb + ['logcat', '-v', 'threadtime', '-T', '1', '-s', 'RiffStartup:I', '*:S'],
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors='replace')
        ready = threading.Event()
        ready_marker = 'riff_capture_ready=' + str(time.monotonic_ns())

        def collect():
            with log_path.open('w', encoding='utf-8') as output:
                for line in process.stdout:
                    if ready_marker in line:
                        ready.set()
                    at = re.search(r'\bat=(\d+)', line)
                    if 'startup index=' in line and at and int(at.group(1)) >= boot_us:
                        output.write(line)
                        output.flush()

        reader = threading.Thread(target=collect, daemon=True)
        reader.start()
        try:
            # A spawned adb process is not necessarily attached to logcat yet.
            # Confirm delivery before the single tap, so its first marker cannot be lost.
            for attempt in range(20):
                command('shell', 'log', '-p', 'i', '-t', 'RiffStartup', ready_marker)
                if ready.wait(0.25):
                    break
            if not ready.is_set():
                raise RuntimeError('Logcat readiness handshake failed; no sample tapped')
            if args.case == 'cold-restored':
                # Deliberate cold control, never classified as natural idle reclamation.
                # Saved queue/data are retained; only the side-by-side debug package is stopped.
                metadata['deliberate_force_stop'] = True
                command('shell', 'am', 'force-stop', '--user', '0', PACKAGE)
                time.sleep(1)
                command('shell', 'am', 'start', '-W', '--user', '0', '-n', PACKAGE + '/com.musicglass.personal.MainActivity')
                metadata['cold_tap_mode'] = args.cold_tap_mode
                if args.cold_tap_mode == 'observed':
                    deadline = time.monotonic() + 20
                    while True:
                        try:
                            tap = play_bounds()
                            break
                        except RuntimeError:
                            if time.monotonic() >= deadline:
                                raise RuntimeError('Restored mini-player did not become visible; no tap')
                            time.sleep(0.2)
                    metadata['cold_control_note'] = 'Tap follows observed mini-player availability; UI dump adds delay. Not an immediate-foreground natural-idle sample.'
                else:
                    metadata['cold_control_note'] = 'One cached-bounds tap immediately after activity-start wait; accepted only if exactly one actual mini-player marker is recorded. No automatic retap.'
            elif args.case.startswith('idle-'):
                command('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                idle_began = time.monotonic()
                print(json.dumps({'event': 'idle_started', 'case': args.case, 'seconds': args.idle_seconds}), flush=True)
                while time.monotonic() - idle_began < args.idle_seconds:
                    time.sleep(min(30, args.idle_seconds - (time.monotonic() - idle_began)))
                metadata['actual_idle_seconds'] = time.monotonic() - idle_began
                metadata['conditions_after_idle'] = snapshot()
                device = metadata['conditions_after_idle']['device']
                if device['locked'] or not device['interactive']:
                    print(json.dumps({'event': 'owner_unlock_required', 'case': args.case,
                                      'instruction': 'Unlock to Home without opening Riff Debug; foreground/tap will follow automatically'}), flush=True)
                    while device['locked'] or not device['interactive']:
                        if time.monotonic() - idle_began >= 895:
                            raise RuntimeError('Owner unlock not available within the 15-minute idle window; no tap')
                        time.sleep(2)
                        device = control('device')
                    metadata['device_after_owner_unlock'] = device
                    metadata['actual_idle_seconds'] = time.monotonic() - idle_began
                open_boot_us = int(float(command('shell', 'cat', '/proc/uptime').split()[0]) * 1_000_000)
                command('shell', 'am', 'start', '-W', '--user', '0', '-n', PACKAGE + '/com.musicglass.personal.MainActivity')
                if args.case == 'idle-settled':
                    deadline = time.monotonic() + 60
                    while True:
                        completed = []
                        for line in log_path.read_text(encoding='utf-8').splitlines():
                            fields = dict(re.findall(r'(\w+)=([^\s]+)', line))
                            if fields.get('stage') == 'warmup' and fields.get('phase') == 'end' and int(fields.get('at', '0')) >= open_boot_us:
                                completed.append(fields)
                        if completed:
                            if completed[-1].get('result') != 'ok':
                                raise RuntimeError('Foreground warm-up failed; settled sample not tapped')
                            metadata['warmup_completed_at_us'] = int(completed[-1]['at'])
                            break
                        if time.monotonic() >= deadline:
                            raise RuntimeError('No foreground warm-up completion observed; settled sample not tapped')
                        time.sleep(0.2)
            command('shell', 'input', 'tap', str(tap[0]), str(tap[1]))
            print(json.dumps({'event': 'mini_play_tapped', 'case': args.case, 'sample': run_dir.name}), flush=True)
            time.sleep(args.capture_seconds)
            metadata['after'] = control('state')
            metadata['status'] = 'captured'
        except Exception as error:
            metadata['status'] = 'incomplete'
            metadata['failure'] = str(error)
            raise
        finally:
            process.terminate()
            process.wait(timeout=10)
            reader.join(timeout=5)
            if args.trace_files:
                # Dedicated diagnostic files only: never read account/preferences/queue files.
                original = log_path.read_text(encoding='utf-8')
                (run_dir / 'startup-logcat.log').write_text(original, encoding='utf-8')
                lines = original.splitlines()
                trace_counts = {}
                dropped = False
                for role in ('main', 'solver', 'other'):
                    trace = command('exec-out', 'run-as', PACKAGE, 'cat', 'cache/startup-trace/' + role + '.log', check=False)
                    relevant = []
                    for line in trace.splitlines():
                        if line.startswith('startup_trace_dropped '):
                            dropped = True
                        fields = dict(re.findall(r'(\w+)=([^\s]+)', line))
                        if line.startswith('startup index=') and fields.get('at', '').isdigit() and int(fields['at']) >= boot_us:
                            relevant.append(line)
                    (run_dir / ('trace-' + role + '.log')).write_text('\n'.join(relevant) + '\n', encoding='utf-8')
                    trace_counts[role] = len(relevant)
                    lines.extend(relevant)
                unique = {}
                for line in lines:
                    if 'startup index=' not in line:
                        continue
                    fields = dict(re.findall(r'(\w+)=([^\s]+)', line))
                    if not fields.get('at', '').isdigit():
                        continue
                    key = tuple(fields.get(k) for k in ('pid', 'epoch', 'lookup', 'stage', 'phase', 'at'))
                    unique[key] = line[line.index('startup '):]
                ordered = sorted(unique.values(), key=lambda line: int(dict(re.findall(r'(\w+)=([^\s]+)', line))['at']))
                log_path.write_text('\n'.join(ordered) + '\n', encoding='utf-8')
                metadata['trace_file_event_counts'] = trace_counts
                metadata['trace_file_dropped_events'] = dropped
            result = analyze(log_path.read_text(encoding='utf-8'))
            write(run_dir / 'analysis.json', result)
            metadata['valid_single_tap'] = result['valid_single_tap']
            if metadata['status'] == 'captured' and not result['valid_single_tap']:
                metadata['status'] = 'invalid_capture'
            write(run_dir / 'metadata.json', metadata)
        # Restoration is explicitly outside the single-tap capture window.
        paused = control('pause')
        if paused['queue_size'] != reference['queue_size'] or paused['queue_index'] != reference['queue_index']:
            raise RuntimeError('Queue/index changed; refusing to seek a different entry')
        restored = control('seek', reference['position_ms'])
        metadata['restored'] = restored
        metadata['conditions_after'] = snapshot()
        write(run_dir / 'metadata.json', metadata)
        if abs(restored['position_ms'] - reference['position_ms']) > 5:
            raise RuntimeError('Position restoration could not be verified')
        print(json.dumps({'event': 'sample_complete', 'case': args.case, 'sample': run_dir.name,
                          'valid_single_tap': result['valid_single_tap'],
                          'output_endpoint': result['run']['output_endpoint'] if result['run'] else None,
                          'tap_to_dispatch_ms': result['run']['tap_to_dispatch_ms'] if result['run'] else None,
                          'tap_to_audio_callback_ms': result['run']['tap_to_audio_callback_ms'] if result['run'] else None}), flush=True)
        if not result['valid_single_tap']:
            raise RuntimeError('Capture did not contain exactly one tap; stopping')


if __name__ == '__main__':
    main()
