package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.os.Bundle;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaSessionCallback implements MediaLibrarySession.Callback {

    public static final String SET_AUDIO_SOURCES = "SetAudioSources";
    public static final String CREATE_PLAYER = "CreatePlayer";
    private static final String EXTRA_IS_LOGGED_IN = "isLoggedIn";
    public static final String SET_LOGIN_STATE = "SetLoginState";
    public static final String SET_PLAYLIST_STATE = "SetPlaylistState";
    public static final String SKIP_NEXT = "SkipNext";
    public static final String SKIP_PREVIOUS = "SkipPrevious";
    static final String ROOT_ID = "root";
    private static final String NODE_SERIES = "root/series";
    private static final String NODE_CONTINUE = "root/continue";
    private static final String NODE_EPISODES = "root/episodes";
    private static final String NODE_LOGIN = "root/login";
    private static final String MEDIA_ID_NOW_PLAYING = "now_playing";
    private static final String EPISODE_LATEST_PREFIX = "episode/latest/";
    private static final String EPISODE_SERIES_PREFIX = "episode/series/";
    private static final String EPISODE_CONTINUE_PREFIX = "episode/continue/";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int ARTWORK_MAX_BYTES = 6 * 1024 * 1024;
    private static final int ARTWORK_CACHE_MAX_BYTES = 8 * 1024 * 1024;
    private static final int ARTWORK_MAX_DIM_PX = 512;
    private static final int ARTWORK_COMPRESS_QUALITY = 85;
    private static final String TAG = "MediaSessionCallback";

    private AudioPlayerService audioService;
    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor();
    private final LruCache<String, byte[]> artworkCache =
        new LruCache<>(ARTWORK_CACHE_MAX_BYTES) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value == null ? 0 : value.length;
            }
        };

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
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(new SessionCommand(SET_AUDIO_SOURCES, new Bundle()))
                .add(new SessionCommand(CREATE_PLAYER, new Bundle()))
                .add(new SessionCommand(SET_LOGIN_STATE, new Bundle()))
                .add(new SessionCommand(SET_PLAYLIST_STATE, new Bundle()))
                .add(new SessionCommand(SKIP_NEXT, new Bundle()))
                .add(new SessionCommand(SKIP_PREVIOUS, new Bundle()))
                .build();

        return new MediaLibrarySession.ConnectionResult.AcceptedResultBuilder((MediaLibrarySession) session)
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
            boolean isLoggedIn = args.getBoolean(
                EXTRA_IS_LOGGED_IN,
                customCommand.customExtras.getBoolean(EXTRA_IS_LOGGED_IN, false)
            );
            Bundle extras = new Bundle();
            extras.putBoolean(EXTRA_IS_LOGGED_IN, isLoggedIn);
            updateSessionExtras(session, extras);
            audioService.notifyLibraryRootChanged("loginState=" + isLoggedIn);
        } else if (customCommand.customAction.equals(SET_PLAYLIST_STATE)) {
            boolean hasPlaylist = args.getBoolean("hasPlaylist", false);
            audioService.updatePlaylistState(hasPlaylist);
        } else if (customCommand.customAction.equals(SKIP_NEXT)) {
            audioService.triggerSkipNext();
        } else if (customCommand.customAction.equals(SKIP_PREVIOUS)) {
            audioService.triggerSkipPrevious();
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
        logAuthState("onGetLibraryRoot browser=" + browser.getPackageName(), session);
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
        if (MEDIA_ID_NOW_PLAYING.equals(mediaId)) {
            MediaItem nowPlaying = buildNowPlayingItem();
            if (nowPlaying != null) {
                return Futures.immediateFuture(LibraryResult.ofItem(nowPlaying, null));
            }
        }

        if (NODE_LOGIN.equals(mediaId)) {
            if (isLoggedIn(session)) {
                return Futures.immediateFuture(
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED)
                );
            }
            MediaItem loginRoot = buildBrowsableItem(
                NODE_LOGIN,
                "Sign in on your phone",
                "Open GH Player on your phone to continue"
            );
            return Futures.immediateFuture(LibraryResult.ofItem(loginRoot, null));
        }

        return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED));
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public ListenableFuture<MediaItemsWithStartPosition> onSetMediaItems(
        MediaSession mediaSession,
        MediaSession.ControllerInfo controller,
        List<MediaItem> mediaItems,
        int startIndex,
        long startPositionMs
    ) {
        if (mediaItems == null || mediaItems.isEmpty()) {
            return Futures.immediateFuture(
                new MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            );
        }

        int safeStartIndex = startIndex >= 0 ? startIndex : 0;
        MediaItem selectedItem = mediaItems.get(Math.min(safeStartIndex, mediaItems.size() - 1));
        String mediaId = selectedItem.mediaId;
        if (mediaId == null) {
            return Futures.immediateFuture(
                new MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            );
        }

        if (MEDIA_ID_NOW_PLAYING.equals(mediaId)) {
            MediaItem current = audioService.getCurrentMediaItem();
            if (current != null) {
                long positionMs = audioService.getCurrentPositionMs();
                return Futures.immediateFuture(
                    new MediaItemsWithStartPosition(ImmutableList.of(current), 0, positionMs)
                );
            }
        }

        if (mediaId.startsWith(EPISODE_LATEST_PREFIX)) {
            String episodeId = mediaId.substring(EPISODE_LATEST_PREFIX.length());
            return buildQueueFromLatest(mediaItems, episodeId, startPositionMs);
        }

        if (mediaId.startsWith(EPISODE_CONTINUE_PREFIX)) {
            String episodeId = mediaId.substring(EPISODE_CONTINUE_PREFIX.length());
            return buildQueueFromContinue(mediaItems, episodeId);
        }

        if (mediaId.startsWith(EPISODE_SERIES_PREFIX)) {
            String remainder = mediaId.substring(EPISODE_SERIES_PREFIX.length());
            String[] parts = remainder.split("/", 2);
            if (parts.length == 2) {
                return buildQueueFromSeries(mediaItems, parts[0], parts[1], startPositionMs);
            }
        }

        return Futures.immediateFuture(
            new MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
        );
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
        logAuthState("onGetChildren parentId=" + parentId + " loggedIn=" + loggedIn, session);
        if (!loggedIn) {
            Log.i(TAG, "onGetChildren: not logged in, showing login item");
            ImmutableList<MediaItem> items = ImmutableList.of(
                buildBrowsableItem(
                    NODE_LOGIN,
                    "Sign in on your phone",
                    "Open GH Player on your phone to continue"
                )
            );
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params));
        }

        if (ROOT_ID.equals(parentId)) {
            ImmutableList.Builder<MediaItem> items = ImmutableList.builder();
            MediaItem nowPlaying = buildNowPlayingItem();
            if (nowPlaying != null) {
                items.add(nowPlaying);
            }
            items.add(
                buildBrowsableItem(NODE_SERIES, "Series", "Browse series"),
                buildBrowsableItem(NODE_CONTINUE, "Continue Listening", "Pick up where you left off"),
                buildBrowsableItem(NODE_EPISODES, "Episodes", "Latest episodes")
            );
            return Futures.immediateFuture(LibraryResult.ofItemList(items.build(), params));
        }

        if (NODE_LOGIN.equals(parentId)) {
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params));
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

        if (NODE_CONTINUE.equals(parentId)) {
            return fetchContinueListening(params);
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
        boolean extrasLoggedIn = extras != null && extras.getBoolean(EXTRA_IS_LOGGED_IN, false);
        if (extrasLoggedIn) {
            return true;
        }

        AutoAuthConfig config = AutoAuthStore.load(audioService.getApplicationContext());
        if (config == null || config.accessToken == null || config.accessToken.isEmpty()) {
            return false;
        }
        return !AutoAuthStore.isTokenExpired(config.accessToken);
    }

    private void logAuthState(String context, MediaLibrarySession session) {
        Bundle extras = session.getSessionExtras();
        boolean extrasLoggedIn = extras != null && extras.getBoolean(EXTRA_IS_LOGGED_IN, false);
        AutoAuthConfig config = AutoAuthStore.load(audioService.getApplicationContext());
        boolean hasToken = config != null && config.accessToken != null && !config.accessToken.isEmpty();
        boolean expired = hasToken && AutoAuthStore.isTokenExpired(config.accessToken);
        Log.i(
            TAG,
            context + " extrasLoggedIn=" + extrasLoggedIn + " hasToken=" + hasToken + " expired=" + expired
        );
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
                Log.i(TAG, "fetchSeriesItems: requesting series");
                List<SupabaseApi.AutoPlaylist> playlists = api.fetchSeries(DEFAULT_PAGE_SIZE);
                Log.i(TAG, "fetchSeriesItems: got " + playlists.size());
                if (playlists.isEmpty()) {
                    Log.i(TAG, "fetchSeriesItems: empty series, falling back to public playlists");
                    playlists = api.fetchPublicPlaylists(DEFAULT_PAGE_SIZE);
                    Log.i(TAG, "fetchSeriesItems: fallback got " + playlists.size());
                }
                ImmutableList.Builder<MediaItem> items = ImmutableList.builder();
                for (SupabaseApi.AutoPlaylist playlist : playlists) {
                    MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                        .setTitle(playlist.title)
                        .setSubtitle(playlist.description)
                        .setIsBrowsable(true)
                        .setIsPlayable(false);

                    applyArtwork(metadata, playlist.coverImagePath);

                    items.add(
                        new MediaItem.Builder()
                            .setMediaId("series/" + playlist.id)
                            .setMediaMetadata(metadata.build())
                            .build()
                    );
                }
                future.set(LibraryResult.ofItemList(items.build(), params));
            } catch (Exception ex) {
                Log.w(TAG, "fetchSeriesItems failed", ex);
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params));
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
                Log.i(TAG, "fetchLatestEpisodes: requesting latest");
                List<SupabaseApi.AutoEpisode> episodes = api.fetchLatestEpisodes(DEFAULT_PAGE_SIZE);
                Log.i(TAG, "fetchLatestEpisodes: got " + episodes.size());
                ImmutableList.Builder<MediaItem> items = ImmutableList.builder();
                for (SupabaseApi.AutoEpisode episode : episodes) {
                    items.add(buildEpisodeItem(episode, EPISODE_LATEST_PREFIX + episode.id, null));
                }
                future.set(LibraryResult.ofItemList(items.build(), params));
            } catch (Exception ex) {
                Log.w(TAG, "fetchLatestEpisodes failed", ex);
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params));
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
                Log.i(TAG, "fetchSeriesEpisodes: " + seriesId);
                String playlistCover = api.fetchPlaylistCover(seriesId);
                List<SupabaseApi.AutoEpisode> episodes = api.fetchSeriesEpisodes(seriesId, DEFAULT_PAGE_SIZE);
                Log.i(TAG, "fetchSeriesEpisodes: got " + episodes.size());
                ImmutableList.Builder<MediaItem> items = ImmutableList.builder();
                for (SupabaseApi.AutoEpisode episode : episodes) {
                    items.add(
                        buildEpisodeItem(
                            episode,
                            EPISODE_SERIES_PREFIX + seriesId + "/" + episode.id,
                            playlistCover
                        )
                    );
                }
                future.set(LibraryResult.ofItemList(items.build(), params));
            } catch (Exception ex) {
                Log.w(TAG, "fetchSeriesEpisodes failed", ex);
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params));
            }
        });
        return future;
    }

    private ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> fetchContinueListening(
        @Nullable LibraryParams params
    ) {
        SettableFuture<LibraryResult<ImmutableList<MediaItem>>> future = SettableFuture.create();
        libraryExecutor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(audioService.getApplicationContext());
                Log.i(TAG, "fetchContinueListening: requesting progress");
                List<SupabaseApi.AutoContinueItem> items = api.fetchContinueListening(DEFAULT_PAGE_SIZE);
                Log.i(TAG, "fetchContinueListening: got " + items.size());
                ImmutableList.Builder<MediaItem> result = ImmutableList.builder();
                for (SupabaseApi.AutoContinueItem item : items) {
                    if (item.episode == null) {
                        continue;
                    }
                    result.add(
                        buildEpisodeItem(
                            item.episode,
                            EPISODE_CONTINUE_PREFIX + item.episode.id,
                            null
                        )
                    );
                }
                future.set(LibraryResult.ofItemList(result.build(), params));
            } catch (Exception ex) {
                Log.w(TAG, "fetchContinueListening failed", ex);
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params));
            }
        });
        return future;
    }

    private MediaItem buildEpisodeItem(
        SupabaseApi.AutoEpisode episode,
        String mediaId,
        @Nullable String fallbackArtwork
    ) {
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
            : (fallbackArtwork != null && !fallbackArtwork.isEmpty()
                ? fallbackArtwork
                : episode.podcastImageUrl);
        applyArtwork(metadata, artwork);

        MediaItem.Builder itemBuilder = new MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.build());

        if (episode.audioUrl != null && !episode.audioUrl.isEmpty()) {
            itemBuilder.setUri(Uri.parse(episode.audioUrl));
        }

        return itemBuilder.build();
    }

    private MediaItem buildNowPlayingItem() {
        MediaItem current = audioService.getCurrentMediaItem();
        if (current == null) {
            return null;
        }

        MediaMetadata currentMetadata = current.mediaMetadata;
        String currentTitle = currentMetadata.title != null ? currentMetadata.title.toString() : null;

        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
            .setTitle("Now Playing")
            .setSubtitle(currentTitle != null ? currentTitle : "Open current playback")
            .setIsBrowsable(false)
            .setIsPlayable(true);

        if (currentMetadata.artist != null) {
            metadata.setArtist(currentMetadata.artist);
        }
        if (currentMetadata.albumTitle != null) {
            metadata.setAlbumTitle(currentMetadata.albumTitle);
        }
        if (currentMetadata.artworkUri != null) {
            metadata.setArtworkUri(currentMetadata.artworkUri);
        }
        if (currentMetadata.artworkData != null) {
            metadata.maybeSetArtworkData(
                currentMetadata.artworkData,
                currentMetadata.artworkDataType
            );
        }

        return current.buildUpon()
            .setMediaId(MEDIA_ID_NOW_PLAYING)
            .setMediaMetadata(metadata.build())
            .build();
    }

    private ListenableFuture<MediaItemsWithStartPosition> buildQueueFromLatest(
        List<MediaItem> fallbackItems,
        String episodeId,
        long startPositionMs
    ) {
        SettableFuture<MediaItemsWithStartPosition> future = SettableFuture.create();
        libraryExecutor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(audioService.getApplicationContext());
                List<SupabaseApi.AutoEpisode> episodes = api.fetchLatestEpisodes(DEFAULT_PAGE_SIZE);
                List<MediaItem> items = new ArrayList<>();
                int startAt = 0;
                for (int i = 0; i < episodes.size(); i++) {
                    SupabaseApi.AutoEpisode episode = episodes.get(i);
                    items.add(
                        buildEpisodeItem(
                            episode,
                            EPISODE_LATEST_PREFIX + episode.id,
                            null
                        )
                    );
                    if (episode.id.equals(episodeId)) {
                        startAt = i;
                    }
                }
                audioService.updatePlaylistState(items.size() > 1);
                future.set(new MediaItemsWithStartPosition(items, startAt, startPositionMs));
            } catch (Exception ex) {
                Log.w(TAG, "buildQueueFromLatest failed", ex);
                future.set(new MediaItemsWithStartPosition(fallbackItems, 0, startPositionMs));
            }
        });
        return future;
    }

    private ListenableFuture<MediaItemsWithStartPosition> buildQueueFromSeries(
        List<MediaItem> fallbackItems,
        String playlistId,
        String episodeId,
        long startPositionMs
    ) {
        SettableFuture<MediaItemsWithStartPosition> future = SettableFuture.create();
        libraryExecutor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(audioService.getApplicationContext());
                String playlistCover = api.fetchPlaylistCover(playlistId);
                List<SupabaseApi.AutoEpisode> episodes = api.fetchSeriesEpisodes(playlistId, DEFAULT_PAGE_SIZE);
                List<MediaItem> items = new ArrayList<>();
                int startAt = 0;
                for (int i = 0; i < episodes.size(); i++) {
                    SupabaseApi.AutoEpisode episode = episodes.get(i);
                    items.add(
                        buildEpisodeItem(
                            episode,
                            EPISODE_SERIES_PREFIX + playlistId + "/" + episode.id,
                            playlistCover
                        )
                    );
                    if (episode.id.equals(episodeId)) {
                        startAt = i;
                    }
                }
                audioService.updatePlaylistState(items.size() > 1);
                future.set(new MediaItemsWithStartPosition(items, startAt, startPositionMs));
            } catch (Exception ex) {
                Log.w(TAG, "buildQueueFromSeries failed", ex);
                future.set(new MediaItemsWithStartPosition(fallbackItems, 0, startPositionMs));
            }
        });
        return future;
    }

    private ListenableFuture<MediaItemsWithStartPosition> buildQueueFromContinue(
        List<MediaItem> fallbackItems,
        String episodeId
    ) {
        SettableFuture<MediaItemsWithStartPosition> future = SettableFuture.create();
        libraryExecutor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(audioService.getApplicationContext());
                List<SupabaseApi.AutoContinueItem> progressItems = api.fetchContinueListening(DEFAULT_PAGE_SIZE);
                List<MediaItem> items = new ArrayList<>();
                int startAt = 0;
                long resumePositionMs = C.TIME_UNSET;
                for (int i = 0; i < progressItems.size(); i++) {
                    SupabaseApi.AutoContinueItem progressItem = progressItems.get(i);
                    if (progressItem.episode == null) {
                        continue;
                    }
                    items.add(
                        buildEpisodeItem(
                            progressItem.episode,
                            EPISODE_CONTINUE_PREFIX + progressItem.episode.id,
                            null
                        )
                    );
                    if (progressItem.episode.id.equals(episodeId)) {
                        startAt = i;
                        resumePositionMs = Math.max(0, progressItem.progressMs);
                    }
                }
                audioService.updatePlaylistState(items.size() > 1);
                long startPos = resumePositionMs == C.TIME_UNSET ? 0 : resumePositionMs;
                future.set(new MediaItemsWithStartPosition(items, startAt, startPos));
            } catch (Exception ex) {
                Log.w(TAG, "buildQueueFromContinue failed", ex);
                future.set(new MediaItemsWithStartPosition(fallbackItems, 0, C.TIME_UNSET));
            }
        });
        return future;
    }

    private String normalizeArtworkUrl(String artworkUrl) {
        if (artworkUrl == null) {
            return null;
        }

        String trimmed = artworkUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("content://") || trimmed.startsWith("file://") ||
            trimmed.startsWith("android.resource://")) {
            return trimmed;
        }

        AutoAuthConfig config = AutoAuthStore.load(audioService.getApplicationContext());
        if (config == null || config.supabaseUrl == null || config.supabaseUrl.isEmpty()) {
            return trimmed;
        }

        String base = config.supabaseUrl.endsWith("/")
            ? config.supabaseUrl.substring(0, config.supabaseUrl.length() - 1)
            : config.supabaseUrl;
        String pathValue = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        if (pathValue.startsWith("storage/v1/")) {
            return base + "/" + pathValue;
        }
        if (pathValue.startsWith("public/")) {
            pathValue = pathValue.substring("public/".length());
        }
        return base + "/storage/v1/object/public/" + pathValue;
    }

    private void applyArtwork(MediaMetadata.Builder metadata, String artworkUrl) {
        String resolvedUrl = normalizeArtworkUrl(artworkUrl);
        if (resolvedUrl == null || resolvedUrl.isEmpty()) {
            return;
        }

        metadata.setArtworkUri(Uri.parse(resolvedUrl));
        byte[] artworkData = getArtworkData(resolvedUrl);
        if (artworkData != null) {
            metadata.maybeSetArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
        }
    }

    private byte[] getArtworkData(String artworkUrl) {
        if (artworkUrl == null || artworkUrl.isEmpty()) {
            return null;
        }

        byte[] cached = artworkCache.get(artworkUrl);
        if (cached != null) {
            return cached;
        }

        byte[] downloaded = downloadArtworkBytes(artworkUrl);
        if (downloaded != null) {
            artworkCache.put(artworkUrl, downloaded);
        }
        return downloaded;
    }

    private byte[] compressArtwork(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) {
            return null;
        }

        Bitmap bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length);
        if (bitmap == null) {
            Log.w(TAG, "compressArtwork: decode failed");
            return null;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int maxDim = Math.max(width, height);
        Bitmap working = bitmap;
        if (maxDim > ARTWORK_MAX_DIM_PX) {
            float scale = (float) ARTWORK_MAX_DIM_PX / (float) maxDim;
            int targetW = Math.max(1, Math.round(width * scale));
            int targetH = Math.max(1, Math.round(height * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
            if (scaled != bitmap) {
                bitmap.recycle();
            }
            working = scaled;
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Bitmap.CompressFormat format = working.hasAlpha()
            ? Bitmap.CompressFormat.PNG
            : Bitmap.CompressFormat.JPEG;
        int quality = format == Bitmap.CompressFormat.JPEG ? ARTWORK_COMPRESS_QUALITY : 100;
        boolean ok = working.compress(format, quality, outputStream);
        working.recycle();
        if (!ok) {
            Log.w(TAG, "compressArtwork: compress failed");
            return null;
        }

        byte[] result = outputStream.toByteArray();
        if (result.length > ARTWORK_MAX_BYTES) {
            Log.w(TAG, "compressArtwork: result too large " + result.length);
            return null;
        }
        return result;
    }

    private byte[] downloadArtworkBytes(String artworkUrl) {
        if (artworkUrl == null || artworkUrl.isEmpty()) {
            return null;
        }
        if (!artworkUrl.startsWith("http://") && !artworkUrl.startsWith("https://")) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(artworkUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "image/*");

            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                Log.w(TAG, "downloadArtworkBytes http " + response + " for " + artworkUrl);
                return null;
            }

            int contentLength = connection.getContentLength();
            if (contentLength > ARTWORK_MAX_BYTES && contentLength > 0) {
                Log.w(TAG, "downloadArtworkBytes too large " + contentLength + " for " + artworkUrl);
                return null;
            }

            int bufferLength = 8 * 1024;
            byte[] buffer = new byte[bufferLength];
            int readLength;
            int totalRead = 0;
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            InputStream inputStream = connection.getInputStream();

            while ((readLength = inputStream.read(buffer, 0, bufferLength)) != -1) {
                totalRead += readLength;
                if (totalRead > ARTWORK_MAX_BYTES) {
                    inputStream.close();
                    Log.w(TAG, "downloadArtworkBytes exceeded limit for " + artworkUrl);
                    return null;
                }
                outputStream.write(buffer, 0, readLength);
            }

            inputStream.close();
            byte[] rawBytes = outputStream.toByteArray();
            byte[] compressed = compressArtwork(rawBytes);
            return compressed != null ? compressed : rawBytes;
        } catch (Exception ex) {
            Log.w(TAG, "downloadArtworkBytes failed: " + artworkUrl, ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    private void updateSessionExtras(MediaSession session, Bundle extras) {
        Bundle current = session.getSessionExtras();
        Bundle merged = new Bundle(current != null ? current : Bundle.EMPTY);
        merged.putAll(extras);
        session.setSessionExtras(merged);
    }
}
