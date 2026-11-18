package dev.aniclock;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Lays the glyph images out in a row and draws them. Used by the lock screen overlay and the settings preview. */
final class ClockRenderer {
    private ClockRenderer() { }

    /**
     * Draws the row centred on a point, scaled down to fit {@code maxWidth}.
     *
     * @param height     glyph height in px
     * @param spacingPct horizontal advance relative to the glyph width
     */
    static void draw(Canvas canvas, DigitSet set, String text, float centerX, float centerY,
                     float height, float maxWidth, int spacingPct, int alpha) {
        if (set == null || text == null || height <= 0) return;
        float spacing = spacingPct / 100f;
        float total = rowWidth(set, text, height, spacing);
        if (total <= 0) return;
        float scale = fitScale(total, maxWidth);
        drawRow(canvas, set, text, centerX - total * scale / 2f, centerY - height * scale / 2f,
                height * scale, spacing, alpha);
    }

    /** Scale factor (at most 1) that makes a row of width {@code total} fit into {@code maxWidth}. */
    static float fitScale(float total, float maxWidth) {
        return total > maxWidth && maxWidth > 0 ? maxWidth / total : 1f;
    }

    /** Draws the row with its top-left corner at (left, top). */
    static void drawRow(Canvas canvas, DigitSet set, String text, float left, float top,
                        float height, float spacing, int alpha) {
        float x = left;
        set.trim(text.length());
        set.setAlpha(alpha);
        for (int i = 0; i < text.length(); i++) {
            Drawable d = set.glyph(i, text.charAt(i));
            if (d == null) continue;
            float w = glyphWidth(d, height);
            d.setBounds(Math.round(x), Math.round(top), Math.round(x + w), Math.round(top + height));
            d.draw(canvas);
            x += advance(w, spacing, i == text.length() - 1);
        }
    }

    static float rowWidth(DigitSet set, String text, float height, float spacing) {
        float x = 0;
        for (int i = 0; i < text.length(); i++) {
            Drawable d = set.glyph(i, text.charAt(i));
            if (d == null) continue;
            x += advance(glyphWidth(d, height), spacing, i == text.length() - 1);
        }
        return x;
    }

    /** Visible artwork within the row, excluding transparent margins in the image canvases. */
    static void inkBounds(DigitSet set, String text, float height, float spacing, RectF out) {
        out.setEmpty();
        float x = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Drawable drawable = set.glyph(i, c);
            if (drawable == null) continue;
            float width = glyphWidth(drawable, height);
            RectF ink = set.inkBounds(c);
            out.union(x + width * ink.left, height * ink.top, x + width * ink.right, height * ink.bottom);
            x += advance(width, spacing, i == text.length() - 1);
        }
    }

    private static float glyphWidth(Drawable d, float height) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        return iw > 0 && ih > 0 ? height * iw / ih : height * 0.45f;
    }

    /** The last glyph takes its full width; others advance by the spacing factor (the art overlaps slightly). */
    private static float advance(float width, float spacing, boolean last) {
        return last ? width : width * spacing;
    }

    /**
     * Horizontal placement of a row of width {@code w} relative to the replaced clock's text box
     * [boxLeft, boxRight], kept inside [minX, maxX]. A box near the centre stays centred; one clearly left or
     * right of the centre keeps that edge (OriginOS often uses start-aligned clocks).
     */
    static float placeLeft(float w, float boxLeft, float boxRight, float minX, float maxX) {
        float mid = (minX + maxX) / 2f, boxMid = (boxLeft + boxRight) / 2f;
        float tolerance = (maxX - minX) * 0.1f;
        float left;
        if (Math.abs(boxMid - mid) <= tolerance) left = boxMid - w / 2f;
        else if (boxMid < mid) left = boxLeft;
        else left = boxRight - w;
        if (left + w > maxX) left = maxX - w;
        if (left < minX) left = minX;
        return left;
    }
}
