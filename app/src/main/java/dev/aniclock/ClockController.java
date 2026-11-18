package dev.aniclock;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Layout;
import android.text.NoCopySpan;
import android.text.SpanWatcher;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.format.DateFormat;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Runs inside System UI, one instance per lock screen root view ("host").
 *
 * <p>Rules:
 * <ul>
 *   <li>Only {@link TextView}s showing the time (or big digits) are replaced. The system clock is hidden by making
 *       its text transparent; a hidden date line is simply not drawn (draw hook, see {@link #skipDraw}), so the
 *       system may show, re-inflate or fade it on screen on/off without it ever appearing. Its place stays, so
 *       nothing moves. Alpha 0 is only the fallback if the hook could not be installed.</li>
 *   <li>A shown date keeps its native text size and is only moved down when enlarged artwork reaches it.</li>
 *   <li>The clock is searched while the lock screen is visible (once per second and right after layout passes, so a
 *       freshly inflated clock is hidden before its first frame). Once found it stays hidden, also while the screen
 *       is off or unlocked, so turning the screen on never shows the old clock for a moment (1.4).</li>
 *   <li>If the system makes a hidden view visible again it is re-hidden before the next frame (rate limited, so
 *       there is no tug of war with system animations). Otherwise frames only read positions.</li>
 *   <li>The animation runs only while the lock screen is visible and the screen is on.</li>
 *   <li>Widgets right below the clock are moved down by as much as the digits reach lower than the system clock
 *       (their translation, on top of the system's own), so the bigger clock never covers them (1.5).</li>
 *   <li>Any unexpected error switches the module off for this System UI process and restores the views.</li>
 * </ul>
 */
final class ClockController implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    static final String TAG = "AniClock";
    static final String KIND_TIME = "time", KIND_DIGITS = "digits";

    private static final Pattern TIME = Pattern.compile(
            "\\s*(\\d{1,2})\\s*[:\uFF1A]\\s*\\d{2}(\\s*[:\uFF1A]\\s*\\d{2})?\\s*([AaPp]\\.?[Mm]\\.?|\u4E0A\u5348|\u4E0B\u5348)?\\s*");
    private static final Pattern DIGITS = Pattern.compile("\\s*\\d{1,2}\\s*");
    private static final float MIN_TEXT_DP = 24f, MARGIN_DP = 16f, WIDGET_GAP_DP = 8f;
    /** Class names (CamelCase) and resource ids (snake_case) of date views. */
    private static final Pattern DATE_CLASS = Pattern.compile("Date|Week|Lunar|Calendar");
    private static final Pattern DATE_ID = Pattern.compile("(^|_)(date|week|weekday|lunar|calendar|riqi|xingqi|nongli)");
    private static final int MAX_FRAME_FIXES = 120, MISSES_BEFORE_RELEASE = 3;
    private static final long LAYOUT_SCAN_MS = 200, TRANSITION_SCAN_MS = 2500;
    /** Views starting this far down the lock screen (shortcuts, unlock hint) are never moved. */
    private static final float PUSH_MAX_TOP = 0.8f, PUSH_MAX_BOTTOM = 0.92f, PUSH_TOLERANCE_DP = 4f;
    /** The clock briefly not found (e.g. while it is animated on screen on/off): the date stays hidden this long. */
    private static final long DATE_GRACE_MS = 2000;

    private static final WeakHashMap<View, ClockController> HOSTS = new WeakHashMap<>();
    /** System views hidden by some controller; another host never takes them. */
    private static final WeakHashMap<View, ClockController> CLAIMED = new WeakHashMap<>();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AniClock-loader");
        t.setDaemon(true);
        return t;
    });
    /** Module APK, from LSPosed's initZygote. */
    static volatile String modulePath;
    private static volatile String diagnostics = "";
    private static volatile String error = "";
    private static volatile int loadedGlyphs = -1;
    private static volatile boolean broken;
    /** Set once the OriginOS lock screen was seen; generic AOSP hosts then stay passive. */
    private static volatile boolean vendorSeen;
    /** Set by HookEntry once View.draw and ViewGroup.dispatchDraw are hooked: hidden dates are then not drawn at all. */
    static volatile boolean drawHooked;
    /** Guards the draw-time state below: the draw hook may also run off the main thread (snapshots). */
    private static final Object BLOCK = new Object();
    /** Date views found next to a replaced clock, not drawn while their controller blocks dates. */
    private static final WeakHashMap<View, Kept> KEPT = new WeakHashMap<>();
    /** Tracked stock clock TextViews mapped to their controller, for synchronous setText interception. */
    private static final WeakHashMap<TextView, ClockController> TRACKED = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> WRITING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** "class#id" of such dates: a re-inflated view like that is not drawn from its first frame, before any scan. */
    private static final Set<String> SIGNATURES = new HashSet<>();
    private static final Set<String> SIGNATURE_CLASSES = new HashSet<>();
    private static final WeakHashMap<View, String> SIGNATURE_OF = new WeakHashMap<>();
    /** Controllers blocking dates right now; while 0 the draw hook costs a single volatile read. */
    private static volatile int blockingHosts;

    private static final class Kept {
        final ClockController owner;
        int misses;

        Kept(ClockController owner) {
            this.owner = owner;
        }
    }

    private final ViewGroup host;
    private final boolean vendor;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Drawable.Callback spanCallback = new Drawable.Callback() {
        @Override public void invalidateDrawable(Drawable who) {
            if (!targets.isEmpty()) {
                TextView primary = targets.get(0);
                if (primary.isAttachedToWindow()) primary.invalidate();
            }
        }

        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
            main.postAtTime(what, when);
        }

        @Override public void unscheduleDrawable(Drawable who, Runnable what) {
            main.removeCallbacks(what);
        }
    };
    private final Runnable tick = () -> guard(this::tick);
    private final Runnable onConfig = () -> guard(this::onConfig);
    private final Runnable layoutScan = () -> guard(() -> refresh(true));
    /** SystemUI may inflate the clock/date in different frames; keep rescanning before those transition frames. */
    private final Runnable transitionScan = new Runnable() {
        @Override public void run() {
            guard(() -> {
                if (!attached || SystemClock.uptimeMillis() >= transitionScanUntil) return;
                scanBeforeDraw = true;
                host.invalidate();
                main.postDelayed(this, LAYOUT_SCAN_MS);
            });
        }
    };
    private final ViewTreeObserver.OnGlobalLayoutListener onLayout = () -> guard(this::onLayout);
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                beginTransitionScan();
            }
            main.post(tick);     // screen on/off, unlock: start/stop the animation right away
        }
    };
    private final List<TextView> targets = new ArrayList<>();
    private final List<View> dates = new ArrayList<>();
    private final WeakHashMap<View, Float> savedAlpha = new WeakHashMap<>();
    /** Draw hook mode: the dates of this lock screen are not drawn while this is true. */
    private volatile boolean blocking;
    /** Uptime when the clock was last seen, 0 if not since the last release. */
    private long lastClockSeen;
    /** Views below the clock moved down via topMargin: {system base topMargin, our added shift}. */
    private final WeakHashMap<View, int[]> pushed = new WeakHashMap<>();
    private int push;
    private final Matrix stockMatrix = new Matrix();
    private final RectF tmp = new RectF();
    private final RectF clockBounds = new RectF();
    private ViewTreeObserver observer;
    private String kind = "";
    private DigitSet set;
    private int setStyle = -1;
    private int loadGeneration;
    private boolean attached, active, loadFailed, receiverOn;
    private final List<View> widgetList = new ArrayList<>();
    private final List<View> staleWidgets = new ArrayList<>();
    private boolean widgetsDirty = true;
    /** A lifecycle/layout transition may have created a new stock clock; scan it before its first frame. */
    private boolean scanBeforeDraw;
    private long transitionScanUntil;
    private int misses;
    private long lastLayoutScan, fixWindow, transitionLockUntil;
    private int fixes;
    private String text = "", dateInfo = "";

    private ClockController(ViewGroup host, boolean vendor) {
        this.host = host;
        this.vendor = vendor;
        this.density = host.getResources().getDisplayMetrics().density;
    }

    /** Main thread. Idempotent per host. */
    static void attach(ViewGroup host, boolean vendor) {
        if (broken || ConfigStore.safeMode()) return;
        if (vendor) vendorSeen = true;
        ClockController c;
        synchronized (HOSTS) {
            if (HOSTS.containsKey(host)) return;
            c = new ClockController(host, vendor);
            HOSTS.put(host, c);
        }
        host.addOnAttachStateChangeListener(c);
        if (host.isAttachedToWindow()) c.onViewAttachedToWindow(host);
    }

    /** For the settings app: "kind|class|id|text|sizeDp|views|dates|dateInfo" of the replaced clock, or empty. */
    static String diagnostics() {
        return diagnostics;
    }

    /** Last error that switched the module off in this System UI process, or empty. */
    static String error() {
        return error;
    }

    static int loadedGlyphs() {
        return loadedGlyphs;
    }

    // ------------------------------------------------------------------ fail-safe

    private void guard(Runnable r) {
        if (broken) return;
        try {
            r.run();
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Switches everything off for this process and gives every view back. */
    private static void fail(Throwable t) {
        if (broken) return;
        broken = true;
        error = t.getClass().getSimpleName() + ": " + t.getMessage();
        diagnostics = "";
        Log.e(TAG, "disabled after error", t);
        List<ClockController> all;
        synchronized (HOSTS) {
            all = new ArrayList<>(HOSTS.values());
        }
        for (ClockController c : all) {
            try {
                c.shutdown();
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    private void shutdown() {
        attached = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(layoutScan);
        main.removeCallbacks(transitionScan);
        ConfigStore.removeListener(onConfig);
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(this);
            observer.removeOnGlobalLayoutListener(onLayout);
        }
        observer = null;
        if (receiverOn) {
            receiverOn = false;
            try {
                receiverContext().unregisterReceiver(screen);
            } catch (Throwable ignored) {
                // already gone
            }
        }
        release();
        if (set != null) {
            set.release();
            set = null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onViewAttachedToWindow(View v) {
        guard(() -> {
            if (attached) return;
            attached = true;
            observer = host.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            observer.addOnGlobalLayoutListener(onLayout);
            scanBeforeDraw = true;
            registerScreen();
            ConfigStore.addListener(onConfig);
            ensureSet();
            main.post(tick);
        });
    }

    @Override public void onViewDetachedFromWindow(View v) {
        guard(() -> {
            if (!attached) return;
            shutdown();
        });
    }

    private void onConfig() {
        if (!attached) return;
        ensureSet();
        refresh(true);
        applyAllSpans(ConfigStore.get(), true);
    }

    /** Once per second: time text, clock detection, span updates, animation on/off. */
    private void tick() {
        main.removeCallbacks(tick);
        if (!attached) return;
        Config cfg = ConfigStore.get();
        String t = timeText(cfg);
        boolean timeChanged = !t.equals(text);
        text = t;
        refresh(true);
        if (timeChanged && active && !targets.isEmpty() && set != null) {
            applyAllSpans(cfg, true);
        }
        long wall = System.currentTimeMillis();
        main.postDelayed(tick, 1000 - wall % 1000 + 5);
    }

    /** Detection (only while the lock screen is visible), inline span styling, animation on/off. */
    private void refresh(boolean allowScan) {
        if (!attached) return;
        Config cfg = ConfigStore.get();
        boolean passive = !vendor && vendorSeen;
        boolean shown = host.isShown();
        boolean awake = interactive();
        boolean usable = set != null && set.isComplete();
        if (cfg.enabled && !passive && usable && !ConfigStore.safeMode()) {
            // AOD runs with isInteractive=false, but its newly inflated stock clock must still be found and styled.
            if (allowScan && shown && keyguardLocked()) scan(cfg);
            active = !targets.isEmpty();
            long now = SystemClock.uptimeMillis();
            if (active) lastClockSeen = now;
            if (active) applyAllSpans(cfg, false);
            if (drawHooked) {
                // Not drawn at all: no fight with the system's own alpha animations, nothing to hide again per frame.
                setBlocking(cfg.hideDate && (active || (lastClockSeen > 0 && now - lastClockSeen < DATE_GRACE_MS)));
            } else {
                for (View v : dates) {
                    if (cfg.hideDate) hideDate(v, true);
                    else restoreDate(v);
                }
            }
        } else {
            active = false;
            release();
        }
        if (set != null) {
            set.setTint(cfg.tint, cfg.tintColor);
            set.setRunning(active && shown && awake);
        }
    }

    /** Right after a layout pass (e.g. the lock screen was re-inflated on screen on): hide before the first frame. */
    private void onLayout() {
        if (!attached) return;
        scanBeforeDraw = true;
        widgetsDirty = true;
        long now = SystemClock.uptimeMillis();
        main.removeCallbacks(layoutScan);
        if (now - lastLayoutScan >= LAYOUT_SCAN_MS) {
            lastLayoutScan = now;
            refresh(true);
        } else {
            main.postDelayed(layoutScan, LAYOUT_SCAN_MS);
        }
    }

    /** System UI's application context (the host may live in a vendor plugin's package context). */
    private Context receiverContext() {
        Context app = host.getContext().getApplicationContext();
        return app != null ? app : host.getContext();
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerScreen() {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            Context c = receiverContext();
            if (android.os.Build.VERSION.SDK_INT >= 33) c.registerReceiver(screen, f, Context.RECEIVER_EXPORTED);
            else c.registerReceiver(screen, f);
            receiverOn = true;
        } catch (Throwable t) {
            Log.w(TAG, "screen receiver", t);   // not essential: the 1 s tick does the same a bit later
        }
    }

    /** Geometry must follow the views that SystemUI has actually laid out, not an early PowerManager state flip. */
    private void beginTransitionScan() {
        long now = SystemClock.uptimeMillis();
        transitionScanUntil = now + TRANSITION_SCAN_MS;
        transitionLockUntil = now + 700L;
        main.removeCallbacks(transitionScan);
        scanBeforeDraw = true;
        host.invalidate();
        main.postDelayed(transitionScan, LAYOUT_SCAN_MS);
    }

    private String timeText(Config cfg) {
        boolean use24 = cfg.format == Config.FORMAT_24H
                || (cfg.format == Config.FORMAT_SYSTEM && DateFormat.is24HourFormat(host.getContext()));
        Calendar now = Calendar.getInstance();
        return Digits.timeText(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND),
                use24, cfg.seconds);
    }

    private boolean interactive() {
        PowerManager pm = host.getContext().getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private boolean keyguardLocked() {
        KeyguardManager km = host.getContext().getSystemService(KeyguardManager.class);
        return km == null || km.isKeyguardLocked();
    }

    // ------------------------------------------------------------------ digits

    private void ensureSet() {
        final int style = ConfigStore.get().style;
        if (style == setStyle) return;
        setStyle = style;
        final int generation = ++loadGeneration;
        final String apk = modulePath;
        final android.content.Context ctx = host.getContext();
        LOADER.execute(() -> {
            DigitSet.Source src = apk != null ? ModuleAssets.ofApk(apk) : packageAssets(ctx);
            final DigitSet loaded = src == null ? null : DigitSet.load(src, style);
            main.post(() -> guard(() -> {
                if (loaded == null) {
                    loadedGlyphs = 0;
                    if (generation == loadGeneration && set == null) {
                        loadFailed = true;
                        refresh(false);
                    }
                    return;
                }
                if (generation != loadGeneration || !attached) {
                    loaded.release();
                    return;
                }
                if (set != null) set.release();
                set = loaded;
                loadFailed = false;
                loadedGlyphs = loaded.size();
                set.setCallback(spanCallback);
                tick();
                applyAllSpans(ConfigStore.get(), true);
            }));
        });
    }

    /** Fallback when LSPosed did not give the module path: the installed app's assets. */
    private static DigitSet.Source packageAssets(android.content.Context c) {
        try {
            return ModuleAssets.of(c.createPackageContext("dev.aniclock", 0).getAssets());
        } catch (Throwable t) {
            Log.w(TAG, "module assets unavailable", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ detection

    private void scan(Config cfg) {
        List<TextView> times = new ArrayList<>(), digits = new ArrayList<>();
        walk(host, 0, times, digits);

        List<TextView> found = new ArrayList<>();
        String foundKind = "";
        TextView best = null;
        for (TextView t : times) {
            if (best == null || t.getTextSize() > best.getTextSize()) best = t;
        }
        if (best != null) {
            found.add(best);
            foundKind = KIND_TIME;
        } else if (digits.size() >= 2) {
            float max = 0;
            for (TextView t : digits) max = Math.max(max, t.getTextSize());
            for (TextView t : digits) {
                if (t.getTextSize() >= max * 0.8f && found.size() < 4) found.add(t);
            }
            if (found.size() >= 2) foundKind = KIND_DIGITS;
            else found.clear();
        }
        int missLimit = SystemClock.uptimeMillis() < transitionScanUntil ? Integer.MAX_VALUE : MISSES_BEFORE_RELEASE;
        if (found.isEmpty() && !targets.isEmpty() && ++misses < missLimit && stillThere()) {
            return;    // e.g. the clock text is being animated; keep it hidden for now
        }
        if (!found.isEmpty()) misses = 0;
        if (!found.equals(targets)) {
            releaseClock();     // the dates are sorted out by updateDates below
            targets.addAll(found);
            kind = foundKind;
            claim(targets);
            synchronized (TRACKED) {
                for (TextView t : targets) TRACKED.put(t, this);
            }
            applyAllSpans(cfg, true);
            widgetsDirty = true;
        }
        updateDates(cfg);
        diagnostics = describe();
    }

    private boolean stillThere() {
        for (TextView t : targets) if (!t.isAttachedToWindow()) return false;
        return true;
    }

    private void walk(View v, int depth, List<TextView> times, List<TextView> digits) {
        if (v.getVisibility() != View.VISIBLE || depth > 40) return;
        if (v != host && claimedByOther(v)) return;
        if (isNotification(v)) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            CharSequence s = t.getText();
            if (s != null && s.length() <= 16 && t.getTextSize() / density >= MIN_TEXT_DP) {
                if (TIME.matcher(s).matches()) times.add(t);
                else if (DIGITS.matcher(s).matches()) digits.add(t);
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, times, digits);
        }
    }

    private static boolean isNotification(View v) {
        return v.getClass().getName().contains("Notification");
    }

    private void updateDates(Config cfg) {
        List<View> next = new ArrayList<>();
        List<String> near = new ArrayList<>();
        if (!targets.isEmpty()) {      // also find shown dates so they can follow the animated clock
            RectF clock = null;
            for (View v : targets) {
                tmp.set(0, 0, v.getWidth(), v.getHeight());
                if (!mapToHost(v, tmp)) continue;
                if (clock == null) clock = new RectF(tmp);
                else clock.union(tmp);
            }
            if (clock != null && clock.height() > 0) findDates(host, 0, clock, Calendar.getInstance(), next, near);
        }
        for (View v : dates) {
            if (!next.contains(v)) {
                restoreDate(v);
                unclaim(v);
            }
        }
        dates.clear();
        dates.addAll(next);
        claim(dates);
        if (drawHooked) keepDates(cfg.hideDate ? next : null);
        List<String> info = new ArrayList<>();
        if (cfg.hideDate && !dates.isEmpty()) {
            for (View v : dates) info.add(viewInfo(v));
        } else if (cfg.hideDate) {
            info.addAll(near);
        }
        dateInfo = String.join("; ", info);
    }

    // ------------------------------------------------------------------ dates not drawn (draw hook mode)

    /**
     * The dates found now are not drawn. One the scan misses stays so while it is not shown (e.g. hidden by the
     * system with the screen off) and is given back only after it was seen away from the clock a few times.
     * {@code found == null}: "hide date" is off, nothing of this lock screen is kept.
     */
    private void keepDates(List<View> found) {
        List<View> redraw = new ArrayList<>();
        synchronized (BLOCK) {
            if (found == null) {
                dropKept(redraw);
            } else {
                for (View v : found) {
                    Kept k = KEPT.get(v);
                    if (k == null || k.owner != this) {
                        KEPT.put(v, new Kept(this));
                        if (blocking) redraw.add(v);    // drawn so far: record it again, empty
                    } else {
                        k.misses = 0;
                    }
                    learn(v);
                }
                if (!targets.isEmpty()) {
                    for (Iterator<Map.Entry<View, Kept>> it = KEPT.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<View, Kept> e = it.next();
                        View v = e.getKey();
                        if (v == null || e.getValue().owner != this || found.contains(v)) continue;
                        if (v.isShown() && ++e.getValue().misses >= MISSES_BEFORE_RELEASE) {
                            it.remove();
                            redraw.add(v);
                        }
                    }
                }
            }
        }
        for (View v : redraw) v.invalidate();
    }

    /** Caller holds {@link #BLOCK}. */
    private void dropKept(List<View> redraw) {
        for (Iterator<Map.Entry<View, Kept>> it = KEPT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<View, Kept> e = it.next();
            if (e.getValue().owner != this) continue;
            if (e.getKey() != null) redraw.add(e.getKey());
            it.remove();
        }
    }

    /** Remembers "class#id" of a date view, only with a resource id (a bare TextView class says nothing). Holds BLOCK. */
    private static void learn(View v) {
        if (v.getId() == View.NO_ID) return;
        String id = idName(v);
        if (id.isEmpty()) return;
        String cls = v.getClass().getName();
        SIGNATURES.add(cls + "#" + id);
        SIGNATURE_CLASSES.add(cls);
    }

    /** Caller holds {@link #BLOCK}. */
    private static boolean signatureMatches(View v) {
        if (SIGNATURES.isEmpty() || v.getId() == View.NO_ID) return false;
        String cls = v.getClass().getName();
        if (!SIGNATURE_CLASSES.contains(cls)) return false;
        String sig = SIGNATURE_OF.get(v);
        if (sig == null) {
            sig = cls + "#" + idName(v);
            SIGNATURE_OF.put(v, sig);
        }
        return SIGNATURES.contains(sig);
    }

    /**
     * From HookEntry's hooks of View.draw / ViewGroup.dispatchDraw, for every view System UI records: true for a
     * date that must not be drawn. Its layout, alpha and animations stay the system's, so nothing moves and nothing
     * has to be hidden again.
     */
    static boolean skipDraw(View v) {
        if (broken) return false;
        if (blockingHosts <= 0) return false;
        synchronized (BLOCK) {
            Kept k = KEPT.get(v);
            if (k != null) return k.owner.blocking;
            if (!signatureMatches(v)) return false;
        }
        ClockController c = controllerAbove(v);
        return c != null && c.blocking;
    }

    /** The controller of the lock screen the view is in, or null. */
    private static ClockController controllerAbove(View v) {
        ViewParent p = v.getParent();
        for (int depth = 0; p instanceof View && depth < 60; depth++) {
            ClockController c;
            synchronized (HOSTS) {
                c = HOSTS.get(p);
            }
            if (c != null) return c;
            p = p.getParent();
        }
        return null;
    }

    private void setBlocking(boolean on) {
        if (on == blocking) return;
        synchronized (BLOCK) {
            blocking = on;
            blockingHosts += on ? 1 : -1;
        }
        redrawDates();
    }

    /** Blocking switched: the dates record their drawing again (empty or real) for the next frame. */
    private void redrawDates() {
        List<View> mine = new ArrayList<>();
        synchronized (BLOCK) {
            for (Map.Entry<View, Kept> e : KEPT.entrySet()) {
                if (e.getKey() != null && e.getValue().owner == this) mine.add(e.getKey());
            }
        }
        for (View v : mine) v.invalidate();
        invalidateSignatures(host, 0);
    }

    private void invalidateSignatures(View v, int depth) {
        if (depth > 40) return;
        boolean match;
        synchronized (BLOCK) {
            match = v != host && signatureMatches(v);
        }
        if (match) v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateSignatures(g.getChildAt(i), depth + 1);
        }
    }

    /**
     * Date views near the clock: today's date anywhere around it, or (directly above/below the clock) any date-like
     * text, or a view whose class or id says "date" (also custom views that draw the text themselves).
     */
    private void findDates(View v, int depth, RectF clock, Calendar now, List<View> out, List<String> near) {
        if (v.getVisibility() != View.VISIBLE || depth > 40 || out.size() >= 3) return;
        if (v != host && claimedByOther(v)) return;
        if (isNotification(v)) return;
        if (v instanceof TextView && targets.contains(v)) return;
        boolean named = v != host && dateNamed(v) && !containsTarget(v);
        if (v instanceof TextView || named) {
            tmp.set(0, 0, v.getWidth(), v.getHeight());
            if (mapToHost(v, tmp, true) && tmp.height() > 0) {
                float h = clock.height();
                boolean around = tmp.bottom >= clock.top - 2 * h && tmp.top <= clock.bottom + 3 * h;
                boolean close = tmp.right > clock.left && tmp.left < clock.right
                        && tmp.bottom >= clock.top - 1.5f * h && tmp.top <= clock.bottom + h
                        && tmp.height() <= 1.5f * h;
                if (v instanceof TextView) {
                    TextView t = (TextView) v;
                    CharSequence s = t.getText();
                    Locale loc = Locale.getDefault();
                    boolean readable = s != null && s.length() > 0 && t.getTextSize() / density >= 9f;
                    if (readable && ((around && DateMatcher.looksLikeDate(s, now, loc))
                            || (close && (named || DateMatcher.looksLikeAnyDate(s, loc))))) {
                        out.add(v);
                    } else if (close && readable && near.size() < 4) {
                        near.add(viewInfo(v));
                    }
                    return;
                }
                if (close) {
                    out.add(v);
                    return;
                }
            } else if (v instanceof TextView) {
                return;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findDates(g.getChildAt(i), depth + 1, clock, now, out, near);
        }
    }

    private boolean dateNamed(View v) {
        return DATE_CLASS.matcher(v.getClass().getSimpleName()).find()
                || DATE_ID.matcher(idName(v).toLowerCase(Locale.ROOT)).find();
    }

    private boolean containsTarget(View v) {
        if (!(v instanceof ViewGroup)) return false;
        for (TextView t : targets) {
            ViewParent p = t.getParent();
            while (p instanceof View) {
                if (p == v) return true;
                if (p == host) break;
                p = p.getParent();
            }
        }
        return false;
    }

    private boolean containsDate(View v) {
        if (!(v instanceof ViewGroup)) return false;
        for (View date : dates) {
            for (ViewParent p = date.getParent(); p instanceof View; p = p.getParent()) {
                if (p == v) return true;
                if (p == host) break;
            }
        }
        return false;
    }

    /** "Class#id «text»" for the status card. */
    private String viewInfo(View v) {
        StringBuilder b = new StringBuilder(v.getClass().getSimpleName());
        String id = idName(v);
        if (!id.isEmpty()) b.append('#').append(id);
        if (v instanceof TextView) {
            String t = String.valueOf(((TextView) v).getText()).trim();
            if (t.length() > 28) t = t.substring(0, 28) + "…";
            b.append(" \u00AB").append(t).append('\u00BB');
        }
        return clean(b.toString()).replace(';', ',');
    }

    private void claim(List<? extends View> views) {
        synchronized (CLAIMED) {
            for (View v : views) CLAIMED.put(v, this);
        }
    }

    private void unclaim(View v) {
        synchronized (CLAIMED) {
            if (CLAIMED.get(v) == this) CLAIMED.remove(v);
        }
    }

    private boolean claimedByOther(View v) {
        synchronized (CLAIMED) {
            ClockController owner = CLAIMED.get(v);
            return owner != null && owner != this && owner.attached;
        }
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) == 0) return "";
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "";
        }
    }

    private String describe() {
        if (targets.isEmpty()) return "";
        TextView v = targets.get(0);
        int size = Math.round(v.getTextSize() / density);
        return clean(kind) + "|" + clean(v.getClass().getName()) + "|" + clean(idName(v)) + "|"
                + clean(String.valueOf(v.getText())) + "|" + size + "|" + targets.size() + "|" + (ConfigStore.get().hideDate ? dates.size() : 0)
                + "|" + clean(dateInfo);
    }

    private static String clean(String s) {
        String c = s.replace('|', ' ').replace('\n', ' ');
        return c.length() > 120 ? c.substring(0, 120) : c;
    }

    // ------------------------------------------------------------------ date hiding & release

    /** Alpha 0: also hides custom views that draw the date themselves or colour it with spans. */
    private void hideDate(View v, boolean learn) {
        float a = v.getAlpha();
        if (!savedAlpha.containsKey(v) || (learn && a > 0f)) savedAlpha.put(v, a);
        if (a != 0f) v.setAlpha(0f);
    }

    private void restoreDate(View v) {
        Float a = savedAlpha.remove(v);
        if (a != null && v.getAlpha() == 0f) v.setAlpha(a);
    }

    /** Gives the system views back exactly as they were. */
    private void release() {
        releaseClock();
        for (View v : dates) {
            restoreDate(v);
            unclaim(v);
        }
        dates.clear();
        savedAlpha.clear();
        dateInfo = "";
        lastClockSeen = 0;
        setBlocking(false);
        List<View> redraw = new ArrayList<>();
        synchronized (BLOCK) {
            dropKept(redraw);
        }
        for (View v : redraw) v.invalidate();
    }

    /** The system clock back as it was (also when another clock view takes its place). */
    private void releaseClock() {
        for (TextView v : targets) {
            synchronized (TRACKED) {
                if (TRACKED.get(v) == this) TRACKED.remove(v);
            }
            strip(v);
            unclaim(v);
        }
        if (!targets.isEmpty()) diagnostics = "";
        releasePushed();
        targets.clear();
        misses = 0;
        kind = "";
        active = false;
    }

    // ------------------------------------------------------------------ per-frame check

    @Override public boolean onPreDraw() {
        if (broken) return true;
        try {
            boolean waitingForDate = SystemClock.uptimeMillis() < transitionScanUntil
                    && !ConfigStore.get().hideDate && dates.isEmpty();
            if (scanBeforeDraw || waitingForDate) {
                scanBeforeDraw = false;
                refresh(true);
            }
            keepDatesHidden();
            updateWidgetPush();
        } catch (Throwable t) {
            fail(t);
        }
        return true;
    }

    /** Fallback when draw hooks could not be installed: keep hidden dates at alpha 0. */
    private void keepDatesHidden() {
        if (!active || drawHooked || !ConfigStore.get().hideDate) return;
        long now = SystemClock.uptimeMillis();
        if (now - fixWindow >= 1000) {
            fixWindow = now;
            fixes = 0;
        }
        if (fixes >= MAX_FRAME_FIXES) return;
        for (View v : dates) {
            if (v.getAlpha() != 0f) {
                fixes++;
                hideDate(v, false);
            }
        }
    }

    /** Maps a rect from the view's coordinates to the host's, including translation, scale and scroll. */
    private boolean mapToHost(View v, RectF r) {
        View cur = v;
        while (cur != host) {
            Matrix m = cur.getMatrix();
            if (!m.isIdentity()) m.mapRect(r);
            r.offset(cur.getLeft(), cur.getTop());
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return false;
            View pv = (View) p;
            r.offset(-pv.getScrollX(), -pv.getScrollY());
            cur = pv;
        }
        return true;
    }

    private boolean mapToHost(View v, RectF r, boolean stock) {
        View cur = v;
        while (cur != host) {
            Matrix m = cur.getMatrix();
            if (!m.isIdentity()) m.mapRect(r);
            r.offset(cur.getLeft(), cur.getTop());
            if (stock) {
                int[] p = pushed.get(cur);
                if (p != null && p[1] != 0) r.offset(0f, -p[1]);
            }
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return false;
            View pv = (View) p;
            r.offset(-pv.getScrollX(), -pv.getScrollY());
            cur = pv;
        }
        return true;
    }

    // ------------------------------------------------------------------ widget adaptation (topMargin)

    private void updateWidgetPush() {
        if (!active || set == null || targets.isEmpty()) {
            releasePushed();
            return;
        }
        TextView primary = targets.get(0);
        if (!primary.isAttachedToWindow() || primary.getVisibility() != View.VISIBLE || primary.getWidth() <= 0) {
            return;
        }
        clockBounds.set(0f, 0f, primary.getWidth(), primary.getHeight());
        if (!mapToHost(primary, clockBounds) || clockBounds.height() <= 0f) {
            return;
        }
        if (widgetsDirty) {
            widgetsDirty = false;
            widgetList.clear();
            findBelow(host, 0, clockBounds, widgetList);
            staleWidgets.clear();
            for (View v : pushed.keySet()) {
                if (!widgetList.contains(v)) staleWidgets.add(v);
            }
            for (int i = 0; i < staleWidgets.size(); i++) {
                View v = staleWidgets.get(i);
                unpush(v, pushed.remove(v));
            }
            for (int i = 0; i < widgetList.size(); i++) {
                View v = widgetList.get(i);
                if (!pushed.containsKey(v) && v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    int base = ((ViewGroup.MarginLayoutParams) v.getLayoutParams()).topMargin;
                    pushed.put(v, new int[]{base, 0});
                }
            }
        }
        if (pushed.isEmpty()) {
            push = 0;
            return;
        }
        float needed = 0f;
        float gap = WIDGET_GAP_DP * density;
        for (Map.Entry<View, int[]> e : pushed.entrySet()) {
            View v = e.getKey();
            if (v == null || !v.isAttachedToWindow() || v.getVisibility() != View.VISIBLE) continue;
            tmp.set(0f, 0f, v.getWidth(), v.getHeight());
            if (mapToHost(v, tmp, true)) {
                needed = Math.max(needed, clockBounds.bottom + gap - tmp.top);
            }
        }
        applyPush(Math.max(0, Math.round(needed)));
    }

    private void findBelow(View v, int depth, RectF clock, List<View> out) {
        if (v.getVisibility() != View.VISIBLE || depth > 40) return;
        if (v != host && claimedByOther(v)) return;
        if (isNotification(v)) return;
        if (v instanceof TextView && targets.contains(v)) return;
        if (ConfigStore.get().hideDate && dates.contains(v)) return;
        if (v != host && !containsTarget(v) && !(ConfigStore.get().hideDate && containsDate(v))) {
            tmp.set(0f, 0f, v.getWidth(), v.getHeight());
            if (!mapToHost(v, tmp, true)) return;
            if (tmp.width() <= 0f || tmp.height() <= 0f) return;
            if (tmp.right <= clock.left || tmp.left >= clock.right || tmp.bottom <= clock.top) return;
            float h = host.getHeight();
            if (tmp.top >= clock.top + clock.height() * 0.35f - PUSH_TOLERANCE_DP * density) {
                if (tmp.top >= PUSH_MAX_TOP * h) return;
                if (tmp.bottom <= PUSH_MAX_BOTTOM * h) {
                    out.add(v);
                    return;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findBelow(g.getChildAt(i), depth + 1, clock, out);
        }
    }

    private void applyPush(int shift) {
        push = shift;
        for (Map.Entry<View, int[]> e : pushed.entrySet()) pushBy(e.getKey(), e.getValue(), shift);
    }

    private static void pushBy(View v, int[] p, int shift) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        int cur = lp.topMargin;
        if (cur != p[0] + p[1]) p[0] = cur;
        int next = p[0] + shift;
        if (cur != next) {
            lp.topMargin = next;
            v.setLayoutParams(lp);
        }
        p[1] = shift;
    }

    private static void unpush(View v, int[] p) {
        if (p == null || p[1] == 0) return;
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
        if (lp.topMargin == p[0] + p[1]) {
            lp.topMargin = p[0];
            v.setLayoutParams(lp);
        }
    }

    private void releasePushed() {
        for (Map.Entry<View, int[]> e : pushed.entrySet()) unpush(e.getKey(), e.getValue());
        pushed.clear();
        widgetList.clear();
        staleWidgets.clear();
        widgetsDirty = true;
        push = 0;
    }

    // ------------------------------------------------------------------ inline ReplacementSpan rendering

    static void onTrackedTextChanged(TextView view) {
        if (broken || WRITING.get()) return;
        ClockController c;
        synchronized (TRACKED) {
            c = TRACKED.get(view);
        }
        if (c == null || !c.attached || !c.active || c.set == null) return;
        c.guard(() -> {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                c.main.post(() -> onTrackedTextChanged(view));
                return;
            }
            Config cfg = ConfigStore.get();
            boolean primary = !c.targets.isEmpty() && c.targets.get(0) == view;
            c.applySpansToView(view, cfg, primary, false);
        });
    }

    private void applyAllSpans(Config cfg, boolean force) {
        if (set == null || targets.isEmpty()) return;
        if (text.isEmpty()) text = timeText(cfg);
        for (int i = 0; i < targets.size(); i++) {
            applySpansToView(targets.get(i), cfg, i == 0, force);
        }
    }

    private void applySpansToView(TextView view, Config cfg, boolean primary, boolean force) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> applySpansToView(view, cfg, primary, force));
            return;
        }
        if (set == null || !view.isAttachedToWindow()) return;
        CharSequence current = view.getText();
        if (current == null || current.length() == 0) return;
        CharSequence original = originalSource(current);
        String display = text.isEmpty() ? timeText(cfg) : text;
        if (!force && current instanceof Spanned) {
            OwnSpan[] old = ((Spanned) current).getSpans(0, current.length(), OwnSpan.class);
            if (old.length == 1 && old[0].original == original) {
                if (!primary && old[0] instanceof BlankSpan) return;
                if (primary && old[0] instanceof AniSpan) {
                    AniSpan s = (AniSpan) old[0];
                    if (s.matches(set, display, cfg)) return;
                }
            }
        }
        SpannableString spanned = new SpannableString(original.toString().replace('\n', ' ').replace('\r', ' '));
        if (original instanceof Spanned) {
            Spanned src = (Spanned) original;
            for (Object span : src.getSpans(0, src.length(), Object.class)) {
                if (span instanceof ReplacementSpan || span instanceof NoCopySpan || span instanceof SpanWatcher) continue;
                int start = src.getSpanStart(span), end = src.getSpanEnd(span);
                if (start >= 0 && end >= start && end <= spanned.length()) {
                    try { spanned.setSpan(span, start, end, src.getSpanFlags(span)); }
                    catch (RuntimeException ignored) { }
                }
            }
        }
        OwnSpan span = primary
                ? new AniSpan(original, this, view, set, display, cfg.sizePct, cfg.spacingPct, cfg.offsetDp)
                : new BlankSpan(original);
        spanned.setSpan(span, 0, spanned.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setTextSafe(view, spanned);
    }

    private static void strip(TextView view) {
        CharSequence current = view.getText();
        if (!(current instanceof Spanned)) return;
        OwnSpan[] own = ((Spanned) current).getSpans(0, current.length(), OwnSpan.class);
        if (own.length == 0) return;
        setTextSafe(view, originalSource(current));
    }

    private static void setTextSafe(TextView view, CharSequence value) {
        boolean prev = WRITING.get();
        WRITING.set(true);
        try {
            view.setText(value, TextView.BufferType.SPANNABLE);
        } finally {
            WRITING.set(prev);
        }
    }

    private static CharSequence originalSource(CharSequence text) {
        if (text == null) return "";
        if (text instanceof Spanned) {
            OwnSpan[] own = ((Spanned) text).getSpans(0, text.length(), OwnSpan.class);
            if (own.length == 1 && sameDisplayText(own[0].original, text)) return own[0].original;
            SpannableString copy = new SpannableString(text.toString());
            Spanned source = (Spanned) text;
            for (Object span : source.getSpans(0, source.length(), Object.class)) {
                if (span instanceof OwnSpan || span instanceof NoCopySpan || span instanceof SpanWatcher) continue;
                int start = source.getSpanStart(span), end = source.getSpanEnd(span);
                if (start >= 0 && end >= start && end <= copy.length()) {
                    copy.setSpan(span, start, end, source.getSpanFlags(span));
                }
            }
            return new SpannedString(copy);
        }
        return text.toString();
    }

    private static boolean sameDisplayText(CharSequence original, CharSequence current) {
        if (original.length() != current.length()) return false;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (c == '\n' || c == '\r') c = ' ';
            if (c != current.charAt(i)) return false;
        }
        return true;
    }

    private float maxRowWidth(TextView view) {
        int hostW = host.getWidth();
        if (hostW <= 0 && view != null) hostW = view.getResources().getDisplayMetrics().widthPixels;
        return Math.max(1f, hostW - 2f * MARGIN_DP * density);
    }

    private abstract static class OwnSpan extends ReplacementSpan {
        final CharSequence original;

        OwnSpan(CharSequence original) {
            this.original = original;
        }
    }

    private static final class AniSpan extends OwnSpan {
        private final ClockController controller;
        private final java.lang.ref.WeakReference<TextView> viewRef;
        private final DigitSet set;
        private final String displayText;
        private final int sizePct, spacingPct, offsetDp;

        AniSpan(CharSequence original, ClockController controller, TextView view, DigitSet set,
                String displayText, int sizePct, int spacingPct, int offsetDp) {
            super(original);
            this.controller = controller;
            this.viewRef = new java.lang.ref.WeakReference<>(view);
            this.set = set;
            this.displayText = displayText;
            this.sizePct = sizePct;
            this.spacingPct = spacingPct;
            this.offsetDp = offsetDp;
        }

        boolean matches(DigitSet s, String text, Config cfg) {
            return set == s && displayText.equals(text) && sizePct == cfg.sizePct
                    && spacingPct == cfg.spacingPct && offsetDp == cfg.offsetDp;
        }

        private float scaledHeight(Paint paint) {
            float spacing = spacingPct / 100f;
            float height = paint.getTextSize() * sizePct / 100f;
            float rowW = ClockRenderer.rowWidth(set, displayText, height, spacing);
            float fit = ClockRenderer.fitScale(rowW, controller.maxRowWidth(viewRef.get()));
            return height * fit;
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            float spacing = spacingPct / 100f;
            float height = scaledHeight(paint);
            float rowW = ClockRenderer.rowWidth(set, displayText, height, spacing);
            int w = Math.max(1, Math.round(rowW));
            int h = Math.max(1, Math.round(height));
            int off = Math.round(offsetDp * controller.density);
            if (fm != null) {
                fm.ascent = -h - Math.max(0, off);
                fm.top = fm.ascent;
                fm.descent = 0;
                fm.bottom = 0;
                fm.leading = 0;
            }
            return w;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) {
            float spacing = spacingPct / 100f;
            float height = scaledHeight(paint);
            int off = Math.round(offsetDp * controller.density);
            float drawTop = y - height + (off < 0 ? off : 0f);
            ClockRenderer.drawRow(canvas, set, displayText, x, drawTop, height, spacing, paint.getAlpha());
        }
    }

    private static final class BlankSpan extends OwnSpan {
        BlankSpan(CharSequence original) {
            super(original);
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                fm.top = fm.ascent = 0;
                fm.bottom = fm.descent = 0;
                fm.leading = 0;
            }
            return 0;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) { }
    }
}
