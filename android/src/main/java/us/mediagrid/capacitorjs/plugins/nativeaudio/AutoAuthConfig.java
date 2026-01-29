package us.mediagrid.capacitorjs.plugins.nativeaudio;

public class AutoAuthConfig {
    public final String supabaseUrl;
    public final String supabaseAnonKey;
    public final String accessToken;

    public AutoAuthConfig(String supabaseUrl, String supabaseAnonKey, String accessToken) {
        this.supabaseUrl = supabaseUrl;
        this.supabaseAnonKey = supabaseAnonKey;
        this.accessToken = accessToken;
    }

    public boolean isValid() {
        return supabaseUrl != null && !supabaseUrl.isEmpty() &&
            supabaseAnonKey != null && !supabaseAnonKey.isEmpty() &&
            accessToken != null && !accessToken.isEmpty();
    }
}
