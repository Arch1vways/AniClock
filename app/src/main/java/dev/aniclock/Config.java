package dev.aniclock;

import android.content.ContentResolver;
import android.provider.Settings;
import java.util.Locale;

/**
 * Module settings, stored as one {@code Settings.Secure} string ("k=v;k=v").
 * The settings app sends the string to System UI by broadcast (see {@link ConfigReceiver}); System UI stores it
 * in Settings.Secure so it survives reboots.
 */
final class Config {
    static final String KEY = "aniclock_cfg";

    static final int STYLE_ANIMATED = 0, STYLE_STATIC = 1;
    static final int FORMAT_SYSTEM = 0, FORMAT_12H = 1, FORMAT_24H = 2;
    static final int SIZE_MIN = 60, SIZE_DEFAULT = 140, SIZE_MAX = 250;
    static final int SPACING_MIN = 50, SPACING_MAX = 130;
    static final int OFFSET_MIN = -200, OFFSET_MAX = 200;
    static final int[] SWATCHES = {
            0xFFFFFFFF, 0xFFF48FB1, 0xFFCE93D8, 0xFF90CAF9,
            0xFF80DEEA, 0xFFA5D6A7, 0xFFFFE082, 0xFFFFAB91
    };

    boolean enabled = true;
    int style = STYLE_ANIMATED;
    int format = FORMAT_SYSTEM;
    boolean seconds;
    /** Digit height relative to the system clock's text size. */
    int sizePct = SIZE_DEFAULT;
    /** Horizontal advance relative to the digit width (the original look overlaps digits slightly). */
    int spacingPct = 90;
    /** Vertical shift from the system clock position, dp. */
    int offsetDp;
    /** Hide the native date line; when shown it is kept below any enlarged visible artwork. */
    boolean hideDate = true;
    boolean tint;
    int tintColor = SWATCHES[1];

    static Config parse(String s) {
        Config c = new Config();
        if (s == null || s.isEmpty()) return c;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "enabled": c.enabled = flag(v); break;
                    case "style": c.style = clamp(Integer.parseInt(v), 0, 1); break;
                    case "format": c.format = clamp(Integer.parseInt(v), 0, 2); break;
                    case "seconds": c.seconds = flag(v); break;
                    case "size": c.sizePct = clamp(Integer.parseInt(v), SIZE_MIN, SIZE_MAX); break;
                    case "spacing": c.spacingPct = clamp(Integer.parseInt(v), SPACING_MIN, SPACING_MAX); break;
                    case "offset": c.offsetDp = clamp(Integer.parseInt(v), OFFSET_MIN, OFFSET_MAX); break;
                    case "tint": c.tint = flag(v); break;
                    case "date": c.hideDate = flag(v); break;
                    case "tintColor": c.tintColor = 0xFF000000 | (int) (Long.parseLong(v, 16) & 0xFFFFFF); break;
                    default: break; // unknown keys are ignored (forward compatibility)
                }
            } catch (NumberFormatException ignored) {
                // keep the default for this key
            }
        }
        return c;
    }

    String serialize() {
        return String.format(Locale.ROOT,
                "enabled=%d;style=%d;format=%d;seconds=%d;size=%d;spacing=%d;offset=%d;date=%d;tint=%d;tintColor=%06x",
                enabled ? 1 : 0, style, format, seconds ? 1 : 0, sizePct, spacingPct, offsetDp, hideDate ? 1 : 0,
                tint ? 1 : 0, tintColor & 0xFFFFFF);
    }

    static Config read(ContentResolver cr) {
        try {
            return parse(Settings.Secure.getString(cr, KEY));
        } catch (Throwable t) {
            return new Config();
        }
    }

    /** "RGB" or "RRGGBB", optional '#'. @return opaque colour or {@code null} if incomplete/invalid. */
    static Integer parseHex(String text) {
        if (text == null) return null;
        String h = text.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 3) {
            h = new String(new char[]{h.charAt(0), h.charAt(0), h.charAt(1), h.charAt(1), h.charAt(2), h.charAt(2)});
        }
        if (h.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(h.charAt(i), 16) < 0) return null;
        }
        return 0xFF000000 | Integer.parseInt(h, 16);
    }

    static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private static boolean flag(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
