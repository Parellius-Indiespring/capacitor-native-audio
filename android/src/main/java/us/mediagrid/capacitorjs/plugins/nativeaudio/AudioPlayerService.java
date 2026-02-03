package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaNotification;
import com.google.common.collect.ImmutableList;

public class AudioPlayerService extends MediaLibraryService {

    private static final String TAG = "AudioPlayerService";
    public static final String PLAYBACK_CHANNEL_ID = "playback_channel";
    private MediaLibrarySession mediaSession = null;
    private PendingIntent sessionActivityPendingIntent = null;
    private boolean playlistActive = false;
    private ExoPlayer player = null;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable androidx.media3.common.MediaItem mediaItem, int reason) {
            if (mediaItem != null) {
                Log.i(TAG, "Media item changed: " + mediaItem.mediaId +
                      " (index: " + player.getCurrentMediaItemIndex() +
                      " of " + player.getMediaItemCount() + ")");
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            String stateStr = playbackState == Player.STATE_IDLE ? "IDLE" :
                             playbackState == Player.STATE_BUFFERING ? "BUFFERING" :
                             playbackState == Player.STATE_READY ? "READY" :
                             playbackState == Player.STATE_ENDED ? "ENDED" : "UNKNOWN";
            Log.i(TAG, "Playback state: " + stateStr);
        }
    };

    @Override
    public void onCreate() {
        Log.i(TAG, "Service being created");
        super.onCreate();

        String packageName = getApplicationContext().getPackageName();
        Intent sessionActivityIntent = getPackageManager().getLaunchIntentForPackage(packageName);

        if (sessionActivityIntent == null) {
            Log.w(TAG, "No launch intent for package: " + packageName);
        } else {
            sessionActivityPendingIntent = PendingIntent.getActivity(
                this,
                0,
                sessionActivityIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
        }

        ensureSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service starting");

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        try {
            Log.i(TAG, "onGetSession controller=" + controllerInfo.getPackageName());
            ensureSession();
            if (mediaSession == null) {
                Log.e(TAG, "mediaSession is null after ensureSession");
            }
            return mediaSession;
        } catch (Exception e) {
            Log.e(TAG, "onGetSession failed", e);
            return mediaSession;
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public MediaNotification.Provider getMediaNotificationProvider() {
        return new GoalhangerNotificationProvider(getApplicationContext());
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        Log.i(TAG, "Task removed");

        AudioSources audioSources = getAudioSourcesFromMediaSession();

        if (audioSources != null) {
            Log.i(TAG, "Destroying all non-notification audio sources");
            audioSources.destroyAllNonNotificationSources();
        }

        if (mediaSession == null) {
            Log.w(TAG, "mediaSession is null in onTaskRemoved");
            stopSelf();
            return;
        }

        Player player = mediaSession.getPlayer();

        // Make sure the service is not in foreground
        if (player.getPlayWhenReady()) {
            player.pause();
        }

        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service being destroyed");

        AudioSources audioSources = getAudioSourcesFromMediaSession();

        if (audioSources != null) {
            Log.i(TAG, "Destroying all non-notification audio sources");
            audioSources.destroyAllNonNotificationSources();
        }

        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }

        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }

        super.onDestroy();
    }

    @OptIn(markerClass = UnstableApi.class)
    public AudioSources getAudioSourcesFromMediaSession() {
        if (mediaSession == null) {
            Log.w(TAG, "mediaSession is null in getAudioSourcesFromMediaSession");
            return null;
        }

        IBinder sourcesBinder = mediaSession.getSessionExtras().getBinder("audioSources");

        if (sourcesBinder != null) {
            return (AudioSources) sourcesBinder;
        }

        return null;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void ensureSession() {
        if (mediaSession != null) {
            return;
        }

        Log.i(TAG, "Creating MediaLibrarySession with Goalhanger Red notification");
        player = new ExoPlayer.Builder(this)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setSeekBackIncrementMs(15000)
            .setSeekForwardIncrementMs(15000)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build();
        player.setPlayWhenReady(false);
        player.addListener(playerListener);

        MediaLibrarySession.Builder builder = new MediaLibrarySession.Builder(this, player, new MediaSessionCallback(this));
        if (sessionActivityPendingIntent != null) {
            builder.setSessionActivity(sessionActivityPendingIntent);
        }
        builder.setMediaButtonPreferences(buildMediaButtonPreferences());
        builder.setCustomLayout(buildCustomLayout(playlistActive));
        mediaSession = builder.build();
        applyStoredLoginState();
    }

    private ImmutableList<CommandButton> buildMediaButtonPreferences() {
        return ImmutableList.of(
            new CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                .setDisplayName("Back 15s")
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .build(),
            new CommandButton.Builder(CommandButton.ICON_PLAY)
                .setDisplayName("Play/Pause")
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .build(),
            new CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
                .setDisplayName("Forward 15s")
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .build()
        );
    }

    private ImmutableList<CommandButton> buildCustomLayout(boolean hasPlaylist) {
        return ImmutableList.of();
    }

    public void updatePlaylistState(boolean hasPlaylist) {
        playlistActive = hasPlaylist;
        if (mediaSession != null) {
            mediaSession.setMediaButtonPreferences(buildMediaButtonPreferences());
            mediaSession.setCustomLayout(buildCustomLayout(hasPlaylist));
        }
    }

    private void applyStoredLoginState() {
        if (mediaSession == null) {
            return;
        }

        AutoAuthConfig config = AutoAuthStore.load(getApplicationContext());
        boolean loggedIn = config != null && config.accessToken != null && !config.accessToken.isEmpty();

        Bundle current = mediaSession.getSessionExtras();
        Bundle merged = new Bundle(current != null ? current : Bundle.EMPTY);
        merged.putBoolean("isLoggedIn", loggedIn);
        mediaSession.setSessionExtras(merged);
        Log.i(TAG, "applyStoredLoginState: " + loggedIn);
        notifyLibraryRootChanged("applyStoredLoginState=" + loggedIn);
    }

    public void notifyLibraryRootChanged(String reason) {
        if (mediaSession == null) {
            Log.w(TAG, "notifyLibraryRootChanged: mediaSession null");
            return;
        }
        Log.i(TAG, "notifyLibraryRootChanged: " + reason);
        mediaSession.notifyChildrenChanged(MediaSessionCallback.ROOT_ID, Integer.MAX_VALUE, null);
    }

    public void triggerSkipNext() {
        AudioSources sources = getAudioSourcesFromMediaSession();
        if (sources == null) {
            return;
        }
        AudioSource source = sources.forNotification();
        if (source != null) {
            source.triggerSkipNext();
        }
    }

    public void triggerSkipPrevious() {
        AudioSources sources = getAudioSourcesFromMediaSession();
        if (sources == null) {
            return;
        }
        AudioSource source = sources.forNotification();
        if (source != null) {
            source.triggerSkipPrevious();
        }
    }

    @Nullable
    public androidx.media3.common.MediaItem getCurrentMediaItem() {
        if (mediaSession == null) {
            return null;
        }
        return mediaSession.getPlayer().getCurrentMediaItem();
    }

    public long getCurrentPositionMs() {
        if (mediaSession == null) {
            return C.TIME_UNSET;
        }
        return mediaSession.getPlayer().getCurrentPosition();
    }
}





