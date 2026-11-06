package com.musicglass.desktop.automix
import kotlin.test.*
import java.nio.file.Files
import java.io.File
class AnalysisLeaseTest {
    @Test fun retainedSnapshotLivesUntilTheLastDecoderOwner(){
        val root=Files.createTempDirectory("riff-analysis-lease-").toFile()
        try{
            val file=File(root,"analysis-input-real-ownership.media").apply{writeBytes(byteArrayOf(1,2,3))}
            val lease=AnalysisFileLease.snapshot(file,root);val retained=lease.retain();lease.close();assertTrue(file.exists());retained.close();assertFalse(file.exists());assertFails{lease.retain()}
            val imported=File(root,"imported.flac").apply{writeText("Ownership-only test bytes, not audio acceptance")};AnalysisFileLease.borrowed(imported).close();assertTrue(imported.exists())
            assertFails{AnalysisFileLease.snapshot(imported,root)}
        }finally{root.deleteRecursively()}
    }
}
