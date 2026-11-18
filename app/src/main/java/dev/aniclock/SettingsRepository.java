package dev.aniclock;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

/**
 * Data layer of the settings app: the single source of truth for {@link Config}.
 * Settings are kept in app preferences and delivered to System UI with an ordered broadcast; the module's answer
 * doubles as the "module is active" check and reports which system clock it replaces.
 */
final class SettingsRepository {
    /** What System UI answered. */
    static final class Status {
        final boolean linked;
        /** "kind|class|id|text|sizeDp|views|dates|dateInfo", empty if no clock was found yet. */
        final String clock;
        /** Digit images loaded by System UI, -1 if unknown. */
        final int glyphs;
        /** "safe" (crash-loop guard), an error text, or empty. */
        final String error;

        Status(boolean linked, String clock, int glyphs, String error) {
            this.linked = linked;
            this.clock = clock == null ? "" : clock;
            this.glyphs = glyphs;
            this.error = error == null ? "" : error;
        }
    }

    /** Called on the main thread. */
    interface Callback {
        void onDelivered(Status status);
    }

    private static final String PREFS = "settings", KEY = "cfg";

    private final Context context;
    private final Handler main;

    SettingsRepository(Context context, Handler main) {
        this.context = context.getApplicationContext();
        this.main = main;
    }

    Config load() {
        String saved = prefs().getString(KEY, null);
        return saved != null ? Config.parse(saved) : Config.read(context.getContentResolver());
    }

    /** Persists locally, then hands the settings to System UI. */
    void save(Config cfg, Callback callback) {
        String serialized = cfg.serialize();
        prefs().edit().putString(KEY, serialized).apply();
        deliver(serialized, callback);
    }

    /** Sends the settings to System UI without changing anything locally (connection check). */
    void deliver(String serialized, Callback callback) {
        Intent i = new Intent(ConfigReceiver.ACTION)
                .setPackage("com.android.systemui")
                .putExtra(ConfigReceiver.EXTRA, serialized);
        BroadcastReceiver ack = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent in) {
                Bundle result = getResultExtras(false);
                // Ignore answers to outdated sends: a newer value is already on its way.
                String echo = result != null ? result.getString(ConfigReceiver.EXTRA_ECHO) : null;
                if (echo != null && !echo.equals(prefs().getString(KEY, echo))) return;
                boolean ok = getResultCode() == Activity.RESULT_OK;
                callback.onDelivered(new Status(ok,
                        ok && result != null ? result.getString(ConfigReceiver.EXTRA_CLOCK) : "",
                        ok && result != null ? result.getInt(ConfigReceiver.EXTRA_GLYPHS, -1) : -1,
                        ok && result != null ? result.getString(ConfigReceiver.EXTRA_ERROR) : ""));
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle opts = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                context.sendOrderedBroadcast(i, null, opts, ack, main, Activity.RESULT_CANCELED, null, null);
            } else {
                context.sendOrderedBroadcast(i, null, ack, main, Activity.RESULT_CANCELED, null, null);
            }
        } catch (Throwable t) {
            callback.onDelivered(new Status(false, "", -1, ""));
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
