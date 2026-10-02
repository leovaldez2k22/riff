# Riff 0.9.0-rc12

Settings now presents account management and compact Interaction, Appearance, Downloads and Library cards. The feedback preview control and technical explanatory paragraphs were removed. A global Haptic feedback switch remains persistent and gates app touch feedback while respecting Android’s system setting. Frosted glass, glass intensity, reduced motion, high-contrast controls and listening history retain their existing preferences.

Smart Downloads gains a saved Allow mobile data switch, off by default. The default still requires validated unmetered Wi-Fi. Opting in permits validated cellular or metered connections. JobScheduler periodic and immediate work follow the selected network policy, and active automatic transfers recheck connectivity and battery at most once per second. Battery, playback priority, reserve-space, account and manual-download rules remain in place. Changing this preference does not enable Smart Downloads. Storage budget is a 1–16 GB capsule slider in 1 GB increments, saved when the gesture finishes; its default is 4 GB.

Library Downloads now follows the album layout: full-screen-width artwork fading into a matching background, centered title and storage summary, large Play button, Shuffle and Settings actions, filters, and compact numbered song rows. Menus expose details, retry, keep, pause and remove actions as applicable. Technical status and quality details are moved into a dialog. Playing a row retains the alphabetically sorted collection and selects that row; Play and Shuffle include only complete audio bundles.

The header shows the most recently downloaded song’s artwork. SQLite schema version 3 adds a separate download timestamp. Existing bundles migrate their original audio file commit dates. New audio commits save a fresh timestamp; listening to songs and retrying artwork or lyrics do not change it. Account changes refresh the collection and artwork.

Riff is the official product name in the launcher, app information, project, documentation prose, request agent and new APK/source archive names. Application ID `com.musicglass.personal`, signing identity, persisted preference keys and account-scope derivation remain stable so existing installations update with data intact. Historical artifact paths remain accurate records of previously issued files.

## Validation

The signed release build, release AndroidTest build, all 83 unit tests and lintRelease passed. Lint reports 0 errors and 58 warnings, including existing compatibility/style advisories. APK Signature Scheme v2 verifies with one signer. The APK declares launcher label Riff, version code 13 and minimum API 31 (Android 12). Physical checks used the iQOO I2217 on Android 16.

The existing Numb bundle survived the schema migration. Instrumentation confirmed a migrated download date, interrupted-job recovery, retained audio and an unchanged timestamp after an asset retry. The phone contains two complete bundles, Cinnamon Girl and Numb. The new hero displays Cinnamon Girl’s album artwork; compact rows, menu/details, saved artwork/lyrics and the empty Pending filter were verified visually. Settings cards and the existing 16 GB preference were inspected on device. Touch haptic disabling and the mobile network guard share the persistent preference path; automated tests cover mobile consent, metered Wi-Fi and network validity. Preference-changing physical checks await user approval after automatic approval review declined that test; user settings remain unchanged.

The final APK is installed and copied to `C:\Users\KIIT0001\Downloads\riff-0.9.0-rc12.apk`. Its SHA-256 is 8db236e74f3a2cc151b746ef5d64455804142f386ffbd4edb27e43fc81b3b031; the Downloads copy matches the release artifact. Corresponding source is packaged as `riff-0.9.0-rc12-source.zip`.

Library → Downloads was checked from the actual Library tab and kept Library selected. Restart restored Team at 40.171 seconds with the ten-song queue, paused and ready in the mini player. A final read-only preference snapshot matches the original values: Smart Downloads on, mobile data off, 16 GB budget and haptics on. No network settings or preference values were altered.

The separate test APK was uninstalled and the exact temporary phone XML/screenshot paths were removed. Riff and existing downloads were preserved. The phone was locked at handoff: display sleeping and keyguard showing=true.
