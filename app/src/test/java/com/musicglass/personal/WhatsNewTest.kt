package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Feature 29: when the sheet shows, and the release file it reads. */
class WhatsNewTest {
    @Test fun anUpdateShowsTheSheetOnceForItsRelease() {
        assertEquals(WhatsNewShow.Release, whatsNewDue(lastSeen = 33, current = 34, freshInstall = false, release = 34))
        assertEquals(WhatsNewShow.None, whatsNewDue(lastSeen = 34, current = 34, freshInstall = false, release = 34))
    }

    @Test fun anUpdateFromBeforeTheSheetExistedStillShowsIt() =
        assertEquals(WhatsNewShow.Release, whatsNewDue(lastSeen = 0, current = 34, freshInstall = false, release = 34))

    @Test fun aNewInstallGetsTheTipsInstead() {
        assertEquals(WhatsNewShow.Tips, whatsNewDue(lastSeen = 0, current = 35, freshInstall = true, release = 35))
        assertEquals(WhatsNewShow.Tips, whatsNewDue(lastSeen = 0, current = 35, freshInstall = true, release = 34))
    }

    // 0.20.0's new signing key makes every install of it fresh, so those get the release's cards with the tips.
    @Test fun aNewInstallOfTheNewKeyReleaseGetsItsCardsAndTheTips() =
        assertEquals(WhatsNewShow.ReleaseAndTips, whatsNewDue(lastSeen = 0, current = NEW_KEY_RELEASE, freshInstall = true, release = NEW_KEY_RELEASE))

    @Test fun anUpdateWithoutNewContentShowsNothing() {
        assertEquals(WhatsNewShow.None, whatsNewDue(lastSeen = 34, current = 35, freshInstall = false, release = 34))
        // Content written for a release not yet installed waits for it.
        assertEquals(WhatsNewShow.None, whatsNewDue(lastSeen = 0, current = 33, freshInstall = false, release = 34))
    }

    @Test fun theReleaseFileReadsAndEveryActionGoesSomewhere() {
        val release = parseWhatsNew(File("src/main/res/raw/whats_new.json").readText())
        assertNotNull(release); release!!
        assertTrue(release.cards.size in 3..5)
        assertTrue(release.cards.all { it.action in setOf("", "encore", "equalizer", "settings", "player") })
    }

    @Test fun aBrokenFileOrCardIsLeftOut() {
        assertNull(parseWhatsNew("{"))
        assertNull(parseWhatsNew("""{"cards":[]}"""))
        assertEquals(1, parseWhatsNew("""{"version":2,"cards":[{"title":"A","body":"B"},{"title":""}]}""")!!.cards.size)
    }

    @Test fun tryItOpensTheScreens() {
        val model = ShellModel()
        tryWhatsNew("encore", model); assertEquals(Route.Encore, model.current)
        tryWhatsNew("equalizer", model); assertEquals(Route.Settings(SettingsSection.Equalizer), model.current)
        tryWhatsNew("player", model); assertEquals("mini", model.sheet)
    }
}
