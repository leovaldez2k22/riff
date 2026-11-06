package com.musicglass.desktop

import com.musicglass.data.*
import com.musicglass.data.encore.*
import java.io.File
import java.time.*
import kotlin.test.*
import kotlinx.serialization.json.*

class EncoreExportContractTest {
    @Test fun pngExporterProducesTheActualRequiredDimensionsWithoutOpeningAWindow(){
        val bitmap=androidx.compose.ui.graphics.ImageBitmap(320,180)
        val canvas=androidx.compose.ui.graphics.Canvas(bitmap)
        canvas.drawRect(androidx.compose.ui.geometry.Rect(0f,0f,320f,180f),androidx.compose.ui.graphics.Paint().apply{color=androidx.compose.ui.graphics.Color.Red})
        val bytes=DesktopEncoreExport.png(bitmap)
        val decoded=javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        assertEquals(1080,decoded.width);assertEquals(1920,decoded.height)
        assertEquals(0xFFFF0000.toInt(),decoded.getRGB(540,960))
        // This is an export geometry contract, not a screenshot or story visual-parity acceptance.
    }

    @Test fun exportsTheRealSelectedSqlitePeriodWithoutFormulaExecutionOrLosingAnExistingFile(){
        val directory=kotlin.io.path.createTempDirectory("riff-encore-export-").toFile()
        try{SqliteLibraryRepository(File(directory,"library")).use{store->
            store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(retentionDays=0))
            store.upsert(TrackRecord(Scopes.LOCAL,"recording","  =SUM(A1:A2)","A, \"B\""))
            HistoryRepository(store,ZoneId.of("UTC")).record(ListeningSpan(Scopes.LOCAL,"span","play","recording",Instant.parse("2026-11-03T12:00:00Z").toEpochMilli(),5000))
            val period=EncorePeriod.containing(EncoreRange.Day,LocalDate.parse("2026-11-03"),DayOfWeek.MONDAY,null)
            val summary=com.musicglass.data.encore.EncoreRepository(EncoreReadDatabase(store),zone={ZoneId.of("UTC")}).use{it.summary(listOf(Scopes.LOCAL),period,Instant.parse("2026-11-03T12:01:00Z").toEpochMilli())}
            val csv=DesktopEncoreExport.csv(summary)
            assertTrue(csv.contains("\"'  =SUM(A1:A2)\""));assertTrue(csv.contains("A, \"\"B\"\""))
            val json=Json.parseToJsonElement(DesktopEncoreExport.json(summary)).jsonObject
            assertEquals(5000L,json.getValue("heardMs").jsonPrimitive.long);assertEquals(1,json.getValue("plays").jsonPrimitive.int)
            assertEquals(JsonNull,json.getValue("completion"));assertFalse(json.toString().contains("streamUrl"))
            val file=File(directory,"encore.csv");DesktopEncoreExport.write(file,summary,"csv");assertEquals(csv,file.readText())
            assertFailsWith<IllegalStateException>{DesktopEncoreExport.write(file,summary,"json")};assertEquals(csv,file.readText())
            DesktopEncoreExport.write(file,summary,"json",replace=true);assertEquals(json,Json.parseToJsonElement(file.readText()))
            assertTrue(directory.listFiles().orEmpty().none{it.extension=="part"})
        }}finally{directory.deleteRecursively()}
    }
}
