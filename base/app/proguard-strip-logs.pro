# Stable releases stay quiet: Log.v/d/i calls (bridge traffic, track names,
# per-listen reports) are stripped; Log.w/e keep real failures.
# Only added for stable builds (see buildTypes.release): betas keep these lines,
# since the beta "send logs" report reads them from logcat (BetaLogs.kt).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
