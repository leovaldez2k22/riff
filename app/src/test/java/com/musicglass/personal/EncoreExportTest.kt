package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Feature 12 extras: the Encore page's CSV of top lists, and the home widget's words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncoreExportTest {
    private val week = EncorePeriod(EncoreRange.Week, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2))
    private fun item(kind: String, title: String, subtitle: String = "", plays: Int = 1, ms: Long = 60_000) =
        EncoreItem(kind, "$kind:$title", title, subtitle, null, plays, ms)
    private fun csv(tops: Map<String, List<EncoreItem>>) = encoreCsv { tops[it].orEmpty() }

    @Test fun theCsvListsEachTopListInOrderWithWholeMinutes() {
        val text = csv(mapOf(
            EncoreKind.SONG to listOf(item(EncoreKind.SONG, "First", "Band", 3, 245_000), item(EncoreKind.SONG, "Second", "Band")),
            EncoreKind.ARTIST to listOf(item(EncoreKind.ARTIST, "Band", plays = 4, ms = 305_000))))
        assertEquals(listOf("list,rank,title,subtitle,plays,minutes",
            "song,1,\"First\",\"Band\",3,4", "song,2,\"Second\",\"Band\",1,1", "artist,1,\"Band\",\"\",4,5"), text.trimEnd().split("\r\n"))
    }

    @Test fun aTitleCannotBreakTheCsvOrRunAsAFormula() {
        val text = csv(mapOf(EncoreKind.SONG to listOf(item(EncoreKind.SONG, "=HYPERLINK(\"x\")", "A, \"B\"\nC"))))
        assertEquals("song,1,\"'=HYPERLINK(\"\"x\"\")\",\"A, \"\"B\"\"\nC\",1,1", text.removePrefix("list,rank,title,subtitle,plays,minutes\r\n").trimEnd())
    }

    @Test fun theFileIsNamedForItsPeriod() {
        assertEquals("riff-encore-week-2026-10-26.csv", encoreCsvName(week))
    }

    @Test fun theWidgetSaysNothingYetUntilTheFirstPlay() {
        assertEquals("Nothing yet", encoreWidgetLines(0, null).first)
        assertEquals("Most played: Song", encoreWidgetLines(3_600_000, "Song").second)
        assertEquals("", encoreWidgetLines(3_600_000, null).second)
    }
}
