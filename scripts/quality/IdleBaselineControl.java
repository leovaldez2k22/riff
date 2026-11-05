import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.app.KeyguardManager;
import android.os.PowerManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import java.util.List;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;

/** Shell-only baseline helper. Never reads titles, URLs, account data or queue descriptions. */
public final class IdleBaselineControl {
    private static final String PACKAGE = "com.musicglass.personal.debug";

    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (Throwable error) {
            PrintStream output = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
            for (Throwable current = error; current != null; current = current.getCause())
                output.println("RIFF_BASELINE_FAILURE " + current.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private static void run(String[] args) throws Exception {
        PrintStream output = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
        output.println("RIFF_BASELINE_PHASE entry");
        if (args.length < 1 || !(args[0].equals("state") || args[0].equals("pause") || args[0].equals("seek") || args[0].equals("device")))
            throw new IllegalArgumentException("Expected state, device, pause, or seek <position_ms>");
        if (args[0].equals("seek") && (args.length != 2 || !args[1].matches("[0-9]{1,12}")))
            throw new IllegalArgumentException("Expected nonnegative position_ms");
        Looper.prepareMainLooper();
        output.println("RIFF_BASELINE_PHASE looper");
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        output.println("RIFF_BASELINE_PHASE context");
        Context context = ((Context) threadClass.getMethod("getSystemContext").invoke(thread))
            .createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        // app_process does not initialize the app's modular-media service registry.
        Class<?> mediaInitializer = Class.forName("android.media.MediaFrameworkPlatformInitializer");
        if (mediaInitializer.getMethod("getMediaServiceManager").invoke(null) == null) {
            Class<?> mediaServices = Class.forName("android.media.MediaServiceManager");
            mediaInitializer.getMethod("setMediaServiceManager", mediaServices)
                .invoke(null, mediaServices.getDeclaredConstructor().newInstance());
        }
        if (args[0].equals("device")) {
            output.println("RIFF_BASELINE_DEVICE {\"locked\":"
                + context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                + ",\"interactive\":" + context.getSystemService(PowerManager.class).isInteractive()
                + ",\"volume_index\":" + context.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC) + "}");
            System.exit(0);
        }
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
            .invoke(null, "media_session");
        MediaSessionManager manager;
        try {
            manager = MediaSessionManager.class.getDeclaredConstructor(Context.class).newInstance(context);
        } catch (NoSuchMethodException missing) {
            manager = MediaSessionManager.class.getDeclaredConstructor(Context.class, android.os.IBinder.class)
                .newInstance(context, binder);
        }
        output.println("RIFF_BASELINE_PHASE sessions");
        MediaController selected = null;
        for (MediaController controller : manager.getActiveSessions(null)) {
            if (PACKAGE.equals(controller.getPackageName())) {
                if (selected != null) throw new IllegalStateException("Multiple debug sessions; cannot choose safely");
                selected = controller;
            }
        }
        if (selected == null) throw new IllegalStateException("No active Riff Debug session");
        if (args[0].equals("pause")) {
            selected.getTransportControls().pause();
            Thread.sleep(500);
        } else if (args[0].equals("seek")) {
            PlaybackState before = selected.getPlaybackState();
            if (before == null || before.getState() != PlaybackState.STATE_PAUSED)
                throw new IllegalStateException("Seek restoration requires a paused debug session");
            if ((before.getActions() & PlaybackState.ACTION_SEEK_TO) == 0)
                throw new IllegalStateException("Debug session does not support seeking");
            selected.getTransportControls().seekTo(Long.parseLong(args[1]));
            Thread.sleep(600);
        }
        PlaybackState state = selected.getPlaybackState();
        if (state == null) throw new IllegalStateException("Debug session has no playback state");
        List<MediaSession.QueueItem> queue = selected.getQueue();
        int index = -1;
        if (queue != null) for (int i = 0; i < queue.size(); i++)
            if (queue.get(i).getQueueId() == state.getActiveQueueItemId()) index = i;
        AudioManager audio = context.getSystemService(AudioManager.class);
        MediaMetadata metadata = selected.getMetadata();
        output.println("RIFF_BASELINE_STATE {\"state\":" + state.getState()
            + ",\"position_ms\":" + state.getPosition()
            + ",\"buffered_ms\":" + state.getBufferedPosition()
            + ",\"updated_uptime_ms\":" + state.getLastPositionUpdateTime()
            + ",\"queue_size\":" + (queue == null ? -1 : queue.size())
            + ",\"queue_index\":" + index
            + ",\"error\":" + (state.getState() == PlaybackState.STATE_ERROR)
            + ",\"duration_ms\":" + (metadata == null ? -1 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
            + ",\"locked\":" + context.getSystemService(KeyguardManager.class).isKeyguardLocked()
            + ",\"interactive\":" + context.getSystemService(PowerManager.class).isInteractive()
            + ",\"volume_index\":" + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + "}");
        System.exit(0);
    }
}
