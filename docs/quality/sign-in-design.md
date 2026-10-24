# Sign-in design (Q18-013)

Agreed with the owner on 19 October 2026 from animated mockups shown in the session (the record concept, "C", with
Riff's frosted glass). Built on 19 October 2026 after the owner's go-ahead (AccountScreen.kt, SignInDesign.kt); checked on the
phone with the owner (A2 on rc6, the account switch on rc10). This replaces the look and flow of
AccountScreen.kt; the working scope in q18-bugs.md (Q18-013: automatic finish, Google's own hosts, no banners over a
working page, a separate cookie profile, accessibility, tests) still applies underneath it.

## Look

- Black screen with a blurred colour backdrop (pink #FF5EAC, violet #6B4CFF, light pink #FF9BD3 blobs).
- Panels, bars, sheets and cards are frosted glass, Riff's FrostedPopup recipe: blur, a #171C27 tint at about 62 to
  64 %, a white sheen from the top left fading to transparent, and a thin light edge. Without blur (before Android 12)
  they fall back to solid #2A2B32.
- Primary buttons are pink with black text and a soft pink glow; second buttons are glass.
- One record is the thread through every screen: black grooves, a pink label reading "Riff", a small hole. It moves
  between screens instead of each screen having its own picture. The label always reads "Riff" (no initial, no photo).
- Google's own page is never blurred or restyled; it sits inside Riff's glass frame.

## Screens

| No. | Screen | What is on it |
|---|---|---|
| 1 | Welcome | Close (x) at the top right. The record, a little smaller than on a first run. A glass card: "Welcome back", "Pick an account to sign in with", the three most recently used accounts (avatar colour and initial, name, shortened email, "Current" or when last used), "See all N accounts" when there are more than three, a pink "Use another Google account", and "Not now". With no saved accounts: the record centred, "Bring your music", "Your likes, playlists and history from YouTube Music", "Continue with Google", "Not now". With one to three: no "See all" |
| 1b | All accounts | Glass top bar: back, the record (still), "Accounts", "N used on this phone". The glass card fills the page: a search field, every saved account with when it was last used and a ⋮ menu ("Remove from this phone", which only drops it from Riff's list), and a pink "Add another account" |
| 2 | Google's page | Glass top bar: back, the record spinning small as the loading sign, "Sign in with Google", the page's host with a lock (e.g. accounts.google.co.in), x. Google's page below, unchanged. When an account was picked on 1 or 1b, Google's page opens with that account chosen (Google's own "Hi ..." and password page). A glass footer: "Step 1 of 2 · Google" and "Riff never sees your password" |
| 3 | Stop signing in? | Back with nothing left to go back to in Google's page. A glass bottom sheet over the blurred, dimmed page: a small record, "Stop signing in?", "Your current account stays exactly as it is.", pink "Keep signing in", glass "Stop" |
| 4 | Google's page didn't load | Only when nothing loaded. The top bar stays; the record sits grey with a no-signal icon on its label; a glass card "Google's page didn't load" / "Check your connection, then try again. Nothing has changed in Riff."; pink "Try again", glass "Start over" |
| 5 | Checking your account | The record spinning in the middle inside a pink progress ring. A glass card with three steps that tick off: "Signed in to Google", "Found your YouTube Music", "Loading your library..." |
| 6 | Connected | The record above a glass card: "You're in, *name*", the shortened email, liked songs and playlists counted up from zero, pink "Start listening", "Not you? Use another account". Waits for the tap on "Start listening"; does not move to Home by itself |

Change account uses the same flow from Settings > profile.

## Saved accounts

Riff keeps a short list of the accounts used on this phone (at most 20): name, full email (Q18-085), avatar colour,
when last used, which is current, and since Q18-087 the account's position in Google's own list of the accounts in the
web session (accounts.google.com/ListAccounts, asked with a POST, Q18-100) and a digest of its account scope. It never
keeps another account's sign-in cookies, so a lost phone holds one live session, not several. Removing an account from
the list never touches the Google account; Sign out asks first and clears the list (Q18-086).

Picking a saved account (updated 24 October 2026, Q18-087 and Q18-110): when Google still holds that account in the
session, Riff loads its YouTube Music at that position behind screen 5 and commits it only if the account reached is
the one picked (by scope digest, or by address when no scope is known). Anything else (a different account, a sign-in
page, a failure, or 15 s without reaching the check) commits nothing, logs `account_pick_mismatch` or
`account_switch_direct_fallback` (words and numbers only) and opens Google's own account page, where the choice is
the user's. Checked on the phone on rc10 by the owner, both ways.

## Transitions

The owner chose a slower pace: the times below are the agreed ones, 1.5 times the first draft. With Android's
"Remove animations" on, each becomes a cross-fade and the record simply stops.

| From → to | Trigger | What moves | Length |
|---|---|---|---|
| 1 → 1b | See all N accounts | The welcome card grows up into the full page around the three rows already showing (they keep their place); the title gives way to the search field; the record flies into the new top bar; the other accounts slide in one by one with their ⋮; the pink button becomes "Add another account" | 2.0 s |
| 1b → 1 | Back | The reverse: extra rows slip away, the card settles to welcome size, the record flies back out, the title, "See all" and "Not now" return | 1.9 s |
| 1b → 2 (and 1 → 2 on an account) | Tap an account | The row glows pink; the page sinks away; the record, already in the bar, starts spinning; the bar becomes "Sign in with Google"; Google's page rises with the account chosen | 2.0 s |
| 1 → 2 | Use another Google account | The card sinks; the record lifts and shrinks into the top bar and starts spinning; the bar fades in; Google's page rises underneath | 2.2 s |
| 2 → 3 | Back on Google's first page | The page and the small record blur and dim; the sheet springs up with a slight bounce; its record rolls in | 1.7 s |
| 3 → 2 | Keep signing in | The sheet drops; the page comes back into focus | 1.1 s |
| 3 → 1 | Stop | The sheet drops; the page and bar sink; the record flies back to the welcome spot and slows to a stop with "Riff" upright; the welcome card rises | 2.1 s |
| 2 → 4 | Nothing loaded | The blank page fades; the record drops out of the bar and spins down to a stop; its colour drains to grey; the no-signal icon pops onto the label; the message and buttons rise | 2.9 s |
| 4 → 2 | Try again | The message sinks; colour returns and "Riff" comes back on the label; the record flies up into the bar spinning; the page loads again | 2.3 s |
| 2 → 5 | Google finishes | Google's page and the bar fall away; the record grows down to the middle, still spinning; the ring draws in; the steps card rises and the steps tick off in turn | 3.8 s |
| 5 → 6 | Account checked | The ring fills and the last step ticks; the steps card sinks and the ring fades; the record slows to a stop, grows to 140 % and glows; a soft pink aura swells out from it and melts away while the background brightens; it shrinks back to normal size a little higher with "Riff" upright; the glass card rises and the name, email, numbers (counting up), button and link float in one by one | 5.1 s |

A tap shows no extra effect in the app; the white circle in the mockups only marked where the tap happened.

## Owner's decisions (19 October 2026)

1. Keep the welcome screen.
2. Screen 6 waits for "Start listening".
3. Keep the brightening background and the counting numbers in 5 → 6.
4. The welcome lists the accounts used on this phone, three at first, with "See all" opening the accounts page by the
   transition above.
5. The label reads "Riff" throughout; the aura replaces the first draft's pulse ring.

## Notes for building

- The ring is drawn as part of the record so the two always share one centre (a mockup drew it separately and it
  drifted).
- Screen 4 appears only when nothing has loaded; a slow page that is still working never gets a banner or an error
  (Q18-013 scope point 3).
- Each screen is announced once to TalkBack; Back reaches every step; the record is decoration and has no label of
  its own.
