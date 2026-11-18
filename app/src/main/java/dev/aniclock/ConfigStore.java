package dev.aniclock;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide, live copy of the settings inside System UI. Listeners are called on the main thread. */
final class ConfigStore {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    /** "starts,firstStartMillis" of recent System UI starts with the module, for the crash-loop guard. */
    static final String GUARD_KEY = "aniclock_guard";
    private static final int GUARD_STARTS = 3;
    private static final long GUARD_WINDOW_MS = 3 * 60_000L, STABLE_MS = 90_000L;
    private static volatile Config current = new Config();
    private static volatile boolean safeMode;
    private static boolean registered;

    private ConfigStore() { }

    static Config get() {
        return current;
    }

    /**
     * True when System UI was restarted {@value #GUARD_STARTS} times within a few minutes with the module loaded
     * (a crash loop). The module then stays off until System UI restarts normally again.
     */
    static boolean safeMode() {
        return safeMode;
    }

    private static void checkCrashLoop(ContentResolver cr) {
        try {
            long now = System.currentTimeMillis();
            int starts = 0;
            long first = now;
            String g = Settings.Secure.getString(cr, GUARD_KEY);
            if (g != null && g.contains(",")) {
                String[] p = g.split(",");
                starts = Integer.parseInt(p[0].trim());
                first = Long.parseLong(p[1].trim());
            }
            if (now - first > GUARD_WINDOW_MS || now < first) {
                starts = 0;
                first = now;
            }
            starts++;
            Settings.Secure.putString(cr, GUARD_KEY, starts + "," + first);
            if (starts >= GUARD_STARTS) {
                safeMode = true;
                android.util.Log.w(ClockController.TAG, "System UI restarted " + starts + " times: module paused");
            }
            // Survived long enough: this start was fine, forget the counter.
            MAIN.postDelayed(() -> {
                try {
                    Settings.Secure.putString(cr, GUARD_KEY, "0," + System.currentTimeMillis());
                } catch (Throwable ignored) {
                    // the counter then simply expires
                }
            }, STABLE_MS);
        } catch (Throwable ignored) {
            // no guard available; the module still works
        }
    }

    static void addListener(Runnable r) {
        LISTENERS.addIfAbsent(r);
    }

    static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    /** Applies new settings immediately and stores them (System UI may write Settings.Secure) to survive restarts. */
    static void update(Context context, Config c) {
        set(c);
        try {
            Settings.Secure.putString(context.getContentResolver(), Config.KEY, c.serialize());
        } catch (Throwable ignored) {
            // not persisted: the new values still apply until System UI restarts
        }
    }

    private static void set(Config c) {
        String serialized = c.serialize();
        if (serialized.equals(current.serialize())) return;
        current = Config.parse(serialized);
        MAIN.post(() -> {
            for (Runnable r : LISTENERS) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                    // a broken listener must not affect the others
                }
            }
        });
    }

    /**
     * Called from the lock screen hook. Restores stored settings and starts listening for the app.
     * The receiver must be exported: the broadcast comes from the settings app, a different process/UID.
     * Android 12-12L has no RECEIVER_EXPORTED flag, so the plain overload is the exported equivalent there.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    static synchronized void ensure(Context context) {
        if (registered) return;
        registered = true;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final ContentResolver cr = app.getContentResolver();
        checkCrashLoop(cr);
        current = Config.read(cr);
        try {
            cr.registerContentObserver(Settings.Secure.getUriFor(Config.KEY), false, new ContentObserver(MAIN) {
                @Override public void onChange(boolean selfChange) {
                    set(Config.read(cr));
                }
            });
        } catch (Throwable ignored) {
            // changes made outside the app (adb) are picked up after a restart only
        }
        try {
            IntentFilter filter = new IntentFilter(ConfigReceiver.ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(new ConfigReceiver(), filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(new ConfigReceiver(), filter);
            }
        } catch (Throwable ignored) {
            // without the receiver the app cannot reach System UI
        }
    }
}
