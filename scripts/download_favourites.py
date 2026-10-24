"""Downloads every song of the album or playlist open in Riff, one at a time, through the app's own
Download song action, from the bottom of the list up. A personal helper for driving your own phone over adb:
it is not part of any build or test lane.

    python scripts/download_favourites.py --serial <device> [--adb <path to adb>] [--work-dir <folder>]

The device is never chosen automatically. adb comes from --adb, else from the SDK that verify.py finds
(ANDROID_HOME, ANDROID_SDK_ROOT, sdk.dir in local.properties, then .toolchain/sdk). The UI dump and the progress
file go in --work-dir (default: riff-download-favourites in the system temp folder); a second run continues
where the first stopped.
"""
import argparse
import subprocess
import tempfile
import time
import os
import sys
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

# Set by configure() from the command line.
ADB = DEVICE = XML_LOCAL = PROGRESS_FILE = None


def configure(argv=None):
    global ADB, DEVICE, XML_LOCAL, PROGRESS_FILE
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="the device to drive (adb devices lists them)")
    parser.add_argument("--adb", help="path to adb; default: platform-tools of the SDK that verify.py finds")
    parser.add_argument("--work-dir", default=os.path.join(tempfile.gettempdir(), "riff-download-favourites"))
    args = parser.parse_args(argv)
    if args.adb:
        ADB = args.adb
    else:
        sys.path.insert(0, str(Path(__file__).resolve().parent / "quality"))
        import verify
        sdk = verify.find_sdk()
        found = verify.executable(sdk / "platform-tools" / "adb") if sdk else None
        if not found:
            parser.error("no adb found: pass --adb or set ANDROID_HOME")
        ADB = str(found)
    DEVICE = args.serial
    os.makedirs(args.work_dir, exist_ok=True)
    XML_LOCAL = os.path.join(args.work_dir, "dump.xml")
    PROGRESS_FILE = os.path.join(args.work_dir, "download_progress.json")

def safe_print(text):
    try:
        print(text, flush=True)
    except Exception:
        try:
            clean = text.encode('ascii', 'replace').decode('ascii')
            print(clean, flush=True)
        except Exception:
            pass

def adb_run(args):
    return subprocess.run([ADB, "-s", DEVICE] + args, capture_output=True, text=True)

def dump_ui():
    adb_run(["shell", "uiautomator", "dump", "--compressed", "/data/local/tmp/dump.xml"])
    adb_run(["pull", "/data/local/tmp/dump.xml", XML_LOCAL])
    if os.path.exists(XML_LOCAL):
        try:
            return ET.parse(XML_LOCAL).getroot()
        except Exception:
            return None
    return None

def get_visible_tracks(root=None):
    if root is None:
        root = dump_ui()
    if root is None:
        return {}
    tracks = {}
    for node in root.iter():
        rid = node.attrib.get("resource-id", "")
        if "album_track_" in rid:
            try:
                idx = int(rid.replace("album_track_", ""))
            except ValueError:
                continue
            b = node.attrib.get("bounds", "")
            m = re.findall(r"\d+", b)
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                texts = [n.attrib.get("text", "") for n in node.iter() if n.attrib.get("text")]
                title = texts[0] if texts else f"Track {idx}"
                artist = texts[1] if len(texts) > 1 else ""
                tracks[idx] = {
                    "title": title,
                    "artist": artist,
                    "bounds": (x1, y1, x2, y2),
                    "options_center": (959, (y1 + y2) // 2)
                }
    return tracks

def is_downloading():
    res = adb_run(["shell", "dumpsys", "activity", "services", "OfflineDownloadService"])
    return "OfflineDownloadService" in res.stdout

def wait_for_download_finish(max_timeout=75):
    t0 = time.time()
    saw_active = False
    for _ in range(int(max_timeout * 2)):
        time.sleep(0.5)
        active = is_downloading()
        if active:
            saw_active = True
        elif saw_active:
            return True, time.time() - t0
    return True, time.time() - t0

def scroll_towards(target_idx):
    for attempt in range(25):
        tracks = get_visible_tracks()
        if not tracks:
            adb_run(["shell", "input", "swipe", "540", "600", "540", "1400", "300"])
            time.sleep(0.5)
            continue
        
        min_idx = min(tracks.keys())
        max_idx = max(tracks.keys())
        
        if target_idx in tracks:
            tb = tracks[target_idx]["bounds"]
            if tb[1] < 120 and attempt < 3:
                adb_run(["shell", "input", "swipe", "540", "400", "540", "800", "250"])
                time.sleep(0.4)
                continue
            elif tb[3] > 2015 and attempt < 3:
                adb_run(["shell", "input", "swipe", "540", "1800", "540", "1400", "250"])
                time.sleep(0.4)
                continue
            return tracks[target_idx]
        
        if target_idx < min_idx:
            # Need smaller index -> drag finger down
            dist = min_idx - target_idx
            if dist > 15:
                adb_run(["shell", "input", "swipe", "540", "400", "540", "1900", "120"])
            else:
                adb_run(["shell", "input", "swipe", "540", "500", "540", "1400", "250"])
            time.sleep(0.5)
        elif target_idx > max_idx:
            # Need larger index -> drag finger up
            dist = target_idx - max_idx
            if dist > 15:
                adb_run(["shell", "input", "swipe", "540", "1900", "540", "400", "120"])
            else:
                adb_run(["shell", "input", "swipe", "540", "1600", "540", "600", "250"])
            time.sleep(0.5)
            
    return None

def download_track(target_idx, total=284):
    track_info = scroll_towards(target_idx)
    if not track_info:
        safe_print(f"Error: Could not scroll to track {target_idx}")
        return False, "", "", 0

    title = track_info["title"]
    artist = track_info["artist"]
    ox, oy = track_info["options_center"]
    
    for attempt in range(3):
        # Tap options button
        adb_run(["shell", "input", "tap", str(ox), str(oy)])
        time.sleep(0.4)
        
        root = dump_ui()
        download_bounds = None
        if root is not None:
            for node in root.iter():
                if node.attrib.get("text") == "Download song":
                    download_bounds = node.attrib.get("bounds")
                    break
        
        if download_bounds:
            m = re.findall(r"\d+", download_bounds)
            if len(m) == 4:
                cx = (int(m[0]) + int(m[2])) // 2
                cy = (int(m[1]) + int(m[3])) // 2
                adb_run(["shell", "input", "tap", str(cx), str(cy)])
                
                success, elapsed = wait_for_download_finish()
                safe_print(f"[{len(os.environ.get('DONE_COUNT', '')) + 1}/{total}] [#{target_idx}] Downloaded '{title}' - {artist} ({elapsed:.1f}s)")
                time.sleep(0.3)
                return True, title, artist, elapsed
        
        # If Download song not found, dismiss overlay and retry
        adb_run(["shell", "input", "keyevent", "4"])
        time.sleep(0.4)
        tracks = get_visible_tracks()
        if target_idx in tracks:
            track_info = tracks[target_idx]
            ox, oy = track_info["options_center"]

    safe_print(f"Failed to find 'Download song' for track {target_idx} after retries")
    return False, title, artist, 0

def main():
    TOTAL_TRACKS = 284
    START_INDEX = 283  # 283 down to 0
    
    downloaded = []
    if os.path.exists(PROGRESS_FILE):
        try:
            with open(PROGRESS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                downloaded = data.get("downloaded", [])
        except Exception:
            pass
            
    safe_print("Starting playlist download in reverse order (bottom to top)...")
    safe_print(f"Already completed: {len(downloaded)} / {TOTAL_TRACKS} songs.")
    
    for target in range(START_INDEX, -1, -1):
        if target in downloaded:
            continue
            
        success, title, artist, elapsed = download_track(target, TOTAL_TRACKS)
        if success:
            downloaded.append(target)
            with open(PROGRESS_FILE, "w", encoding="utf-8") as f:
                json.dump({
                    "downloaded": downloaded,
                    "count": len(downloaded),
                    "total": TOTAL_TRACKS,
                    "last_index": target,
                    "last_title": title,
                    "last_artist": artist,
                    "elapsed": elapsed,
                    "timestamp": time.time()
                }, f, ensure_ascii=False)
        else:
            safe_print(f"Warning: Track {target} download failed, continuing to next...")

    safe_print("All songs in playlist downloaded successfully!")

if __name__ == "__main__":
    configure()
    main()
