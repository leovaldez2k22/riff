"""Targeted stress scenarios for Riff over adb (no UI-layout dependence except nav_leak).

Usage:  python scripts/stress/scenarios.py [scenario ...]   (no args = all, in order)
Each scenario captures logcat, records crashes/ANRs/native crashes for the app process,
and writes artifacts/stress/scenarios/<name>/{logcat.txt,result.json}.
Every device change a scenario makes is undone in its finally block.
"""
import json, os, re, subprocess, sys, threading, time
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
ADB = os.environ.get('RIFF_ADB') or str(PROJECT / '.toolchain/sdk/platform-tools/adb.exe')  # RIFF_ADB: run from a worktree
PKG = 'com.musicglass.personal'
OUT = PROJECT / 'artifacts/stress/scenarios'


def sh(cmd, timeout=120):
    r = subprocess.run([ADB, 'shell', cmd], capture_output=True, text=True, timeout=timeout,
                       encoding='utf-8', errors='replace')
    return (r.stdout + r.stderr).strip()


def app_pid():
    return sh(f'pidof {PKG}').strip()


def session():
    """State, position (ms) and title of Riff's media session, or None if absent."""
    dump = sh('dumpsys media_session')
    i = dump.find(f'package={PKG}')
    if i < 0:
        return None
    block = dump[i:i + 3000]
    st = re.search(r'state=PlaybackState \{state=(\w+)\(\d+\), position=(-?\d+).*?updated=(\d+)', block)
    title = re.search(r'description=(.*)', block)
    if not st:
        return None
    return {'state': st.group(1), 'position': int(st.group(2)), 'updated': int(st.group(3)),
            'title': title.group(1).split(', ')[0].strip() if title else None}


def live_position(s):
    """Extrapolate position to now for PLAYING sessions (dumpsys position is as of `updated`)."""
    if not s:
        return None
    if s['state'] != 'PLAYING':
        return s['position']
    uptime_ms = int(float(sh('cat /proc/uptime').split()[0]) * 1000)
    return s['position'] + max(0, uptime_ms - s['updated'])


def wait_for(pred, timeout, step=1.0):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(step)
    return None


def riff_focused():
    return f'{PKG}/' in sh('dumpsys window | grep -m1 mCurrentFocus')


def riff_play():
    """Press play with Riff's own mini-player button, never `cmd media_session dispatch play`: when
    Riff isn't the media-button session that key goes to another app (it once started YouTube)."""
    if riff_session_playing():
        return True
    launch()
    if not riff_focused():
        return False
    xml = ''
    for _ in range(3):
        # exec-out, not shell: through `adb shell` this phone returns only "dumped to /dev/tty".
        xml = subprocess.run([ADB, 'exec-out', 'uiautomator', 'dump', '/dev/tty'], capture_output=True, text=True,
                             timeout=60, encoding='utf-8', errors='replace').stdout
        if '<hierarchy' in xml:
            break
        time.sleep(1)
    m = re.search(r'resource-id="[^"]*mini_play"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    a, b, c, d = map(int, m.groups())
    s = session()
    if s and s['state'] == 'PLAYING':
        return True
    sh(f'input tap {(a + c) // 2} {(b + d) // 2}')
    return True


def riff_session_playing():
    # Buffering counts: pressing the button then would pause it.
    s = session()
    return bool(s and s['state'] in ('PLAYING', 'BUFFERING', 'CONNECTING'))


def launch():
    sh(f'am start -W -n {PKG}/.MainActivity')
    time.sleep(2)


def start_service():
    # Behind the lock screen the activity never connects, so start the session service directly.
    sh(f'am start-service -n {PKG}/.MusicPlaybackService -a androidx.media3.session.MediaSessionService')
    return wait_for(session, 15, 1)


def ensure_playing(timeout=45):
    launch()
    if not session():
        start_service()
    if (s := session()) and s['state'] == 'PLAYING':
        return s
    # Re-send: right after a (re)start, Riff may not be the media-button session yet and the key is dropped.
    end = time.time() + timeout
    while time.time() < end:
        riff_play()
        # Re-resolving a stream after an error can take 10-20 s.
        if s := wait_for(lambda: (s := session()) and s['state'] == 'PLAYING' and s, 25, 2):
            return s
    return None


def java_heap_kb():
    m = re.search(r'Java Heap:\s+(\d+)', sh(f'dumpsys meminfo {PKG}'))
    return int(m.group(1)) if m else None


def force_gc():
    pid = app_pid()
    if pid:
        sh(f'run-as {PKG} kill -10 {pid}')  # SIGUSR1: ART forces a GC in debuggable apps
        time.sleep(3)


STRICT_RE = re.compile(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+\w\s+StrictMode\s*: StrictMode policy violation(?:; ~duration=(\d+) ms)?: android\.os\.strictmode\.(\w+)', re.M)
SKIPPED_RE = re.compile(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+\w\s+Choreographer\s*: Skipped (\d+) frames', re.M)
DAVEY_RE = re.compile(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+\w\s+(?:HWUI|OpenGLRenderer)\s*: Davey! duration=(\d+)ms', re.M)


def jank_breakdown(log, pids):
    """StrictMode violations by kind (count, total and max ms) plus frame stalls, for the given pids only.

    Logs from other apps on the phone are ignored. The StrictMode policy is only installed in the
    stress build (src/stress), so release builds report nothing here."""
    pids = {str(p) for p in pids if p}
    kinds = {}
    for pid, ms, kind in STRICT_RE.findall(log):
        if pid not in pids:
            continue
        k = kinds.setdefault(kind, {'count': 0, 'totalMs': 0, 'maxMs': 0})
        k['count'] += 1
        if ms:
            k['totalMs'] += int(ms); k['maxMs'] = max(k['maxMs'], int(ms))
    skipped = [int(n) for pid, n in SKIPPED_RE.findall(log) if pid in pids]
    davey = [int(ms) for pid, ms in DAVEY_RE.findall(log) if pid in pids]
    return {'strictByKind': kinds, 'skippedFrameEvents': len(skipped), 'maxSkippedFrames': max(skipped, default=0),
            'daveyCount': len(davey), 'maxDaveyMs': max(davey, default=0)}


class Capture:
    """Logcat capture plus crash/ANR accounting for one scenario."""
    def __init__(self, name):
        self.dir = OUT / name
        self.dir.mkdir(parents=True, exist_ok=True)
        subprocess.run([ADB, 'logcat', '-c'], capture_output=True)
        self.file = open(self.dir / 'logcat.txt', 'w', encoding='utf-8', errors='replace')
        self.proc = subprocess.Popen([ADB, 'logcat', '-v', 'threadtime', '-b', 'main,system,crash,events'],
                                     stdout=self.file, stderr=subprocess.STDOUT)

    def stop(self):
        time.sleep(2)
        self.proc.kill()
        self.file.close()
        log = (self.dir / 'logcat.txt').read_text(encoding='utf-8', errors='replace')
        return {
            'fatal': len(re.findall(r'FATAL EXCEPTION', log)),
            'fatalLines': re.findall(r'.*AndroidRuntime: (?:Process|java|kotlin|android)\S*.*', log)[:6],
            'anr': len(re.findall(rf'ANR in {re.escape(PKG)}', log)),
            'native': len(re.findall(r'Fatal signal', log)),
            'strictMode': len(re.findall(r'StrictMode policy violation|StrictMode: Tried to access', log)),
            '_log': log,
        }


def run(name, fn):
    print(f'== {name} ({time.strftime("%H:%M:%S")})', flush=True)
    cap = Capture(name)
    pid_before = app_pid()
    result = {'scenario': name, 'started': time.strftime('%H:%M:%S')}
    try:
        result.update(fn() or {})
    except Exception as e:  # scenario harness failure, not necessarily an app bug
        result['harnessError'] = repr(e)
    captured = cap.stop()
    log = captured.pop('_log')
    result.update(captured)
    result['pidBefore'], result['pidAfter'] = pid_before, app_pid()
    result['jank'] = jank_breakdown(log, {pid_before, result['pidAfter']})
    checks = result.get('checks', {})
    result['pass'] = (not result.get('harnessError') and result['fatal'] == 0 and result['anr'] == 0
                      and result['native'] == 0 and all(checks.values()))
    (cap.dir / 'result.json').write_text(json.dumps(result, indent=2))
    with open(OUT / 'summary.jsonl', 'a') as f:
        f.write(json.dumps(result) + '\n')
    print(json.dumps({k: result[k] for k in result if k not in ('fatalLines',)}), flush=True)
    return result


# ---------------------------------------------------------------- scenarios

def rapid_skip():
    start = ensure_playing()
    if not start:
        return {'checks': {'startedPlaying': False}}
    titles = set()
    for i in range(150):
        sh('cmd media_session dispatch next')
        time.sleep(0.25)
        if i % 15 == 0 and (s := session()):
            titles.add(s['title'])
    for _ in range(50):
        sh('cmd media_session dispatch previous')
        time.sleep(0.25)
    recovered = wait_for(lambda: (s := session()) and s['state'] == 'PLAYING' and s, 60, 2)
    return {'distinctTitlesSampled': len(titles), 'final': session(),
            'checks': {'startedPlaying': True, 'appAlive': bool(app_pid()), 'playingAfterStorm': bool(recovered)}}


def play_pause_storm():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    for _ in range(200):
        sh('cmd media_session dispatch play-pause')
        time.sleep(0.1)
    # 200 toggles is even: should end playing. Give it time to settle.
    settled = wait_for(lambda: (s := session()) and s['state'] in ('PLAYING', 'PAUSED') and s, 30, 2)
    riff_play()
    playing = wait_for(lambda: (s := session()) and s['state'] == 'PLAYING' and s, 45, 2)
    return {'settled': settled, 'checks': {'appAlive': bool(app_pid()), 'resumesAfterStorm': bool(playing)}}


def _restore_after_kill(paused):
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    time.sleep(8)
    if paused:
        sh('cmd media_session dispatch pause')
        time.sleep(7)  # periodic snapshot runs every 5 s; pause also saves
    before = session()
    before_pos = live_position(before)
    sh('input keyevent KEYCODE_HOME')
    time.sleep(2)
    pid = app_pid()
    sh(f'run-as {PKG} kill -9 {pid}')  # what a low-memory kill looks like to the app
    time.sleep(3)
    killed = not app_pid() or app_pid() != pid
    launch()
    if not wait_for(session, 8, 1):
        start_service()
    after = wait_for(lambda: session(), 30, 2)
    pos_diff = (after['position'] - before_pos) if after and before_pos is not None else None
    tolerance = 1500 if paused else 7000
    return {'before': before, 'after': after, 'positionDiffMs': pos_diff,
            'checks': {'processKilled': killed, 'sessionRestored': bool(after),
                       'sameSong': bool(after and before and after['title'] == before['title']),
                       'positionKept': pos_diff is not None and abs(pos_diff) <= tolerance,
                       'restoredPaused': bool(after and after['state'] in ('PAUSED', 'STOPPED', 'NONE'))}}


def restore_after_kill_paused():
    return _restore_after_kill(paused=True)


def restore_after_kill_playing():
    return _restore_after_kill(paused=False)


def config_churn():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    launch()
    force_gc()
    heap0 = java_heap_kb()
    pos0 = live_position(session())
    t0 = time.time()
    # Restore exactly what the phone had: this used to reset to the values of the 2026-10-02 night.
    before = {'rotation': sh('settings get system user_rotation'), 'autoRotate': sh('settings get system accelerometer_rotation'),
              'night': sh('cmd uimode night'),
              'font': sh('settings get system font_scale'), 'density': sh('wm density')}
    night_before = 'yes' if 'yes' in before['night'] else 'no' if 'no' in before['night'] else 'auto'
    density_override = re.search(r'Override density: (\d+)', before['density'])
    try:
        sh('settings put system accelerometer_rotation 0')  # user_rotation is ignored while auto-rotate is on
        for i in range(20):
            sh(f'settings put system user_rotation {1 if i % 2 == 0 else 0}')
            time.sleep(1.5)
        for mode in ('no', 'yes') * 3:
            sh(f'cmd uimode night {mode}')
            time.sleep(2)
        for scale in ('1.3', '1.0') * 2:
            sh(f'settings put system font_scale {scale}')
            time.sleep(2.5)
        for d in ('520', 'reset') * 2:
            sh(f'wm density {d}')
            time.sleep(3)
    finally:
        sh(f"settings put system user_rotation {before['rotation'] if before['rotation'].isdigit() else 0}")
        sh(f"settings put system accelerometer_rotation {before['autoRotate'] if before['autoRotate'] in ('0', '1') else 1}")
        sh(f'cmd uimode night {night_before}')
        sh(f"settings put system font_scale {before['font'] if re.fullmatch(r'[0-9.]+', before['font']) else '1.0'}")
        sh(f'wm density {density_override.group(1)}' if density_override else 'wm density reset')
    time.sleep(3)
    force_gc()
    heap1 = java_heap_kb()
    pos1 = live_position(session())
    advanced = pos1 is not None and pos0 is not None and pos1 - pos0 > (time.time() - t0) * 1000 * 0.5
    after = {'rotation': sh('settings get system user_rotation'), 'autoRotate': sh('settings get system accelerometer_rotation'),
             'night': sh('cmd uimode night'),
             'font': sh('settings get system font_scale'), 'density': sh('wm density')}
    return {'javaHeapKbBefore': heap0, 'javaHeapKbAfter': heap1, 'settingsBefore': before, 'settingsAfter': after,
            'checks': {'appAlive': bool(app_pid()), 'playbackContinued': advanced, 'settingsRestored': before == after}}


def network_flap():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    try:
        for _ in range(8):
            sh('cmd wifi set-wifi-enabled disabled'); sh('svc data disable')
            time.sleep(15)
            sh('cmd wifi set-wifi-enabled enabled'); sh('svc data enable')
            time.sleep(20)
        sh('cmd connectivity airplane-mode enable'); time.sleep(60)
        sh('cmd connectivity airplane-mode disable'); time.sleep(5)
    finally:
        sh('cmd connectivity airplane-mode disable')
        sh('cmd wifi set-wifi-enabled enabled'); sh('svc data enable')
    online = wait_for(lambda: 'Wifi is connected' in sh('cmd wifi status') or None, 60, 3)
    riff_play()
    playing = wait_for(lambda: (s := session()) and s['state'] == 'PLAYING' and s, 90, 3)
    if playing:
        p0 = live_position(playing); time.sleep(10); p1 = live_position(session())
    advancing = bool(playing) and p1 is not None and p1 - p0 > 5000
    return {'final': session(), 'checks': {'wifiBack': bool(online), 'appAlive': bool(app_pid()),
                                           'playbackRecovers': advancing}}


def network_handover():
    """Wi-Fi -> mobile -> Wi-Fi while playing: the client IP changes, which can invalidate SABR attestation."""
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    # (Used to send 10 'previous' media keys to step back from the end of a restored queue. Media keys
    # can reach another app; start this scenario on a song with Up next or on live radio instead.)
    samples = []
    try:
        sh('svc data enable')
        for _ in range(3):
            sh('cmd wifi set-wifi-enabled disabled'); time.sleep(30)
            samples.append(session() and session()['state'])
            sh('cmd wifi set-wifi-enabled enabled'); time.sleep(30)
            samples.append(session() and session()['state'])
    finally:
        sh('cmd wifi set-wifi-enabled enabled'); sh('svc data enable')
    wait_for(lambda: 'Wifi is connected' in sh('cmd wifi status') or None, 60, 3)
    playing = ensure_playing(60)
    p0 = live_position(playing) if playing else None
    time.sleep(10)
    p1 = live_position(session())
    return {'states': samples, 'final': session(),
            'checks': {'appAlive': bool(app_pid()), 'playbackRecovers': p0 is not None and p1 is not None and p1 - p0 > 5000}}


def trim_memory_background():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    sh('input keyevent KEYCODE_HOME'); time.sleep(2)
    for level in ('RUNNING_MODERATE', 'RUNNING_LOW', 'RUNNING_CRITICAL', 'UI_HIDDEN', 'BACKGROUND', 'MODERATE', 'COMPLETE'):
        sh(f'am send-trim-memory {PKG} {level}'); time.sleep(2)
    p0 = live_position(session()); time.sleep(10); p1 = live_position(session())
    launch()
    # No foreground check: from 00:48 the phone sat on its secure lock screen, which the run must not unlock.
    return {'checks': {'appAlive': bool(app_pid()), 'playingInBackground': p0 is not None and p1 is not None and p1 - p0 > 5000}}


def background_doze():
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    sh('input keyevent KEYCODE_HOME'); time.sleep(2)
    minutes = 10
    try:
        sh(f'dumpsys deviceidle whitelist -{PKG}')
        sh('dumpsys battery unplug')
        sh('dumpsys deviceidle force-idle')
        p0 = live_position(session()); t0 = time.time()
        samples = []
        for _ in range(minutes * 2):
            time.sleep(30)
            s = session(); samples.append(s and s['state'])
        p1 = live_position(session()); dt = (time.time() - t0) * 1000
        idle = sh('dumpsys deviceidle get deep')
    finally:
        sh('dumpsys deviceidle unforce'); sh('dumpsys battery reset')
        sh(f'dumpsys deviceidle whitelist +{PKG}')
    launch()
    return {'deepIdleState': idle, 'states': samples, 'advancedMs': (p1 - p0) if p0 is not None and p1 is not None else None,
            'wallMs': int(dt),
            # Position resets at each track change, so a queue that moves on can 'go backwards':
            # count it as playing if every 30 s sample was PLAYING, or the position kept up.
            'checks': {'appAlive': bool(app_pid()), 'kepPlayingThroughDoze': bool(samples) and all(x == 'PLAYING' for x in samples)
                       or (p0 is not None and p1 is not None and p1 - p0 > dt * 0.8)}}


def playback_soak(minutes=75):
    """Long background playback: memory trend of the app process and recovery from track/queue ends."""
    if not ensure_playing():
        return {'checks': {'startedPlaying': False}}
    force_gc()
    samples, restarts, not_playing = [], 0, 0
    for i in range(minutes):
        time.sleep(60)
        s = session()
        m = re.search(r'TOTAL PSS:\s+(\d+)', sh(f'dumpsys meminfo {PKG}'))
        samples.append({'min': i + 1, 'state': s and s['state'], 'pssKb': int(m.group(1)) if m else None})
        if not s or s['state'] != 'PLAYING':
            not_playing += 1
            if ensure_playing(30):
                restarts += 1
    force_gc()
    pss = [x['pssKb'] for x in samples if x['pssKb']]
    first, last = (sum(pss[:5]) / 5, sum(pss[-5:]) / 5) if len(pss) >= 10 else (None, None)
    growth = (last - first) if first else None
    return {'samples': samples, 'restarts': restarts, 'notPlayingSamples': not_playing,
            'pssFirst5AvgKb': first, 'pssLast5AvgKb': last, 'growthKb': growth,
            'checks': {'appAlive': bool(app_pid()), 'mostlyPlaying': not_playing <= minutes // 10,
                       'pssGrowthUnder50MB': growth is not None and growth < 50_000}}


def nav_leak():
    launch()
    sh('cmd media_session dispatch pause')
    sh(f'uiautomator dump /data/local/tmp/riff-ui.xml')
    xml = sh('cat /data/local/tmp/riff-ui.xml')
    tabs = {}
    for label in ('Home', 'Podcasts', 'Radio', 'Library'):
        # Bottom navigation labels: take the lowest node with this exact text.
        nodes = re.findall(rf'text="{label}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if nodes:
            x1, y1, x2, y2 = map(int, max(nodes, key=lambda n: int(n[1])))
            tabs[label] = ((x1 + x2) // 2, (y1 + y2) // 2)
    if len(tabs) < 4:
        return {'tabsFound': list(tabs), 'checks': {'foundNavigation': False}}
    force_gc(); heap0 = java_heap_kb()
    order = ['Home', 'Podcasts', 'Radio', 'Library']
    for i in range(120):
        x, y = tabs[order[i % 4]]
        sh(f'input tap {x} {y}')
        time.sleep(0.8)
    sh(f'input tap {tabs["Home"][0]} {tabs["Home"][1]}'); time.sleep(3)
    force_gc(); heap1 = java_heap_kb()
    gfx = sh(f'dumpsys gfxinfo {PKG} | grep -E "Total frames|Janky frames|90th|99th"')
    growth = (heap1 - heap0) if heap0 and heap1 else None
    return {'javaHeapKbBefore': heap0, 'javaHeapKbAfter': heap1, 'growthKb': growth, 'gfx': gfx,
            'checks': {'foundNavigation': True, 'appAlive': bool(app_pid()), 'heapGrowthUnder20MB': growth is not None and growth < 20_000}}


SCENARIOS = {f.__name__: f for f in [rapid_skip, play_pause_storm, restore_after_kill_paused, restore_after_kill_playing,
                                     config_churn, network_flap, network_handover, trim_memory_background, nav_leak, background_doze, playback_soak]}

if __name__ == '__main__':
    names = sys.argv[1:] or list(SCENARIOS)
    OUT.mkdir(parents=True, exist_ok=True)
    for n in names:
        run(n, SCENARIOS[n])
