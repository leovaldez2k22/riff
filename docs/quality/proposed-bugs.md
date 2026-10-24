# Proposed bugs, waiting for the owner

Found after the owner's deadline decision of 24 October 2026. Nothing here is in `q18-bugs.md`: the owner approves each entry first.
Each entry: what was seen, where, the evidence file, and a suggested severity.

## P1: SignInScreensDeviceTest taps a row scrolled off a short screen (API 36 emulator)

- **Seen:** R5 emulator lane on the final code (bc69f02), API 36 emulator riff_q18_api36 (720 x 1280): 69 of 70, the one failure `welcomeShowsTheThreeLatestAndAllAccountsSearchesAndRemoves`, "could not find any node that satisfies (TestTag = 'signin_account_remove')". Three reruns of the class alone: the same failure each time. The same test passes on API 31 and API 33 (69 of 69 each) and passed on API 36 on 20 Oct (C2).
- **Why:** after the search is cleared and the keyboard hidden, the Accounts list stays scrolled down, so the first row (Fixture One) sits above the screen. The test clicks the first row's menu button, which is off screen, so no menu opens. Riff's screen is right; the list scrolls back by hand.
- **Evidence:** `..\Riff-finalrtifacts\quality\lanes\emulator-20261024T161001Z` (instrumentation.txt, signin-rerun-1.txt to 3, q19c3-1b-remove-menu.png showing the list from Fixture Two).
- **Suggested fix:** in the test, scroll the list to its first row (`performScrollToIndex(0)`) before opening the menu. Test only; no change to the app.
- **Suggested severity:** Low (test).
