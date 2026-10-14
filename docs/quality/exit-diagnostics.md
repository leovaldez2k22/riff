# Exit-reason diagnostics (Q15A)

The 200-line event ring records what Riff did. It does not record why a process ended. A crash, an ANR or a low-memory kill on someone's phone was invisible unless they caught it in logcat. Two records now cover that. Both stay on the phone until the user shares them.

## What is recorded

**Exits.** When the main process starts, a background thread reads Android's historical exit reasons (`ActivityManager.getHistoricalProcessExitReasons`, API 30+) for the app's processes: the player and screens (`main`) and the stream solver (`:solver`). It records those newer than the last one seen. Each record keeps:

| Field | Example | Note |
|---|---|---|
| process | `main`, `solver`, `other` | from the process name |
| reason | `crash`, `anr`, `low_memory`, `user_requested`, ... | the platform's code as a word; an unknown future code becomes `reason_<n>` |
| importance | `100`, `400` | how visible the process was when it ended |
| at | the start of the hour | not the exact time |
| code | `22` | the version code that was running; after an update, the exits read at the first start are filed under the version before it |

Never read: the platform's free-text description and its trace file (`getTraceInputStream`). The service's existing ring line for an exit already dropped the description (Q15).

**Errors.** Every process installs an uncaught-exception recorder when it starts. It keeps the class names of the exception and up to four of its causes, and up to six stack frames from the app's own package (class, method, line) from the deepest cause that has any. It never reads a message, so a URL, cookie, title or account detail in an exception message is never stored. It then hands the exception, unchanged, to the handler that was there before, which ends the process as before. Recording is one small file write. If that fails, the crash still goes on.

## Where it is kept

The records live in `no_backup/diagnostics/`:
- `exits.log`: the last 12 exits, written by the main process only.
- `crash-main.log` and `crash-solver.log`: each written only by that process, the last 4 kinds of error each.

The same error again raises its count instead of adding a line, so a crash loop cannot grow the files or push older kinds out. These files are apart from the event ring, so a ring flooded by a crash loop cannot evict them. A file is replaced whole: written to a temporary file, then renamed. On reading, any line not in the exact stored shape is dropped, and a file over 8 KiB reads as empty. The folder is outside backup, as all app data is (Q15).

## What the user sees

Settings → About → Diagnostics:

- **Last exits**, newest first, in words: "Stopped because of an error", "Stopped responding and was closed", "Closed by Android to free memory". Each row names the process ("Riff" or "Stream helper") and the hour. A stop from Android settings ("Stopped from Android settings"), a stop by the user, a normal close, an update, or a swipe from recent apps (which Android files under "other": "Closed by Android, or swiped away") is shown with a neutral mark, not as a failure. Failures are crash, native crash, ANR, low memory, excessive resource use, a failure to start and a dependency's death.
- **Errors**: the exception class, the process, how many times, the hour.
- **Share diagnostics report** opens the system share sheet with a plain-text report. The user picks where it goes; nothing is sent by the app and no network endpoint was added.
- **Copy playback diagnostics** copies the same report.
- **Clear exit records** removes the exit and error files.

## The report

Its content is fixed:
- the version, Android SDK and phone model on one cleaned line;
- the full build identity;
- the exit records;
- the error records with their app frames;
- the event ring.

Every line goes through the Q15 scrubber (`DiagnosticEvent.parse`) on the way out, whatever wrote it. Frames are kept only in the stored frame shape.

## Soak evidence

`scripts/stress/scenarios.py` reads the exit and error files with `run-as` on the debuggable stress build before and after each scenario, and puts the added records per process and reason in `result.json` (`exitRecords`). This is informational; the pass rule is unchanged. A dead process's exit is filed when the main process next starts, so a scenario that ends with the app still running shows its exits in the next scenario's numbers. On a release build the files cannot be read and the field is null.

## Evidence

`ExitDiagnosticsTest` (Robolectric):
- each reason class with its word, its failure or neutral standing and its plain text;
- only new exits, per process, with the version that ran, and a platform that refuses;
- an exception whose message and causes carry a signed URL, a cookie, a song title, an account scope and an e-mail address: none of it is stored;
- the previous handler receives the same thread and the same exception, also when the store cannot write, and a hundred recordings take well under a process's grace;
- a crash loop of 500 and 50 different kinds stays bounded and leaves the exits;
- lines of any other shape are dropped and an oversized file reads empty;
- the shared report holds none of the planted values (SEC03);
- the store is outside backup and apart from the ring.

The emulator check of DIAG01 is in the ledger.
