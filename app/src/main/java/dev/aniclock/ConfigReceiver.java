package dev.aniclock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Runs inside System UI. Receives the settings string from the AniClock app and answers the ordered broadcast
 * with RESULT_OK ("module is active") plus what the lock screen hook found, for the app's status card.
 */
final class ConfigReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.aniclock.action.CONFIG";
    static final String EXTRA = "cfg";
    static final String EXTRA_ECHO = "cfg_echo";
    /** Human-readable description of the replaced system clock, or empty if none was found yet. */
    static final String EXTRA_CLOCK = "clock";
    /** Number of digit images System UI has loaded, -1 if it could not read them. */
    static final String EXTRA_GLYPHS = "glyphs";
    /** Why the module switched itself off in System UI ("safe" for the crash-loop guard), or empty. */
    static final String EXTRA_ERROR = "error";
    private static final String APP = BuildConfig.APPLICATION_ID;

    @Override public void onReceive(Context context, Intent intent) {
        try {
            handle(context, intent);
        } catch (Throwable t) {
            android.util.Log.e(ClockController.TAG, "config receiver", t);
        }
    }

    private void handle(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        // Android 14+: the app shares its identity with the broadcast, so only our own app is accepted.
        if (Build.VERSION.SDK_INT >= 34 && !APP.equals(getSentFromPackage())) return;
        String raw = intent.getStringExtra(EXTRA);
        if (raw == null || raw.length() > 512) return;
        ConfigStore.update(context, Config.parse(raw));
        if (isOrderedBroadcast()) {
            Bundle result = getResultExtras(true);
            result.putString(EXTRA_ECHO, raw);
            result.putString(EXTRA_CLOCK, ClockController.diagnostics());
            result.putInt(EXTRA_GLYPHS, ClockController.loadedGlyphs());
            result.putString(EXTRA_ERROR, ConfigStore.safeMode() ? "safe" : ClockController.error());
            setResultCode(Activity.RESULT_OK);
        }
    }
}
