package com.musicglass.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.musicglass.desktop.storage.DesktopSessionDb
import kotlinx.serialization.json.*
import java.io.File

internal object DesktopPaths {
    val root: File get() = File(System.getProperty("riff.data.dir") ?: System.getenv("LOCALAPPDATA")?.let { "$it/Riff/desktop-preview" }
        ?: "${System.getProperty("user.home")}/.riff/desktop-preview")
}

/** FULL-synchronous SQLite checkpoint. No URI leases, tokens or signed URLs are serialized. */
internal class DesktopSessionRepository(directory: File) : AutoCloseable {
    private val driver: JdbcSqliteDriver
    private val db: DesktopSessionDb
    init {
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create desktop session directory" }
        driver = JdbcSqliteDriver("jdbc:sqlite:${File(directory, "session.sqlite").absolutePath}")
        val version=driver.executeQuery(null,"PRAGMA user_version",{cursor->cursor.next();QueryResult.Value(cursor.getLong(0)?:0)},0).value
        if(version>2) {driver.close();error("Session database was written by a newer app version")}
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        driver.execute(null, "PRAGMA synchronous=FULL", 0)
        driver.execute(null, "PRAGMA busy_timeout=5000", 0)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        val exists = driver.executeQuery(null, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='session_checkpoint'",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) == 1L) }, 0).value
        if (!exists) DesktopSessionDb.Schema.create(driver)
        driver.execute(null,"CREATE TABLE IF NOT EXISTS command_receipt(command_id TEXT NOT NULL PRIMARY KEY,revision INTEGER NOT NULL,payload_sha256 TEXT NOT NULL,committed_ms INTEGER NOT NULL)",0)
        driver.execute(null,"PRAGMA user_version=2",0)
        db = DesktopSessionDb(driver)
    }
    @Synchronized fun load(): JsonObject? = db.sessionQueries.readCheckpoint("default").executeAsOneOrNull()?.let {
        check(it.payload.length <= 8 * 1024 * 1024) { "Session checkpoint exceeds limit" }
        Json.parseToJsonElement(it.payload).jsonObject.also { value -> check(value["schema"]?.jsonPrimitive?.int == 1) { "Unsupported session version" } }
    }
    @Synchronized fun save(value: JsonObject, revision: Long, commandId: String = java.util.UUID.randomUUID().toString()): String {
        val text = value.toString()
        require(text.length <= 8 * 1024 * 1024)
        require(commandId.length in 1..128)
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
        db.transaction {
            val receipt=db.sessionQueries.readReceipt(commandId).executeAsOneOrNull()
            if(receipt!=null) { check(receipt.revision==revision && receipt.payload_sha256==digest){"Command identity reused with different checkpoint"};return@transaction }
            val current = db.sessionQueries.readCheckpoint("default").executeAsOneOrNull()
            check(current == null || current.revision <= revision) { "A newer queue checkpoint exists" }
            val now=System.currentTimeMillis()
            db.sessionQueries.writeCheckpoint("default", revision, text, now)
            db.sessionQueries.writeReceipt(commandId,revision,digest,now)
            db.sessionQueries.pruneReceipts()
        }
        return commandId
    }
    @Synchronized fun pragma(name: String): String {
        require(name in setOf("journal_mode", "synchronous", "integrity_check"))
        return driver.executeQuery(null, "PRAGMA $name", { c -> c.next(); QueryResult.Value(c.getString(0).orEmpty()) }, 0).value
    }
    @Synchronized override fun close() = driver.close()
}

internal fun SongItem.toSessionJson(): JsonObject = buildJsonObject {
    require(!id.startsWith("http:") && !id.startsWith("https:") && !id.startsWith("riff:")) { "Expiring URLs cannot be checkpointed" }
    put("id", id); put("title", title); put("artist", artist); put("album", album); put("duration", durationMs)
    put("episode",isEpisode);put("scope",scope); sourcePath?.let{put("path",it)}; artworkKey?.let{put("artwork",it)}; albumId?.let{put("albumId",it)}; artistId?.let{put("artistId",it)}; put("available",available)
    put("color", dominantColor.value.toString()); put("mesh", JsonArray(deckMesh.map(::JsonPrimitive)))
}
internal fun songFromSession(value: JsonObject): SongItem = SongItem(
    value.getValue("id").jsonPrimitive.content,
    value.getValue("title").jsonPrimitive.content,
    value.getValue("artist").jsonPrimitive.content,
    value.getValue("album").jsonPrimitive.content,
    value.getValue("duration").jsonPrimitive.long,
    androidx.compose.ui.graphics.Color(value.getValue("color").jsonPrimitive.content.toULong()),
    value.getValue("mesh").jsonArray.map { it.jsonPrimitive.int }.toIntArray(), emptyList(),
    scope=value["scope"]?.jsonPrimitive?.content ?: "local",
    sourcePath=value["path"]?.jsonPrimitive?.contentOrNull,
    artworkKey=value["artwork"]?.jsonPrimitive?.contentOrNull,
    albumId=value["albumId"]?.jsonPrimitive?.contentOrNull,
    artistId=value["artistId"]?.jsonPrimitive?.contentOrNull,
    available=value["available"]?.jsonPrimitive?.boolean ?: true,
    isEpisode=value["episode"]?.jsonPrimitive?.boolean?:false
)