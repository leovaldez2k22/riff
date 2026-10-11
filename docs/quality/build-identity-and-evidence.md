# Q01 build identity and evidence

## Candidate identity

The highest known distributed code in local tags, release docs and APK inventory is rc20/code 21. The first quality candidate is **0.10.0-rc1 / 22**. Remote release pages could not be fetched; no newer distributed build is known locally. Nothing has been published or installed.

Every variant packages assets/quality/build-identity.json and matching BuildConfig fields. About shows version/code and a short source ID; copied diagnostics include the full revision, source digest, dirty/local state, variant and code. Missing/failed Git becomes local-unknown with a null dirty flag, never clean.

SHA-256 covers sorted relative paths plus NUL-delimited raw contents of app/src, Gradle wrapper/catalog/configuration, app build configuration and R8 rules. Gradle and Python use the same framing. Dirty builds from one revision remain distinguishable. Tests are included because they define the tested source. Caches, outputs, signing files and personal paths are excluded. No wall-clock timestamp is compiled; evidence timestamps are separate. Byte-identical APK reproducibility has not been established.

Application ID, minimum API, entry points and signing configuration are unchanged. Later distributed candidates need a new code and rc suffix.

## Local checks

Configure JDK 21, SDK and Gradle cache as in baseline.md, then:

~~~powershell
python scripts/quality/collect_results.py --variant debug --offline
python scripts/quality/collect_results.py --variant release --offline
.\scripts\quality\collect-results.ps1 -Python C:\path\to\python.exe --variant debug --offline
python -m unittest discover -s scripts/quality -p 'test_*.py' -v
~~~

The runner executes tests and selected lint with task-scoped --rerun, then assembly, retaining the log. Evidence lives in artifacts/quality/<revision-or-local-id>-<state>-<source-digest>/<unique-run-id>/. Reused run IDs are refused. Failures and successful reruns remain separate. Embedded APK identity must match current source and reports must be fresh and structurally complete. APKs and reports are copied before later phases overwrite build outputs. Preserve entire runs locally.

manifest.json follows [evidence-schema.json](evidence-schema.json): source/version/toolchain, APK/certificate hashes, UTC interval, sanitized device tier/API/model, fixture/live mode, commands, report paths/hashes and gates. Unsigned APKs have a null certificate; signed APKs require apksigner verification. Serial/account IDs are not manifest fields. Raw device reports remain private.

Local success is PASS_LOCAL; absent instrumentation remains BLOCKED. Skipped local checks are BLOCKED. Exit codes alone, success-looking filenames, OK lines or INSTRUMENTATION_CODE:-1 cannot validate an incomplete run. Instrumentation requires structured start/completion pairs, consistent exact counts, no crash/failure/skip, final code and matching summary. App/test APKs must have identical metadata; both hashes are recorded.

Exit codes: 0 = requested local checks pass; 1 = failure/invalid evidence; 2 = explicitly skipped local checks. Collection never installs APKs.

## Tooling and limits

CI runs collector tests and release collection and uploads manifests on success/failure. build-release-candidate.ps1 retains certificate checks, uses the wrapper and collects evidence; -SkipChecks records blocked checks. verify-device.ps1 and run-ui-regression.ps1 require -AllowInstall; the UI runner validates pairing and structured output. Device scripts accept -Python and were syntax checked only. The existing UI suite is classified live, not synthetic. Never uninstall to bypass a certificate mismatch.

Q00 R8 8.13.19/Kotlin 2.4 warnings remain unresolved. Shrunk assembly is not runtime solver proof. Physical/live/certificate upgrade/assistive-technology gates remain blocked. Q01 is traceability, not release certification.

Implementation APIs checked against [AGP 8.13 BuildConfigField](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/variant/BuildConfigField). No toolchain dependency was added or upgraded.
