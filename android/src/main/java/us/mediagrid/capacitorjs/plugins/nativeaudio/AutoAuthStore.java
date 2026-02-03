package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class AutoAuthStore {
    private static final String TAG = "AutoAuthStore";
    private static final String PREFS_NAME = "gh_auto_auth";
    private static final String KEY_SUPABASE_URL = "supabase_url";
    private static final String KEY_SUPABASE_ANON_KEY = "supabase_anon_key";
    private static final String KEY_ACCESS_TOKEN = "access_token";

    public static void save(Context context, String supabaseUrl, String supabaseAnonKey, String accessToken) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SUPABASE_URL, supabaseUrl)
            .putString(KEY_SUPABASE_ANON_KEY, supabaseAnonKey)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply();
        Log.i(
            TAG,
            "save: url=" + (supabaseUrl != null) + " anonKey=" + (supabaseAnonKey != null) + " token=" + (accessToken != null && !accessToken.isEmpty())
        );
    }

    public static AutoAuthConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        AutoAuthConfig config = new AutoAuthConfig(
            prefs.getString(KEY_SUPABASE_URL, null),
            prefs.getString(KEY_SUPABASE_ANON_KEY, null),
            prefs.getString(KEY_ACCESS_TOKEN, null)
        );
        Log.i(
            TAG,
            "load: url=" + (config.supabaseUrl != null) + " anonKey=" + (config.supabaseAnonKey != null) + " token=" + (config.accessToken != null && !config.accessToken.isEmpty())
        );
        return config;
    }

    public static String extractUserId(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            return null;
        }

        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP);
            String payload = new String(decoded, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(payload);
            return json.optString("sub", null);
        } catch (Exception ex) {
            Log.w(TAG, "extractUserId failed", ex);
            return null;
        }
    }

    public static long extractExpiry(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            return 0;
        }

        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return 0;
            }
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP);
            String payload = new String(decoded, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(payload);
            return json.optLong("exp", 0);
        } catch (Exception ex) {
            Log.w(TAG, "extractExpiry failed", ex);
            return 0;
        }
    }

    public static boolean isTokenExpired(String accessToken) {
        long expSeconds = extractExpiry(accessToken);
        if (expSeconds <= 0) {
            return false;
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        return expSeconds <= nowSeconds;
    }
}
