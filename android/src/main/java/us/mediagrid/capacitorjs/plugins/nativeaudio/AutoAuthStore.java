package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.content.Context;
import android.content.SharedPreferences;

public class AutoAuthStore {
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
    }

    public static AutoAuthConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new AutoAuthConfig(
            prefs.getString(KEY_SUPABASE_URL, null),
            prefs.getString(KEY_SUPABASE_ANON_KEY, null),
            prefs.getString(KEY_ACCESS_TOKEN, null)
        );
    }
}
