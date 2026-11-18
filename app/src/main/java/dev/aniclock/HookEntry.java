package dev.aniclock;

import android.graphics.Canvas;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/**
 * LSPosed entry point (libxposed API 102). Finds the lock screen root view and hands it to {@link ClockController}.
 * OriginOS: the keyguard comes from the vivo System UI plugin and is loaded lazily, so class loading is watched.
 * Other ROMs: AOSP's KeyguardStatusView is used as a fallback anchor.
 */
public final class HookEntry extends XposedModule {
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String PLUGIN_PKG = "com.vivo.systemuiplugin";
    /** OriginOS lock screen root (vivo System UI plugin). */
    private static final String VIVO_KEYGUARD =
            "com.vivo.systemuiplugin.keyguard.legacy.keyguard.views.keyguardstyleos5.KeyguardOsView";
    private static final String AOSP_STATUS = "com.android.keyguard.KeyguardStatusView";

    private static final AtomicBoolean WATCHING = new AtomicBoolean();
    private static final Set<Class<?>> INSTALLED = Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        xposed = this;
        ClockController.modulePath = getModuleApplicationInfo().sourceDir;   // the module APK
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!SYSTEMUI.equals(pkg) && !PLUGIN_PKG.equals(pkg)) return;
        hookDraw();
        hookSetText();
        for (String name : new String[]{VIVO_KEYGUARD, AOSP_STATUS}) {
            try {
                install(Class.forName(name, false, param.getClassLoader()));
            } catch (Throwable ignored) {
                // not loaded in this class loader (yet); the watcher below catches it later
            }
        }
        if (WATCHING.compareAndSet(false, true)) {
            try {
                interceptMethods(ClassLoader.class, "loadClass", chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Class) {
                        try {
                            Class<?> loaded = (Class<?>) result;
                            if (VIVO_KEYGUARD.equals(loaded.getName()) || isVivoAodClock(loaded)) install(loaded);
                        } catch (Throwable t) {
                            Log.e(ClockController.TAG, "class watcher", t);
                        }
                    }
                    return result;
                });
            } catch (Throwable ignored) {
                // without the watcher only classes already loaded are hooked
            }
        }
    }

    private static void install(Class<?> type) {
        synchronized (INSTALLED) {
            if (!INSTALLED.add(type)) return;
        }
        final boolean vendor = type.getName().startsWith("com.vivo.");
        Hooker onView = chain -> {
            Object result = chain.proceed();
            Object o = chain.getThisObject();
            if (o instanceof View) schedule((View) o, vendor);
            return result;
        };
        try {
            interceptConstructors(type, onView);
        } catch (Throwable ignored) {
            // the lifecycle hooks below still cover attachment
        }
        for (String method : new String[]{"onFinishInflate", "onAttachedToWindow", "onWindowVisibilityChanged"}) {
            try {
                interceptMethods(type, method, onView);
            } catch (Throwable ignored) {
                // skip this entry point
            }
        }
    }

    private static final AtomicBoolean DRAW_HOOKED = new AtomicBoolean(), SET_TEXT_HOOKED = new AtomicBoolean();

    private static void hookSetText() {
        if (!SET_TEXT_HOOKED.compareAndSet(false, true)) return;
        try {
            intercept(TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class), chain -> {
                Object r = chain.proceed();
                Object v = chain.getThisObject();
                if (v instanceof TextView) ClockController.onTrackedTextChanged((TextView) v);
                return r;
            });
        } catch (Throwable t) {
            hookError("setText hook", t);
        }
    }

    /**
     * The date next to the replaced clock is not drawn at all (instead of alpha 0 applied again by a check), so it
     * cannot show up for a moment when the system re-inflates, shows or fades it on screen on/off. Every other view
     * goes straight through; while nothing is blocked the check is a single volatile read.
     */
    private static void hookDraw() {
        if (!DRAW_HOOKED.compareAndSet(false, true)) return;
        Hooker skip = chain -> {
            Object v = chain.getThisObject();
            if (v instanceof View && ClockController.skipDraw((View) v)) return null;
            return chain.proceed();
        };
        try {
            intercept(View.class.getDeclaredMethod("draw", Canvas.class), skip);
            // ViewGroups without a background record only dispatchDraw, without a draw call
            intercept(ViewGroup.class.getDeclaredMethod("dispatchDraw", Canvas.class), skip);
            ClockController.drawHooked = true;
        } catch (Throwable t) {
            hookError("draw hooks (the date falls back to alpha 0)", t);
        }
    }

    /** View.post() on a not-yet-attached view runs once it is attached, so this also covers the constructor hook. */
    private static void schedule(final View view, final boolean vendor) {
        view.post(() -> {
            try {
                ConfigStore.ensure(view.getContext());
                ViewGroup host = vendor ? asGroup(view) : null;
                if (host == null) host = asGroup(view.getRootView());
                if (host != null) ClockController.attach(host, vendor);
            } catch (Throwable t) {
                Log.e(ClockController.TAG, "attach", t);   // never break the lock screen
            }
        });
    }

    private static ViewGroup asGroup(View v) {
        return v instanceof ViewGroup ? (ViewGroup) v : null;
    }

    /** Vivo loads AOD clock implementations lazily from the SystemUI plugin, often after the normal keyguard. */
    private static boolean isVivoAodClock(Class<?> type) {
        String name = type.getName().toLowerCase(java.util.Locale.ROOT);
        return View.class.isAssignableFrom(type)
                && name.startsWith("com.vivo.systemuiplugin.")
                && name.contains("aod")
                && (name.contains("clock") || name.contains("time"));
    }

    // ------------------------------------------------------------------ libxposed API 102 hooks

    private static final String HOOK_TAG = "AniClock";

    /** Framework interface of the loaded module, set in onModuleLoaded. */
    static volatile XposedInterface xposed;

    /** Intercepts one method or constructor: {@code hook(target).intercept(hooker)}. */
    static HookHandle intercept(Executable target, Hooker hooker) {
        XposedInterface x = xposed;
        if (x == null) throw new IllegalStateException("Xposed module is not loaded");
        return x.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    /** Every non-abstract method with this name declared by {@code type}; fails only if none could be hooked. */
    static List<HookHandle> interceptMethods(Class<?> type, String name, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) targets.add(m);
        }
        return interceptAll(targets, hooker);
    }

    /** Every constructor declared by {@code type}; fails only if none could be hooked. */
    static List<HookHandle> interceptConstructors(Class<?> type, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Constructor<?> c : type.getDeclaredConstructors()) targets.add(c);
        return interceptAll(targets, hooker);
    }

    private static List<HookHandle> interceptAll(List<Executable> targets, Hooker hooker) {
        List<HookHandle> out = new ArrayList<>(targets.size());
        Throwable failure = null;
        for (Executable e : targets) {
            try {
                out.add(intercept(e, hooker));
            } catch (Throwable t) {
                failure = t;
                hookError("hook " + e, t);
            }
        }
        if (out.isEmpty() && failure != null) {
            if (failure instanceof Error) throw (Error) failure;
            throw failure instanceof RuntimeException ? (RuntimeException) failure : new RuntimeException(failure);
        }
        return out;
    }

    private static void hookError(String what, Throwable t) {
        XposedInterface x = xposed;
        if (x != null) x.log(Log.ERROR, HOOK_TAG, what, t);
        else Log.e(HOOK_TAG, what, t);
    }
}
