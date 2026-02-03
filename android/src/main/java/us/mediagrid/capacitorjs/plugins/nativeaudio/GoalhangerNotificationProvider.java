package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaStyleNotificationHelper;
import com.google.common.collect.ImmutableList;

@UnstableApi
public class GoalhangerNotificationProvider implements MediaNotification.Provider {

    // Goalhanger Red: #EA4E47 -> RGB(234, 78, 71)
    private static final int GOALHANGER_RED = Color.rgb(234, 78, 71);
    private static final String CHANNEL_ID = AudioPlayerService.PLAYBACK_CHANNEL_ID;
    private static final int NOTIFICATION_ID = 1001;

    private final Context context;

    public GoalhangerNotificationProvider(Context context) {
        this.context = context;
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Media playback controls");
        channel.setShowBadge(false);

        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public MediaNotification createNotification(
        MediaSession mediaSession,
        ImmutableList<CommandButton> customLayout,
        MediaNotification.ActionFactory actionFactory,
        Callback onNotificationChangedCallback
    ) {
        ensureNotificationChannel();

        Player player = mediaSession.getPlayer();

        // Get the app's launcher icon resource ID
        int iconRes = context.getApplicationInfo().icon;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(getTitle(player))
            .setContentText(getArtist(player))
            .setSubText(getAlbum(player))
            .setColor(GOALHANGER_RED)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(player.isPlaying());

        // Set session activity (tap notification to open app)
        PendingIntent sessionActivity = mediaSession.getSessionActivity();
        if (sessionActivity != null) {
            builder.setContentIntent(sessionActivity);
        }

        // Apply MediaStyle
        MediaStyleNotificationHelper.MediaStyle mediaStyle =
            new MediaStyleNotificationHelper.MediaStyle(mediaSession);
        builder.setStyle(mediaStyle);

        // Add playback actions
        addPlaybackActions(builder, player, actionFactory, mediaSession);

        Notification notification = builder.build();
        return new MediaNotification(NOTIFICATION_ID, notification);
    }

    @Override
    public boolean handleCustomCommand(MediaSession session, String action, Bundle extras) {
        // No custom commands to handle
        return false;
    }

    private void ensureNotificationChannel() {
        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null &&
            notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            createNotificationChannel();
        }
    }

    private String getTitle(Player player) {
        if (player.getCurrentMediaItem() != null &&
            player.getCurrentMediaItem().mediaMetadata.title != null) {
            return player.getCurrentMediaItem().mediaMetadata.title.toString();
        }
        return "Goalhanger Podcasts";
    }

    private String getArtist(Player player) {
        if (player.getCurrentMediaItem() != null &&
            player.getCurrentMediaItem().mediaMetadata.artist != null) {
            return player.getCurrentMediaItem().mediaMetadata.artist.toString();
        }
        if (player.getCurrentMediaItem() != null &&
            player.getCurrentMediaItem().mediaMetadata.subtitle != null) {
            return player.getCurrentMediaItem().mediaMetadata.subtitle.toString();
        }
        return null;
    }

    private String getAlbum(Player player) {
        if (player.getCurrentMediaItem() != null &&
            player.getCurrentMediaItem().mediaMetadata.albumTitle != null) {
            return player.getCurrentMediaItem().mediaMetadata.albumTitle.toString();
        }
        return null;
    }

    private void addPlaybackActions(
        NotificationCompat.Builder builder,
        Player player,
        MediaNotification.ActionFactory actionFactory,
        MediaSession mediaSession
    ) {
        // Add skip back 15s
        builder.addAction(createSkipBackAction(actionFactory, mediaSession));

        // Add play/pause
        if (player.isPlaying()) {
            builder.addAction(createPauseAction(actionFactory, mediaSession));
        } else {
            builder.addAction(createPlayAction(actionFactory, mediaSession));
        }

        // Add skip forward 15s
        builder.addAction(createSkipForwardAction(actionFactory, mediaSession));
    }

    private NotificationCompat.Action createPlayAction(
        MediaNotification.ActionFactory actionFactory,
        MediaSession mediaSession
    ) {
        return new NotificationCompat.Action(
            android.R.drawable.ic_media_play,
            "Play",
            actionFactory.createMediaActionPendingIntent(
                mediaSession,
                Player.COMMAND_PLAY_PAUSE
            )
        );
    }

    private NotificationCompat.Action createPauseAction(
        MediaNotification.ActionFactory actionFactory,
        MediaSession mediaSession
    ) {
        return new NotificationCompat.Action(
            android.R.drawable.ic_media_pause,
            "Pause",
            actionFactory.createMediaActionPendingIntent(
                mediaSession,
                Player.COMMAND_PLAY_PAUSE
            )
        );
    }

    private NotificationCompat.Action createSkipBackAction(
        MediaNotification.ActionFactory actionFactory,
        MediaSession mediaSession
    ) {
        return new NotificationCompat.Action(
            android.R.drawable.ic_media_rew,
            "Rewind 15s",
            actionFactory.createMediaActionPendingIntent(
                mediaSession,
                Player.COMMAND_SEEK_BACK
            )
        );
    }

    private NotificationCompat.Action createSkipForwardAction(
        MediaNotification.ActionFactory actionFactory,
        MediaSession mediaSession
    ) {
        return new NotificationCompat.Action(
            android.R.drawable.ic_media_ff,
            "Forward 15s",
            actionFactory.createMediaActionPendingIntent(
                mediaSession,
                Player.COMMAND_SEEK_FORWARD
            )
        );
    }
}
