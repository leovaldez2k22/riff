#!/usr/bin/env bash
# Puts back the app state that random stress input changed, from the pre-test run-as backup.
# Requires the debuggable stress build to be installed (run-as). Restores settings, search history,
# local saves, last-played queue, listening history and the offline catalog database.
# Deliberately NOT restored: provider_session (current sign-in tokens), WebView/ML Kit data, caches.
set -euo pipefail
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="$ROOT/.toolchain/sdk/platform-tools/adb.exe"
PKG=com.musicglass.personal
BACKUP="$ROOT/artifacts/stress/app-data-backup-2026-10-02.tar"
WORK="$(mktemp -d)"
command -v cygpath >/dev/null && WORK="$(cygpath -m "$WORK")"  # adb.exe needs a Windows path
FILES=(
  shared_prefs/offline_settings.xml shared_prefs/accessibility_display.xml shared_prefs/search_history.xml
  shared_prefs/music_collections.xml shared_prefs/last_playback.xml shared_prefs/playback.xml
  shared_prefs/playback_events.xml shared_prefs/podcast_progress.xml
  databases/offline-music.db databases/offline-music.db-wal
)
tar -xf "$BACKUP" -C "$WORK" "${FILES[@]}"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell rm -rf /data/local/tmp/riff-restore
"$ADB" shell mkdir -p /data/local/tmp/riff-restore
for f in "${FILES[@]}"; do
  "$ADB" push "$WORK/$f" "/data/local/tmp/riff-restore/$(basename "$f")" >/dev/null
done
"$ADB" shell chmod 644 '/data/local/tmp/riff-restore/*'
"$ADB" shell run-as "$PKG" rm -f databases/offline-music.db-shm
for f in "${FILES[@]}"; do
  "$ADB" shell run-as "$PKG" cp "/data/local/tmp/riff-restore/$(basename "$f")" "$f"
done
"$ADB" shell rm -rf /data/local/tmp/riff-restore
for f in "${FILES[@]}"; do
  if [ "$("$ADB" exec-out run-as "$PKG" cat "$f" | md5sum | cut -d' ' -f1)" = "$(md5sum < "$WORK/$f" | cut -d' ' -f1)" ]; then
    echo "restored $f"
  else
    echo "MISMATCH $f" >&2; exit 1
  fi
done
rm -rf "$WORK"
