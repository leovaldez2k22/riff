# Owner's feature requests

Asked for on 19 October 2026, alongside the sign-in design (sign-in-design.md). Mockups were shown for F2 and F5; the
owner then asked for the two waves (not the record) on the lyric card, today's sliders kept unchanged in settings, and
the storage budget kept as a slider, and said to build all five. Built the same day; JVM tests only so far, not yet
checked on a phone or emulator.

| ID | Request | Notes from the code today | Status |
|---|---|---|---|
| F1 | Tapping a song to play it opens the full player automatically | Not yet read in the code; to check which screens start playback (lists, search, downloads, widgets, notifications) and which of them should open the player | Built 19 Oct 2026 (9c57bec): song taps in lists, collections, downloads, history and on-device open the player once that song plays (within 30 s); Play and Shuffle buttons do not |
| F2 | Share lyrics: long-press a line; while held the line slowly shrinks; when the long press registers it pops back to its size and the owner can select up to 5 lines in all, above or below it; the selection becomes an image with Riff's name and logo at the bottom right, to share | LyricsPanel.kt: a tap on a timed line seeks to it (kept); the long press is new and must not seek | Built 19 Oct 2026 (fa9c650): 650 ms hold; up to 5 lines; cover, dark and Riff pink looks; Save to Pictures/Riff and Share |
| F3 | After a song has played more than 10 seconds, Previous goes back to its start instead of the previous song | PlaybackGateway.previous and the service's "previous" command call seekToPreviousMediaItem, which always goes to the previous song | Built 19 Oct 2026 (21d9372); the notification, headset and widget Previous follow the same rule |
| F4 | Slider haptics. Volume boost (100 to 300 %): strongest at 100, 200, 300; lighter at 150, 250; lightest at 125, 175, 225, 275. Crossfade (1 to 12 s): a medium tick at each second. Glass and frosted glass intensity (0 to 150 %): a medium tick every 25 % | SettingsScreen.kt sliders use one shared SliderHaptics.Intensity today | Built 19 Oct 2026 (75da2ed) |
| F5 | Settings revamped into compartments that expand to show more options | SettingsScreen.kt today: account, then sections Interaction, Audio, Data usage, Appearance, Downloads, Widgets, Library, About, all open | Built 19 Oct 2026 (7fbf4ca): Sound, Look & feel, Downloads & data, Library & privacy, Widgets, About; one open at a time; rows and sliders unchanged |
