package dev.aniclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.format.DateFormat;
import android.view.View;
import java.util.Calendar;

/** Live clock in the settings screen, drawn with the same renderer as the lock screen. */
final class PreviewView extends View implements Drawable.Callback {
    private final Runnable tick = this::tick;
    private DigitSet set;
    private Config cfg = new Config();
    private String text = "";

    PreviewView(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Takes ownership of the set; {@code null} shows nothing. */
    void setDigits(DigitSet s) {
        if (set != null) set.release();
        set = s;
        if (set != null) set.setCallback(this);
        apply();
    }

    void setConfig(Config c) {
        cfg = Config.parse(c.serialize());
        apply();
    }

    private void apply() {
        if (set != null) {
            set.setTint(cfg.tint, cfg.tintColor);
            set.setRunning(isAttachedToWindow());
        }
        tick();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        apply();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        if (set != null) set.setRunning(false);
        super.onDetachedFromWindow();
    }

    private void tick() {
        removeCallbacks(tick);
        boolean use24 = cfg.format == Config.FORMAT_24H
                || (cfg.format == Config.FORMAT_SYSTEM && DateFormat.is24HourFormat(getContext()));
        Calendar now = Calendar.getInstance();
        text = Digits.timeText(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND),
                use24, cfg.seconds);
        invalidate();
        if (isAttachedToWindow()) postDelayed(tick, 1000 - System.currentTimeMillis() % 1000 + 5);
    }

    String text() {
        return text;
    }

    boolean hasDigits() {
        return set != null && set.isComplete();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (set == null || !set.isComplete()) return;
        // Same proportions as on the lock screen: the size slider scales around a typical clock text height.
        float base = getHeight() * 0.42f;
        float h = Math.min(getHeight() * 0.92f, base * cfg.sizePct / 140f);
        float pad = getResources().getDisplayMetrics().density * 16;
        ClockRenderer.draw(canvas, set, text, getWidth() / 2f, getHeight() / 2f, h, getWidth() - 2 * pad,
                cfg.spacingPct, 255);
    }

    @Override public void invalidateDrawable(Drawable who) {
        invalidate();
    }

    @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
        android.os.Handler h = getHandler();
        if (h != null) h.postAtTime(what, who, when);
    }

    @Override public void unscheduleDrawable(Drawable who, Runnable what) {
        android.os.Handler h = getHandler();
        if (h != null) h.removeCallbacks(what, who);
    }
}
