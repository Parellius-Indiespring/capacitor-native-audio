package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.os.Bundle;
import android.net.Uri;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaSessionCallback implements MediaLibrarySession.Callback {

    public static final String SET_AUDIO_SOURCES = "SetAudioSources";
    public static final String CREATE_PLAYER = "CreatePlayer";
    private static final String EXTRA_IS_LOGGED_IN = "isLoggedIn";
    public static final String SET_LOGIN_STATE = "SetLoginState";
    private static final String ROOT_ID = "root";
    private static final String NODE_SERIES = "root/series";
    private static final String NODE_CONTINUE = "root/continue";
    private static final String NODE_EPISODES = "root/episodes";
    private static final String NODE_LOGIN = "root/login";
    private static final int DEFAULT_PAGE_SIZE = 50;

    private AudioPlayerService audioService;
    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor();

    public MediaSessionCallback(AudioPlayerService audioService) {
        this.audioService = audioService;
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public MediaSession.ConnectionResult onConnect(
        MediaSession session,
        MediaSession.ControllerInfo controller
    ) {
        SessionCommands sessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(new SessionCommand(SET_AUDIO_SOURCES, new Bundle()))
                .add(new SessionCommand(CREATE_PLAYER, new Bundle()))
                .build();

        return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .build();
    }

    @Override
    public ListenableFuture<SessionResult> onCustomCommand(
        MediaSession session,
        MediaSession.ControllerInfo controller,
        SessionCommand customCommand,
        Bundle args
    ) {
        if (customCommand.customAction.equals(SET_AUDIO_SOURCES)) {
            Bundle extras = new Bundle();
            extras.putBinder("audioSources", customCommand.customExtras.getBinder("audioSources"));
            updateSessionExtras(session, extras);
        } else if (customCommand.customAction.equals(SET_LOGIN_STATE)) {
            Bundle extras = new Bundle();
            extras.putBoolean(
                EXTRA_IS_LOGGED_IN,
                customCommand.customExtras.getBoolean(EXTRA_IS_LOGGED_IN, false)
            );
            updateSessionExtras(session, extras);
        } else if (customCommand.customAction.equals(CREATE_PLAYER)) {
            AudioSource source = (AudioSource) customCommand.customExtras.getBinder("audioSource");
            source.initialize(audioService);
        }

        return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
    }

    @Override
    public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
        MediaLibrarySession session,
        MediaSession.ControllerInfo browser,
        @Nullable LibraryParams params
    ) {
        boolean loggedIn = isLoggedIn(session);
        if (!loggedIn) {
            MediaItem loginRoot = buildBrowsableItem(
                NODE_LOGIN,
                "Sign in on your phone",
                "Open GH Player on your phone to continue"
            );
            return Futures.immediateFuture(LibraryResult.ofItem(loginRoot, params));
        }

        MediaItem root =
            new MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    new MediaMetadata.Builder()
                        .setTitle("GH Player")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build();

        return Futures.immediateFuture(LibraryResult.ofItem(root, params));
    }

    @Override
    public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
        MediaLibrarySession session,
        MediaSession.ControllerInfo browser,
        String mediaId
    ) {
        if (NODE_LOGIN.equals(mediaId)) {
            MediaItem loginRoot = buildBrowsableItem(
                NODE_LOGIN,
                "Sign in on your phone",
                "Open GH Player on your phone to continue"
            );
            return Futures.immediateFuture(LibraryResult.ofItem(loginRoot, null));
        }

        return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED));
    }

    @Override
    public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
        MediaLibrarySession session,
        MediaSession.ControllerInfo browser,
        String parentId,
        @IntRange(from = 0) int page,
        @IntRange(from = 1) int pageSize,
        @Nullable LibraryParams params
    ) {
        boolean loggedIn = isLoggedIn(session);
        if (!loggedIn) {
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params));
        }

        if (ROOT_ID.equals(parentId)) {
            ImmutableList<MediaItem> items = ImmutableList.of(
                buildBrowsableItem(NODE_SERIES, "Series", "Browse series"),
                buildBrowsableItem(NODE_CONTINUE, "Continue Listening", "Pick up where you left off"),
                buildBrowsableItem(NODE_EPISODES, "Episodes", "Latest episodes")
            );
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params));
        }

        if (NODE_SERIES.equals(parentId)) {
            return fetchSeriesItems(params);
        }

        if (parentId != null && parentId.startsWith("series/")) {
            String seriesId = parentId.substring("series/".length());
            return fetchSeriesEpisodes(seriesId, params);
        }

        if (NODE_EPISODES.equals(parentId)) {
            return fetchLatestEpisodes(params);
        }

        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params));
    }

    @Override
    public ListenableFuture<LibraryResult<Void>> onSearch(
        MediaLibrarySession session,
        MediaSession.ControllerInfo browser,
        String query,
        @Nullable LibraryParams params
    ) {
        return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED));
    }

    @Override
    public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
        MediaLibrarySession session,
        MediaSession.ControllerInfo browser,
        String query,
        @IntRange(from = 0) int page,
        @IntRange(from = 1) int pageSize,
        @Nullable LibraryParams params
    ) {
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params));
    }

    private boolean isLoggedIn(MediaLibrarySession session) {
        Bundle extras = session.getSessionExtras();
        return extras != null && extras.getBoolean(EXTRA_IS_LOGGED_IN, false);
    }

    private MediaItem buildBrowsableItem(String id, String title, String subtitle) {
        return new MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                new MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build();
    }

    private ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> fetchSeriesItems(
        @Nullable LibraryParams params
    ) {
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future = SettableFuture.create();
        libraryExecutor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(audioService.getApplicationContext());
                List<SupabaseApi.AutoPlaylist> playlists = api.fetchSeries(DEFAULT_PAGE_SIZE);
                ImmutableList.Builder<MediaItem> items = ImmutableList.builder();
                for (SupabaseApi.AutoPlaylist playlist : playlists) {
                    MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                        .setTitle(playlist.title)
                        .setSubtitle(playlist.description)
                        .setIsBrowsable(true)
                        .setIsPlayable(false);

                    if (playlist.imageUrl != null && !playlist.imageUrl.isEmpty()) {
                        metadata.setArtworkUri(Uri.parse(playlist.imageUrl));
                    }

                    items.add(
                        new MediaItem.Builder()
                            .setMediaId("series/" + playlist.id)
                            .setMediaMetadata(metadata.build())
                            .build()
                    );
                }
                future.set(LibraryResult.ofItemList(items.build(), params));
            } catch (Exception ex) {
                future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO));
            }
        });
        return future;
    }

    private ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> fetchLatestEpisodes(
        @Nullable LibraryParams params
    ) {
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future = SettableFuture.create();
        libraryExecutor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(audioService.getApplicationContext());
                List<SupabaseApi.AutoEpisode> episodes = api.fetchLatestEpisodes(DEFAULT_PAGE_SIZE);
                ImmutableList.Builder<MediaItem> items = ImmutableList.builder();
                for (SupabaseApi.AutoEpisode episode : episodes) {
                    MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setSubtitle(
                            episode.podcastTitle != null && !episode.podcastTitle.isEmpty()
                                ? episode.podcastTitle
                                : "Goalhanger"
                        )
                        .setIsBrowsable(false)
                        .setIsPlayable(true);

                    String artwork = episode.imageUrl != null && !episode.imageUrl.isEmpty()
                        ? episode.imageUrl
                        : episode.podcastImageUrl;
                    if (artwork != null && !artwork.isEmpty()) {
                        metadata.setArtworkUri(Uri.parse(artwork));
                    }

                    MediaItem.Builder itemBuilder = new MediaItem.Builder()
                        .setMediaId("episode/" + episode.id)
                        .setMediaMetadata(metadata.build());

                    if (episode.audioUrl != null && !episode.audioUrl.isEmpty()) {
                        itemBuilder.setUri(Uri.parse(episode.audioUrl));
                    }

                    items.add(itemBuilder.build());
                }
                future.set(LibraryResult.ofItemList(items.build(), params));
            } catch (Exception ex) {
                future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO));
            }
        });
        return future;
    }

    private ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> fetchSeriesEpisodes(
        String seriesId,
        @Nullable LibraryParams params
    ) {
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future = SettableFuture.create();
        libraryExecutor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(audioService.getApplicationContext());
                List<SupabaseApi.AutoEpisode> episodes = api.fetchSeriesEpisodes(seriesId, DEFAULT_PAGE_SIZE);
                ImmutableList.Builder<MediaItem> items = ImmutableList.builder();
                for (SupabaseApi.AutoEpisode episode : episodes) {
                    MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setSubtitle(
                            episode.podcastTitle != null && !episode.podcastTitle.isEmpty()
                                ? episode.podcastTitle
                                : "Goalhanger"
                        )
                        .setIsBrowsable(false)
                        .setIsPlayable(true);

                    String artwork = episode.imageUrl != null && !episode.imageUrl.isEmpty()
                        ? episode.imageUrl
                        : episode.podcastImageUrl;
                    if (artwork != null && !artwork.isEmpty()) {
                        metadata.setArtworkUri(Uri.parse(artwork));
                    }

                    MediaItem.Builder itemBuilder = new MediaItem.Builder()
                        .setMediaId("episode/" + episode.id)
                        .setMediaMetadata(metadata.build());

                    if (episode.audioUrl != null && !episode.audioUrl.isEmpty()) {
                        itemBuilder.setUri(Uri.parse(episode.audioUrl));
                    }

                    items.add(itemBuilder.build());
                }
                future.set(LibraryResult.ofItemList(items.build(), params));
            } catch (Exception ex) {
                future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO));
            }
        });
        return future;
    }

    private void updateSessionExtras(MediaLibrarySession session, Bundle extras) {
        Bundle current = session.getSessionExtras();
        Bundle merged = new Bundle(current != null ? current : Bundle.EMPTY);
        merged.putAll(extras);
        session.setSessionExtras(merged);
    }
}
