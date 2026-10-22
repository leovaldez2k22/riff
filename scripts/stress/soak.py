"""B9 live soak: watch one Riff build play for hours on one named device, over adb (USB or Wi-Fi).

Usage:  python scripts/stress/soak.py --serial SERIAL [--package com.musicglass.personal.preview]
                                       [--hours 4] [--limit-hours 5] [--no-interact] [--out DIR]

The music is started by hand first (the owner's rule for B9); this script only watches, and once an hour skips a
song and turns the screen off and on with key events (no taps, no screenshots, no unlocking). Set Riff's sleep timer
longer than --hours (250 minutes for 4 hours), or the timer's pause ends the run as a stall. It stops early when
playback stands still for --stall-seconds, when Riff's process changes (a crash), when the device stops answering
for --silence-seconds, or at --limit-hours. Every adb call names the device and has a time limit.

Writes OUT/samples.jsonl (one line a minute), OUT/crash.txt (Riff's crash-buffer lines) and OUT/summary.json.
Memory budget (performance.md): growth from hour 1 to the end within max(10 %, 20 MiB) of PSS.
"""
import argparse, json, os, re, subprocess, sys, time
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
# The owner's original app is never driven by a test (keep-original-riff-app).
ORIGINAL_APP = 'com.musicglass.personal'


def default_adb():
    sys.path.insert(0, str(PROJECT / 'scripts/quality'))
    import verify  # noqa: E402  (finds the SDK the same way every lane does, Q16)
    sdk = verify.find_sdk()
    found = verify.executable(sdk / 'platform-tools' / 'adb') if sdk else None
    return str(found) if found else 'adb'


class Device:
    def __init__(self, adb, serial):
        self.adb, self.serial = adb, serial

    def sh(self, cmd, timeout=60):
        """Output of `adb -s SERIAL shell cmd`, or None when the device did not answer in time."""
        try:
            r = subprocess.run([self.adb, '-s', self.serial, 'shell', cmd], capture_output=True, text=True,
                               timeout=timeout, encoding='utf-8', errors='replace')
        except subprocess.TimeoutExpired:
            return None
        if r.returncode != 0 and not r.stdout:
            return None
        return r.stdout


# ---------------------------------------------------------------- parsing (pure, tested in test_soak.py)

def parse_session(dump, package):
    """State, position, update time, title and queue size of the package's media session, or None."""
    i = (dump or '').find(f'package={package}\n')
    if i < 0:
        i = (dump or '').find(f'package={package}')
    if i < 0:
        return None
    block = dump[i:i + 4000]
    nxt = block.find('package=', len(f'package={package}'))
    if nxt > 0:
        block = block[:nxt]
    st = re.search(r'state=PlaybackState \{state=(\w+)\(\d+\), position=(-?\d+).*?updated=(\d+)', block)
    if not st:
        return None
    title = re.search(r'description=([^\n]*)', block)
    queue = re.search(r'queueTitle=[^\n]*?, size=(\d+)', block)
    return {'state': st.group(1), 'position': int(st.group(2)), 'updated': int(st.group(3)),
            'title': title.group(1).split(', ')[0].strip() if title else None,
            'queue': int(queue.group(1)) if queue else None}


def live_position(session, uptime_ms):
    """Position extrapolated to now while playing (dumpsys gives it as of `updated`)."""
    if session is None:
        return None
    if session['state'] != 'PLAYING':
        return session['position']
    return session['position'] + max(0, uptime_ms - session['updated'])


def parse_pss_kb(meminfo):
    m = re.search(r'TOTAL PSS:\s+(\d+)', meminfo or '') or re.search(r'^\s*TOTAL\s+(\d+)', meminfo or '', re.M)
    return int(m.group(1)) if m else None


def crash_lines(text, package, pid=None):
    """Crash-buffer lines that belong to the package: FATAL EXCEPTION blocks, ANRs, native crashes."""
    out = []
    for line in (text or '').splitlines():
        if package in line or (pid and re.search(rf'\b{pid}\b', line)):
            out.append(line)
    return out


def progressed(prev, cur, prev_pos, cur_pos):
    """True when playback moved since the previous sample: the position advanced or the song changed."""
    if prev is None or cur is None:
        return False
    if cur['title'] != prev['title']:
        return True
    return cur_pos is not None and prev_pos is not None and cur_pos > prev_pos + 1000


def memory_verdict(samples, hour_ms=3_600_000):
    """Growth from the first reading at or after hour 1 to the last reading, against max(10 %, 20 MiB)."""
    readings = [(s['t'], s['pss']) for s in samples if s.get('pss')]
    if not readings:
        return {'verdict': 'BLOCKED', 'reason': 'no memory readings'}
    start = readings[0][0]
    base = next(((t, p) for t, p in readings if t - start >= hour_ms), None)
    if base is None:
        return {'verdict': 'BLOCKED', 'reason': 'run shorter than an hour', 'first': readings[0][1],
                'last': readings[-1][1]}
    last = readings[-1]
    growth = last[1] - base[1]
    allowed = max(base[1] // 10, 20 * 1024)
    return {'verdict': 'PASS' if growth <= allowed else 'FAIL', 'hour1Kb': base[1], 'lastKb': last[1],
            'growthKb': growth, 'allowedKb': allowed, 'peakKb': max(p for _, p in readings)}


# ---------------------------------------------------------------- the run

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--serial', required=True, help='adb serial or ip:port; required so no other device is touched')
    ap.add_argument('--package', default='com.musicglass.personal.preview')
    ap.add_argument('--hours', type=float, default=4.0)
    ap.add_argument('--limit-hours', type=float, default=5.0)
    ap.add_argument('--sample-seconds', type=int, default=60)
    ap.add_argument('--memory-every', type=int, default=10, help='memory reading every N samples')
    ap.add_argument('--stall-seconds', type=int, default=300)
    ap.add_argument('--silence-seconds', type=int, default=900)
    ap.add_argument('--no-interact', action='store_true', help='skip the hourly skip and screen off/on')
    ap.add_argument('--out', default=str(PROJECT / 'artifacts/quality/q19/b9' / time.strftime('%Y%m%d-%H%M')))
    ap.add_argument('--adb', default=os.environ.get('RIFF_ADB'))
    a = ap.parse_args(argv)
    if a.package == ORIGINAL_APP:
        sys.exit('refused: the original Riff app is never soaked')
    dev = Device(a.adb or default_adb(), a.serial)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    log = open(out / 'samples.jsonl', 'a', encoding='utf-8')

    def uptime_ms():
        up = dev.sh('cat /proc/uptime', 20)
        return int(float(up.split()[0]) * 1000) if up else None

    pid0 = (dev.sh(f'pidof {a.package}', 20) or '').strip()
    first = parse_session(dev.sh('dumpsys media_session', 30), a.package)
    if not pid0 or not first or first['state'] != 'PLAYING':
        summary = {'verdict': 'BLOCKED', 'reason': 'start the music first: Riff is not playing', 'pid': pid0,
                   'session': first}
        (out / 'summary.json').write_text(json.dumps(summary, indent=2))
        print(json.dumps(summary))
        return 2
    crash_before = len(crash_lines(dev.sh('logcat -b crash -d', 60), a.package, pid0))
    print(f"{time.strftime('%H:%M:%S')} soak started: {a.package} pid {pid0}, '{first['title']}', queue {first['queue']}, "
          f"{a.hours} h (limit {a.limit_hours} h), a sample every {a.sample_seconds} s", flush=True)

    t0 = time.time()
    samples, events = [], []
    prev, prev_pos, last_move, last_answer = first, live_position(first, uptime_ms() or 0), t0, t0
    songs = {first['title']} if first['title'] else set()
    next_interaction = t0 + 3600
    stop = None
    n = 0
    while True:
        time.sleep(a.sample_seconds)
        n += 1
        now = time.time()
        el = int((now - t0) * 1000)
        pid = (dev.sh(f'pidof {a.package}', 20) or '').strip()
        dump = dev.sh('dumpsys media_session', 30)
        if dump is None:
            if now - last_answer > a.silence_seconds:
                stop = 'device silent'
                break
            continue
        last_answer = now
        cur = parse_session(dump, a.package)
        pos = live_position(cur, uptime_ms() or 0)
        sample = {'t': el, 'clock': time.strftime('%H:%M:%S'), 'pid': pid,
                  'state': cur and cur['state'], 'pos': pos, 'title': cur and cur['title'],
                  'queue': cur and cur['queue']}
        if n % a.memory_every == 0 or n == 1:
            sample['pss'] = parse_pss_kb(dev.sh(f'dumpsys meminfo {a.package}', 60))
        if progressed(prev, cur, prev_pos, pos):
            last_move = now
        if cur and cur['title']:
            songs.add(cur['title'])
        samples.append(sample)
        log.write(json.dumps(sample) + '\n')
        log.flush()
        if n % 5 == 0:
            print(f"{sample['clock']} {el // 60000} min {sample['state']} songs={len(songs)} pss={sample.get('pss')}",
                  flush=True)
        if pid != pid0:
            stop = f'process changed ({pid0} -> {pid or "gone"})'
            break
        if now - last_move > a.stall_seconds:
            stop = f'playback still for {a.stall_seconds} s'
            break
        if now - t0 >= a.hours * 3600:
            stop = 'done'
            break
        if now - t0 >= a.limit_hours * 3600:
            stop = 'time limit'
            break
        if not a.no_interact and now >= next_interaction:
            next_interaction += 3600
            dev.sh('input keyevent KEYCODE_MEDIA_NEXT', 20)
            dev.sh('input keyevent KEYCODE_WAKEUP', 20)
            time.sleep(5)
            dev.sh('input keyevent KEYCODE_SLEEP', 20)
            after = parse_session(dev.sh('dumpsys media_session', 30), a.package)
            # A media key can reach another app when Riff is not the media-button session; record where it went.
            events.append({'t': el, 'event': 'skip, screen on and off', 'from': cur and cur['title'],
                           'to': after and after['title'], 'state': after and after['state']})
            last_move = time.time()
        prev, prev_pos = cur, pos

    crash = crash_lines(dev.sh('logcat -b crash -d', 60), a.package, pid0)
    (out / 'crash.txt').write_text('\n'.join(crash[crash_before:]), encoding='utf-8')
    end_pss = parse_pss_kb(dev.sh(f'dumpsys meminfo {a.package}', 60))
    if end_pss:
        samples.append({'t': int((time.time() - t0) * 1000), 'pss': end_pss})
    memory = memory_verdict(samples)
    new_crashes = len(crash) - crash_before
    stable = stop == 'done' and new_crashes == 0
    verdict = ('BLOCKED' if stop in ('device silent', 'time limit') else
               'FAIL' if not stable or memory['verdict'] == 'FAIL' else memory['verdict'])
    summary = {'verdict': verdict, 'stop': stop, 'minutes': round((time.time() - t0) / 60, 1),
               'songs': len(songs), 'newCrashLines': new_crashes, 'memory': memory, 'events': events,
               'package': a.package, 'serial': a.serial}
    (out / 'summary.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    return 0 if verdict == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
