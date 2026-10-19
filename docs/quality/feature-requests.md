# Owner's feature requests

Asked for on 19 October 2026, alongside the sign-in design (sign-in-design.md). The owner said not to build them yet;
mockups were shown for F2 and F5. Each waits for the owner's go-ahead.

| ID | Request | Notes from the code today | Status |
|---|---|---|---|
| F1 | Tapping a song to play it opens the full player automatically | Not yet read in the code; to check which screens start playback (lists, search, downloads, widgets, notifications) and which of them should open the player | Requested; not started |
| F2 | Share lyrics: long-press a line; while held the line slowly shrinks; when the long press registers it pops back to its size and the owner can select up to 5 lines in all, above or below it; the selection becomes an image with Riff's name and logo at the bottom right, to share | LyricsPanel.kt: a tap on a timed line seeks to it (kept); the long press is new and must not seek | Requested; mockup shown 19 Oct 2026; not started |
| F3 | After a song has played more than 10 seconds, Previous goes back to its start instead of the previous song | PlaybackGateway.previous and the service's "previous" command call seekToPreviousMediaItem, which always goes to the previous song | Requested; not started |
| F4 | Slider haptics. Volume boost (100 to 300 %): strongest at 100, 200, 300; lighter at 150, 250; lightest at 125, 175, 225, 275. Crossfade (1 to 12 s): a medium tick at each second. Glass and frosted glass intensity (0 to 150 %): a medium tick every 25 % | SettingsScreen.kt sliders use one shared SliderHaptics.Intensity today | Requested; not started |
| F5 | Settings revamped into compartments that expand to show more options | SettingsScreen.kt today: account, then sections Interaction, Audio, Data usage, Appearance, Downloads, Widgets, Library, About, all open | Requested; mockup shown 19 Oct 2026; not started |
