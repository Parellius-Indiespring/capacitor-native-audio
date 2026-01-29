package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.content.Context;
import android.util.Log;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class SupabaseApi {
    private static final String TAG = "SupabaseApi";
    private final Context context;

    public SupabaseApi(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<AutoPlaylist> fetchSeries(int limit) throws Exception {
        AutoAuthConfig config = AutoAuthStore.load(context);
        if (config == null || !config.isValid()) {
            Log.w(TAG, "fetchSeries: missing auth config");
            return new ArrayList<>();
        }

        Uri uri = Uri.parse(config.supabaseUrl)
            .buildUpon()
            .appendEncodedPath("rest/v1/playlists")
            .appendQueryParameter("select", "id,title,description,image_url")
            .appendQueryParameter("category", "eq.series")
            .appendQueryParameter("is_published", "eq.true")
            .appendQueryParameter("visibility", "eq.public")
            .appendQueryParameter("order", "updated_at.desc")
            .appendQueryParameter("limit", String.valueOf(limit))
            .build();

        JSONArray rows = fetchJsonArray(uri.toString(), config);
        List<AutoPlaylist> results = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            results.add(
                new AutoPlaylist(
                    row.optString("id"),
                    row.optString("title"),
                    row.optString("description"),
                    row.optString("image_url")
                )
            );
        }
        return results;
    }

    public List<AutoEpisode> fetchLatestEpisodes(int limit) throws Exception {
        AutoAuthConfig config = AutoAuthStore.load(context);
        if (config == null || !config.isValid()) {
            Log.w(TAG, "fetchLatestEpisodes: missing auth config");
            return new ArrayList<>();
        }

        Uri uri = Uri.parse(config.supabaseUrl)
            .buildUpon()
            .appendEncodedPath("rest/v1/episodes")
            .appendQueryParameter(
                "select",
                "id,title,summary,published_at,duration_seconds,image_url,audio_url,podcasts(title,image_url)"
            )
            .appendQueryParameter("order", "published_at.desc")
            .appendQueryParameter("limit", String.valueOf(limit))
            .build();

        JSONArray rows = fetchJsonArray(uri.toString(), config);
        List<AutoEpisode> results = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject podcast = row.optJSONObject("podcasts");
            results.add(
                new AutoEpisode(
                    row.optString("id"),
                    row.optString("title"),
                    row.optString("summary"),
                    row.optString("image_url"),
                    row.optString("audio_url"),
                    podcast != null ? podcast.optString("title") : null,
                    podcast != null ? podcast.optString("image_url") : null
                )
            );
        }
        return results;
    }

    public List<AutoEpisode> fetchSeriesEpisodes(String playlistId, int limit) throws Exception {
        AutoAuthConfig config = AutoAuthStore.load(context);
        if (config == null || !config.isValid()) {
            Log.w(TAG, "fetchSeriesEpisodes: missing auth config");
            return new ArrayList<>();
        }

        Uri uri = Uri.parse(config.supabaseUrl)
            .buildUpon()
            .appendEncodedPath("rest/v1/playlist_items")
            .appendQueryParameter(
                "select",
                "id,sort_order,episodes(id,title,summary,image_url,audio_url,podcasts(title,image_url))"
            )
            .appendQueryParameter("playlist_id", "eq." + playlistId)
            .appendQueryParameter("order", "sort_order.asc")
            .appendQueryParameter("limit", String.valueOf(limit))
            .build();

        JSONArray rows = fetchJsonArray(uri.toString(), config);
        List<AutoEpisode> results = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject episode = row.optJSONObject("episodes");
            if (episode == null) {
                continue;
            }
            JSONObject podcast = episode.optJSONObject("podcasts");
            results.add(
                new AutoEpisode(
                    episode.optString("id"),
                    episode.optString("title"),
                    episode.optString("summary"),
                    episode.optString("image_url"),
                    episode.optString("audio_url"),
                    podcast != null ? podcast.optString("title") : null,
                    podcast != null ? podcast.optString("image_url") : null
                )
            );
        }
        return results;
    }

    private JSONArray fetchJsonArray(String urlString, AutoAuthConfig config) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("apikey", config.supabaseAnonKey);
        connection.setRequestProperty("Authorization", "Bearer " + config.accessToken);

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String payload = readStream(stream);
        if (code < 200 || code >= 300) {
            Log.e(TAG, "Supabase error " + code + " for " + urlString + ": " + payload);
            throw new RuntimeException("Supabase error " + code + ": " + payload);
        }
        return new JSONArray(payload);
    }

    private String readStream(InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    public static class AutoPlaylist {
        public final String id;
        public final String title;
        public final String description;
        public final String imageUrl;

        public AutoPlaylist(String id, String title, String description, String imageUrl) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.imageUrl = imageUrl;
        }
    }

    public static class AutoEpisode {
        public final String id;
        public final String title;
        public final String summary;
        public final String imageUrl;
        public final String audioUrl;
        public final String podcastTitle;
        public final String podcastImageUrl;

        public AutoEpisode(
            String id,
            String title,
            String summary,
            String imageUrl,
            String audioUrl,
            String podcastTitle,
            String podcastImageUrl
        ) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.imageUrl = imageUrl;
            this.audioUrl = audioUrl;
            this.podcastTitle = podcastTitle;
            this.podcastImageUrl = podcastImageUrl;
        }
    }
}
