package dev.aniclock;

import java.util.Locale;

/** Names and layout of the digit image set ("animated/0.gif" ... "static/colon.gif"). Pure logic, unit tested. */
final class Digits {
    static final String ANIMATED = "animated", STATIC = "static";
    static final String[] STYLES = {ANIMATED, STATIC};
    /** Glyph names; "dot" is optional and only used as a fallback separator. */
    static final String[] NAMES = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "colon", "dot"};
    /** Files needed for a complete set: ten digits and the colon. */
    static final int REQUIRED = 11;

    private Digits() { }

    static String styleDir(int style) {
        return style == Config.STYLE_STATIC ? STATIC : ANIMATED;
    }

    /** File name for a clock character, or {@code null} for characters that are not drawn. */
    static String fileFor(char c) {
        if (c >= '0' && c <= '9') return c + ".gif";
        if (c == ':') return "colon.gif";
        if (c == '.') return "dot.gif";
        return null;
    }

    static boolean isGlyphFile(String name) {
        for (String n : NAMES) {
            if ((n + ".gif").equals(name)) return true;
        }
        return false;
    }

    /** Only these relative paths may ever be served or written. Blocks traversal such as "../". */
    static boolean isValidRelativePath(String rel) {
        if (rel == null) return false;
        int slash = rel.indexOf('/');
        if (slash <= 0 || rel.indexOf('/', slash + 1) >= 0) return false;
        String dir = rel.substring(0, slash), name = rel.substring(slash + 1);
        return (dir.equals(ANIMATED) || dir.equals(STATIC)) && isGlyphFile(name);
    }

    /** Clock text for the given time and settings, e.g. "9:41", "21:41", "21:41:07". */
    static String timeText(int hour24, int minute, int second, boolean use24h, boolean seconds) {
        int h = use24h ? hour24 : (hour24 % 12 == 0 ? 12 : hour24 % 12);
        String hh = use24h ? String.format(Locale.ROOT, "%02d", h) : Integer.toString(h);
        String s = hh + ":" + String.format(Locale.ROOT, "%02d", minute);
        if (seconds) s += ":" + String.format(Locale.ROOT, "%02d", second);
        return s;
    }
}
