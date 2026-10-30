# Contributing to Riff

Bug reports, focused fixes, and feature suggestions are welcome. This is a personal project; responses and releases have no guaranteed schedule.

## Report a problem

Check existing issues, then use the [bug report form](https://github.com/leovaldez2k22/riff/issues/new/choose). Include the app version, Android version, phone model, reproduction steps, and expected versus actual behavior. For playback, include whether Data saver was enabled and whether changing songs or restarting helped. Settings → About → Copy playback diagnostics can help; inspect logs and screenshots before posting them publicly.

Do not post account cookies, credentials, signing keys, signed stream URLs, or personal account exports. Report security problems privately as described in [SECURITY](SECURITY.md).

## Submit a change

1. Fork the repository and create a focused branch from `main`.
2. Follow the existing Kotlin/Compose patterns. Use real provider data and clearly labeled unavailable states.
3. Run `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug` with JDK 21 and the SDK described in [README](README.md). On Windows use `.\gradlew.bat`.
4. Add meaningful tests for changed behavior and update relevant documentation. For playback or UI behavior, describe phone testing and its limits.
5. Open a pull request explaining the problem, resulting behavior, and validation. Keep unrelated refactors separate.

Never put signing credentials in CI or Git. Debug builds cannot upgrade a release signed with the maintainer's key. Integration tests requiring provider access or a phone are separate from the JVM/Compose CI checks.

## License

Contributions must be compatible with this project's GNU GPL v3 license. Include provenance and compatible notices for imported code, fonts, or artwork. Do not submit content you lack permission to redistribute.
