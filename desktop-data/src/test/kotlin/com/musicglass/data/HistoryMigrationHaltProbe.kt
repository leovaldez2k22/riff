package com.musicglass.data

import java.io.File

/** Test executable only: terminate the real process while the production schema transaction is active. */
object HistoryMigrationHaltProbe {
    @JvmStatic fun main(args:Array<String>){
        require(args.size==2)
        val checkpoint=LibraryMigrationCheckpoint.valueOf(args[1])
        SqliteLibraryRepository(File(args[0])){actual->if(actual==checkpoint)Runtime.getRuntime().halt(73)}.use{}
        error("Requested migration checkpoint was not reached")
    }
}
