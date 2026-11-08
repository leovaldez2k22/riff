package com.musicglass.quality;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.TreeMap;

/** Signed test sidecar: platform APIs only, so shrinking cannot change its target symbols. */
public final class EditionSwitchProbe extends Instrumentation {
    private static final String PACKAGE = "com.musicglass.personal";
    private static final String ID = "split000001";
    private static final String TITLE = "Split fixture song";
    private static final String SCOPE = "guest";
    private static final String[][] PREFS = {
        {"crossfade", "enabled", "mode", "seconds"}, {"audio_boost", "enabled", "percent", "normalize"},
        {"riff_equalizer", "enabled", "mode", "tone_x", "tone_y", "focused", "manual", "preset", "balance"},
        {"moods", "chosen", "kept"}, {"accessibility_display", "player_stats"}
    };
    private Bundle arguments;
    private Context target;
    private File marker;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!PACKAGE.equals(getTargetContext().getPackageName())) throw new IllegalStateException("Wrong target package");
            if (!("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE))) {
                throw new IllegalStateException("Fixture probe refuses physical devices");
            }
            String token = arguments.getString("token", "");
            if (!token.matches("[a-f0-9]{32}")) throw new IllegalStateException("Missing fixture ownership token");
            target = getTargetContext();
            marker = new File(target.getNoBackupFilesDir(), "edition-switch-fixture.json");
            String action = arguments.getString("action", "verify");
            if ("seed".equals(action)) {
                if (marker.exists()) throw new IllegalStateException("Refusing to overwrite a fixture");
                seed(token);
            } else if (!"verify".equals(action)) throw new IllegalStateException("Unknown probe action");
            JSONObject ownership = new JSONObject(read(marker));
            if (!token.equals(ownership.getString("token"))) throw new IllegalStateException("Fixture ownership mismatch");
            launch();
            JSONObject report = snapshot(ownership.getInt("widgetId"));
            result.putString("report", report.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void launch() throws Exception {
        Intent intent = target.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (intent == null) throw new IllegalStateException("Launcher missing");
        startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long until = SystemClock.elapsedRealtime() + 30000;
        while ((!target.getDatabasePath("library-state.db").isFile() || !target.getDatabasePath("offline-music.db").isFile()) && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(100);
        }
        if (!target.getDatabasePath("library-state.db").isFile() || !target.getDatabasePath("offline-music.db").isFile()) {
            throw new IllegalStateException("Application stores did not initialize");
        }
    }

    private SharedPreferences prefs(String name) { return target.getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException("Preference commit failed");
    }
    private File bundle() throws Exception {
        return new File(new File(new File(target.getFilesDir(), "offline-music-v1"), digest(SCOPE.getBytes(StandardCharsets.UTF_8), "SHA-256")), ID);
    }
    private File analysis() throws Exception {
        return new File(new File(target.getNoBackupFilesDir(), "automix-analysis"), digest(("yt:" + ID + ":tail").getBytes(StandardCharsets.UTF_8), "SHA-1") + ".env");
    }
    private SQLiteDatabase database(String name) {
        return SQLiteDatabase.openDatabase(target.getDatabasePath(name).getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
    }

    private void seed(String token) throws Exception {
        commit(prefs("update_check").edit().putBoolean("enabled", false));
        launch();
        commit(prefs("crossfade").edit().putBoolean("enabled", true).putString("mode", "automix").putInt("seconds", 6));
        commit(prefs("riff_equalizer").edit().putBoolean("enabled", true).putString("mode", "manual")
            .putInt("tone_x", 15).putInt("tone_y", -5).putBoolean("focused", true)
            .putString("manual", "2.0,-1.0,3.0,0.0,-2.0,1.0,0.0").putString("preset", "Custom").putFloat("balance", 0.25f));
        commit(prefs("audio_boost").edit().putBoolean("enabled", true).putInt("percent", 150).putBoolean("normalize", false));
        commit(prefs("moods").edit().putString("chosen", "party").putString("kept", "split-fixture\tSplit fixture\ttrue\tautomix\t6\t150\tfalse\t-"));
        commit(prefs("accessibility_display").edit().putBoolean("player_stats", true));
        File dir = bundle();
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("Download directory unavailable");
        File audio = new File(dir, "audio.media");
        try (InputStream in = getContext().getAssets().open("seek-source.webm"); FileOutputStream out = new FileOutputStream(audio)) {
            byte[] bytes = new byte[16384]; int count;
            while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
            out.getFD().sync();
        }
        write(new File(dir, "ready"), Long.toString(audio.length()));
        long duration;
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try {
            media.setDataSource(audio.getAbsolutePath());
            duration = Long.parseLong(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } finally { media.release(); }
        if (duration <= 2000) throw new IllegalStateException("Fixture audio duration unavailable");
        JSONObject track = new JSONObject().put("title", TITLE).put("artist", "Split fixture artist").put("album", "Split fixture album").put("music", true).put("live", false);
        try (SQLiteDatabase db = database("offline-music.db")) {
            db.execSQL("INSERT INTO songs(scope,id,track,state,pinned,bytes,total,duration,quality,downloaded_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                new Object[]{SCOPE, ID, track.toString(), "complete", 1, audio.length(), audio.length(), duration, "fixture", System.currentTimeMillis()});
        }
        JSONObject item = new JSONObject().put("id", "yt:" + ID).put("title", TITLE).put("artist", "Split fixture artist").put("art", JSONObject.NULL).put("album", "Split fixture album")
            .put("artistId", JSONObject.NULL).put("albumId", JSONObject.NULL).put("live", false).put("local", JSONObject.NULL).put("music", true);
        JSONObject snapshot = new JSONObject().put("format", "riff.playback-snapshot").put("version", 2).put("index", 0).put("position", 2000)
            .put("shuffle", false).put("repeat", 0).put("radioSeed", JSONObject.NULL).put("collectionTitle", "Split fixture").put("duration", duration).put("queue", new JSONArray().put(item));
        String day = LocalDate.now().toString();
        try (SQLiteDatabase db = database("library-state.db")) {
            db.beginTransaction();
            try {
                db.execSQL("INSERT INTO history_events(scope,event_id,media_id,title,artist,played_at,duration_ms,music,search_text,listened_ms,album,ended,source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{SCOPE, "split-fixture", "yt:" + ID, TITLE, "Split fixture artist", System.currentTimeMillis(), 120000, 1, "split fixture song", 120000, "Split fixture album", 1, "download"});
                db.execSQL("INSERT INTO stats_daily(scope,day,kind,key,title,subtitle,plays,listened_ms) VALUES(?,?,?,?,?,?,?,?)",
                    new Object[]{SCOPE, day, "song", "yt:" + ID, TITLE, "Split fixture artist", 1, 120000});
                db.execSQL("INSERT INTO stats_hourly(scope,day,hour,listened_ms) VALUES(?,?,?,?)", new Object[]{SCOPE, day, 12, 120000});
                db.execSQL("INSERT INTO playback_snapshots(scope,payload_version,payload,updated_at) VALUES(?,?,?,?)",
                    new Object[]{SCOPE, 2, snapshot.toString(), System.currentTimeMillis()});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        File envelope = analysis();
        if (!envelope.getParentFile().mkdirs() && !envelope.getParentFile().isDirectory()) throw new IllegalStateException("Analysis directory unavailable");
        // Match the actual song and current 64-second local-tail / 100-ms envelope contract.
        // A four-second duration could trigger legitimate re-analysis after queue restoration.
        long tailLength = Math.min(duration, 64_000L);
        long tailStart = duration - tailLength;
        int blocks = (int) ((tailLength + 99L) / 100L);
        int beats = (int) ((tailLength + 499L) / 500L);
        int downbeats = (int) ((tailLength + 1999L) / 2000L);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(envelope))) {
            out.writeInt(0x52414E31); out.writeInt(2); out.writeUTF("yt:" + ID + ":tail");
            out.writeLong(tailStart); out.writeLong(duration); out.writeInt(blocks);
            for (int i = 0; i < blocks; i++) out.writeFloat(-14f);
            for (int i = 0; i < blocks; i++) out.writeFloat(-20f);
            out.writeBoolean(true); out.writeDouble(120); out.writeFloat(0.9f); out.writeFloat(0.9f);
            out.writeInt(beats); for (int i = 0; i < beats; i++) out.writeLong(tailStart + i * 500L);
            out.writeInt(downbeats); for (int i = 0; i < downbeats; i++) out.writeLong(tailStart + i * 2000L);
        }
        AppWidgetHost host = new AppWidgetHost(target, 26930);
        int widgetId = host.allocateAppWidgetId();
        getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
        try {
            if (!AppWidgetManager.getInstance(target).bindAppWidgetIdIfAllowed(widgetId, new ComponentName(PACKAGE, PACKAGE + ".EncoreWidget"))) {
                throw new IllegalStateException("Encore widget binding refused");
            }
        } finally { getUiAutomation().dropShellPermissionIdentity(); }
        write(marker, new JSONObject().put("token", token).put("widgetId", widgetId).put("format", 1).toString());
    }

    private JSONObject snapshot(int widgetId) throws Exception {
        TreeMap<String, String> values = new TreeMap<>();
        for (String[] group : PREFS) {
            SharedPreferences saved = prefs(group[0]);
            for (int i = 1; i < group.length; i++) {
                Object value = saved.getAll().get(group[i]);
                if (value == null) throw new IllegalStateException("Missing saved preference " + group[0] + "/" + group[i]);
                values.put(group[0] + "/" + group[i], value.getClass().getSimpleName() + ":" + value);
            }
        }
        JSONObject result = new JSONObject().put("preferences", digest(values.toString().getBytes(StandardCharsets.UTF_8), "SHA-256"));
        try (SQLiteDatabase db = database("library-state.db")) {
            result.put("history", query(db, "SELECT * FROM history_events WHERE scope=? AND event_id=?", SCOPE, "split-fixture"));
            result.put("totals", query(db, "SELECT * FROM stats_daily WHERE scope=? AND key=? ORDER BY day,kind", SCOPE, "yt:" + ID));
            result.put("hourly", query(db, "SELECT * FROM stats_hourly WHERE scope=? ORDER BY day,hour", SCOPE));
            result.put("queue", query(db, "SELECT scope,payload_version,payload FROM playback_snapshots WHERE scope=?", SCOPE));
        }
        try (SQLiteDatabase db = database("offline-music.db")) {
            result.put("download", query(db, "SELECT scope,id,track,state,pinned,bytes,total,duration,quality,downloaded_at FROM songs WHERE scope=? AND id=?", SCOPE, ID));
        }
        result.put("audio", hashFile(new File(bundle(), "audio.media"))).put("ready", read(new File(bundle(), "ready"))).put("analysis", hashFile(analysis()));
        AppWidgetManager manager = AppWidgetManager.getInstance(target);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(widgetId);
        if (info == null || !new ComponentName(PACKAGE, PACKAGE + ".EncoreWidget").equals(info.provider)) throw new IllegalStateException("Encore widget did not survive");
        AppWidgetHost host = new AppWidgetHost(target, 26930);
        final AppWidgetHostView[] view = new AppWidgetHostView[1];
        runOnMainSync(() -> { host.startListening(); view[0] = host.createView(target, widgetId, info); });
        try {
            waitForIdleSync();
            runOnMainSync(() -> view[0].updateAppWidget(null));
            final boolean[] cleared = new boolean[1];
            runOnMainSync(() -> cleared[0] = !text(view[0]).contains(TITLE));
            if (!cleared[0]) throw new IllegalStateException("Widget fixture view did not reset before refresh");
            target.sendBroadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(info.provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{widgetId}));
            long until = SystemClock.elapsedRealtime() + 30000;
            final boolean[] visible = new boolean[1];
            while (SystemClock.elapsedRealtime() < until) {
                runOnMainSync(() -> visible[0] = text(view[0]).contains(TITLE));
                if (visible[0]) break;
                SystemClock.sleep(100);
            }
            if (!visible[0]) throw new IllegalStateException("Encore widget did not render fixture history");
        } finally { runOnMainSync(host::stopListening); }
        return result.put("widgetId", widgetId).put("widgetRenderedFixture", true);
    }

    private String query(SQLiteDatabase db, String sql, String... args) throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                JSONArray row = new JSONArray();
                for (int i = 0; i < cursor.getColumnCount(); i++) row.put(cursor.isNull(i) ? JSONObject.NULL : cursor.getString(i));
                rows.put(row);
            }
        }
        if (rows.length() == 0) throw new IllegalStateException("Fixture database row missing");
        return digest(rows.toString().getBytes(StandardCharsets.UTF_8), "SHA-256");
    }
    private static String text(View view) {
        StringBuilder value = new StringBuilder();
        if (view instanceof TextView) value.append(((TextView) view).getText());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) value.append(text(((ViewGroup) view).getChildAt(i)));
        return value.toString();
    }
    private static String digest(byte[] bytes, String algorithm) throws Exception {
        byte[] hash = MessageDigest.getInstance(algorithm).digest(bytes); StringBuilder value = new StringBuilder();
        for (byte item : hash) value.append(String.format("%02x", item & 255));
        return value.toString();
    }
    private static String hashFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[16384]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format("%02x", item & 255));
        return value.toString();
    }
    private static String read(File file) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) out.write(bytes, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static void write(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(text.getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
    }
}
