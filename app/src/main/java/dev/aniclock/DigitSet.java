package dev.aniclock;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.RectF;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One set of digit images. Animated GIFs become {@link AnimatedImageDrawable}s (decoded and played by the
 * platform); static style falls back to the first frame of the animated set when a static image is missing.
 * Shared by the settings preview and the lock screen; only the byte source differs.
 *
 * <p>Every position of the clock text gets its own drawable ("slot"). An animated drawable keeps a single set of
 * bounds that the render thread reads when it draws, so sharing one drawable between the two "2"s of "22:47"
 * drew only one of them (1.0 bug: a missing digit in 24-hour time).
 */
final class DigitSet {
    /** Reads one file of the set, e.g. ("animated", "3.gif"); returns {@code null} if it does not exist. */
    interface Source {
        byte[] read(String dir, String name);
    }

    private static final class Slot {
        final char c;
        final Drawable d;
        Slot(char c, Drawable d) { this.c = c; this.d = d; }
    }

    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<Character, RectF> ink = new HashMap<>();
    private static final RectF FULL_INK = new RectF(0, 0, 1, 1);
    private final List<Slot> slots = new ArrayList<>();
    private final boolean animated;
    private Drawable.Callback callback;
    private ColorMatrixColorFilter filter;
    private int tintColor;
    private boolean tinted;
    private boolean running;
    private int alpha = 255;

    private DigitSet(boolean animated) {
        this.animated = animated;
    }

    /** Blocking read and check of all files; call off the main thread. */
    static DigitSet load(Source source, int style) {
        boolean wantStatic = style == Config.STYLE_STATIC;
        DigitSet set = new DigitSet(!wantStatic);
        for (String n : Digits.NAMES) {
            String file = n + ".gif";
            byte[] bytes = wantStatic ? source.read(Digits.STATIC, file) : null;
            Drawable checked = decode(bytes);
            if (checked == null) {
                bytes = source.read(Digits.ANIMATED, file);
                checked = decode(bytes);
            }
            if (checked != null) {
                set.files.put(file, bytes);
                char symbol = n.length() == 1 ? n.charAt(0) : "colon".equals(n) ? ':' : '.';
                set.ink.put(symbol, measureInk(checked));
                dispose(checked);
            }
        }
        return set;
    }

    /** Stable visible bounds of the initial artwork, normalized to its canvas; measured once on the loader. */
    private static RectF measureInk(Drawable drawable) {
        Bitmap bitmap = null;
        try {
            int width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
            if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return new RectF(FULL_INK);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(new Canvas(bitmap));
            int left = width, top = height, right = -1, bottom = -1;
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    if ((row[x] >>> 24) == 0) continue;
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = y;
                }
            }
            if (right < left || bottom < top) return new RectF(FULL_INK);
            return new RectF(left / (float) width, top / (float) height,
                    (right + 1f) / width, (bottom + 1f) / height);
        } catch (Throwable ignored) {
            return new RectF(FULL_INK);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    /** Read-only normalized bounds, kept stable while GIF frames animate. */
    RectF inkBounds(char c) {
        RectF bounds = ink.get(c);
        if (bounds == null && c == ':') bounds = ink.get('.');
        return bounds != null ? bounds : FULL_INK;
    }

    private static Drawable decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Digits and colon present. */
    boolean isComplete() {
        for (int i = 0; i < Digits.REQUIRED; i++) {
            if (!files.containsKey(Digits.NAMES[i] + ".gif")) return false;
        }
        return true;
    }

    int size() {
        return files.size();
    }

    private byte[] bytesFor(char c) {
        String f = Digits.fileFor(c);
        if (f == null) return null;
        byte[] b = files.get(f);
        if (b == null && c == ':') b = files.get("dot.gif");
        return b;
    }

    /** Drawable for character {@code c} at text position {@code index}; created on first use of that pair. */
    Drawable glyph(int index, char c) {
        while (slots.size() <= index) slots.add(null);
        Slot s = slots.get(index);
        if (s != null && s.c == c) return s.d;
        if (s != null) dispose(s.d);
        Drawable d = decode(bytesFor(c));
        if (d != null) prepare(d);
        slots.set(index, new Slot(c, d));
        return d;
    }

    /** Frees positions beyond the current text (e.g. after seconds were switched off). */
    void trim(int length) {
        while (slots.size() > length) {
            Slot s = slots.remove(slots.size() - 1);
            if (s != null) dispose(s.d);
        }
    }

    private void prepare(Drawable d) {
        d.setFilterBitmap(false);            // pixel art: keep hard edges when scaled up
        d.setAlpha(alpha);
        d.setColorFilter(filter);
        d.setCallback(callback);
        if (d instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable a = (AnimatedImageDrawable) d;
            a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            if (running) a.start();
        }
    }

    private static void dispose(Drawable d) {
        if (d == null) return;
        if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).stop();
        d.setCallback(null);
    }

    private interface Each { void apply(Drawable d); }

    private void each(Each e) {
        for (Slot s : slots) {
            if (s != null && s.d != null) e.apply(s.d);
        }
    }

    void setCallback(Drawable.Callback cb) {
        callback = cb;
        each(d -> d.setCallback(cb));
    }

    /** Plays or pauses the animation (static style never plays). */
    void setRunning(boolean run) {
        run = run && animated;
        if (run == running) return;
        running = run;
        final boolean start = run;
        each(d -> {
            if (d instanceof AnimatedImageDrawable) {
                if (start) ((AnimatedImageDrawable) d).start();
                else ((AnimatedImageDrawable) d).stop();
            }
        });
    }

    /**
     * Only applied on change: setting alpha invalidates an animated drawable, and doing that from draw() kept the
     * whole lock screen redrawing every frame.
     */
    void setAlpha(int a) {
        if (a == alpha) return;
        alpha = a;
        each(d -> d.setAlpha(a));
    }

    /** Monochrome tint: luminance mapped onto the colour, transparency kept. */
    void setTint(boolean enabled, int color) {
        if (enabled == tinted && color == tintColor) return;
        tinted = enabled;
        tintColor = color;
        filter = enabled ? new ColorMatrixColorFilter(monochrome(color)) : null;
        each(d -> d.setColorFilter(filter));
    }

    static ColorMatrix monochrome(int color) {
        float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        float lr = 0.2126f, lg = 0.7152f, lb = 0.0722f;
        return new ColorMatrix(new float[]{
                lr * r, lg * r, lb * r, 0, 0,
                lr * g, lg * g, lb * g, 0, 0,
                lr * b, lg * b, lb * b, 0, 0,
                0, 0, 0, 1, 0});
    }

    void release() {
        setRunning(false);
        trim(0);
        files.clear();
        ink.clear();
    }
}
