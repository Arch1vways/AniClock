package dev.aniclock;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises the lock screen date line ("вс, 2 ноября", "Sun, Nov 2", "11月2日 周日", "02.11.2025") for today.
 * Only today's names and numbers count, which keeps unrelated text (notifications, weather) out. Unit tested.
 */
final class DateMatcher {
    private static final Pattern CJK = Pattern.compile("\\d{1,2}\\s*\u6708\\s*\\d{1,2}\\s*\u65E5|\u661F\u671F|\u5468[\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u65E5\u5929]|\u519C\u5386");

    private static final Pattern NUMERIC = Pattern.compile("(^|\\D)\\d{1,2}\\s?[./-]\\s?\\d{1,2}(\\D|$)");
    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}]+");
    private static final java.util.Map<Locale, List<String>> ALL_NAMES = new java.util.HashMap<>();

    private DateMatcher() { }

    /**
     * Looser check for text right next to the clock: any weekday or month name (also abbreviated, e.g. "ноя"),
     * in the system language or English, or a numeric day/month.
     */
    static boolean looksLikeAnyDate(CharSequence text, Locale locale) {
        if (text == null) return false;
        String t = text.toString().toLowerCase(locale).trim();
        if (t.length() < 2 || t.length() > 48) return false;
        if (CJK.matcher(t).find() || NUMERIC.matcher(t).find()) return true;
        List<String> names = allNames(locale);
        for (String token : NON_LETTERS.split(t)) {
            if (token.length() < 2) continue;
            for (String n : names) {
                if (token.equals(n)) return true;
                if (token.length() >= 3 && n.startsWith(token)) return true;                     // "ноя" / "ноября"
                if (n.length() >= 3 && token.startsWith(n) && token.length() <= n.length() + 2) return true;
            }
        }
        return false;
    }

    private static List<String> allNames(Locale locale) {
        synchronized (ALL_NAMES) {
            List<String> cached = ALL_NAMES.get(locale);
            if (cached != null) return cached;
            List<String> out = new ArrayList<>();
            Calendar c = Calendar.getInstance();
            for (Locale l : new Locale[]{locale, Locale.ENGLISH}) {
                for (int d = 0; d < 7; d++) {
                    c.set(2025, Calendar.NOVEMBER, 2 + d);      // Sunday .. Saturday
                    for (String n : names(c, l, true)) add(out, n, locale);
                }
                for (int m = 0; m < 12; m++) {
                    c.set(2025, m, 1);
                    for (String n : names(c, l, false)) add(out, n, locale);
                }
            }
            ALL_NAMES.put(locale, out);
            return out;
        }
    }

    static boolean looksLikeDate(CharSequence text, Calendar now, Locale locale) {
        if (text == null) return false;
        String t = text.toString().toLowerCase(locale).trim();
        if (t.length() < 2 || t.length() > 48) return false;
        if (CJK.matcher(t).find()) return true;
        int day = now.get(Calendar.DAY_OF_MONTH), month = now.get(Calendar.MONTH) + 1;
        if (anyWord(t, names(now, locale, true))) return true;                      // weekday
        boolean hasDay = Pattern.compile("(^|\\D)0?" + day + "(\\D|$)").matcher(t).find();
        if (hasDay && anyWord(t, names(now, locale, false))) return true;           // day + month name
        String d = "0?" + day, m = "0?" + month;
        return Pattern.compile("(^|\\d{4}[./-]|\\D)(" + d + "[./-]" + m + "|" + m + "[./-]" + d + ")(\\D|$)").matcher(t).find();
    }

    /** Today's weekday or month names in every common form (format/standalone, full/short), lower case. */
    private static List<String> names(Calendar now, Locale locale, boolean weekday) {
        List<String> out = new ArrayList<>();
        DateFormatSymbols sym = DateFormatSymbols.getInstance(locale);
        if (weekday) {
            int i = now.get(Calendar.DAY_OF_WEEK);
            add(out, sym.getWeekdays()[i], locale);
            add(out, sym.getShortWeekdays()[i], locale);
        } else {
            int i = now.get(Calendar.MONTH);
            add(out, sym.getMonths()[i], locale);
            add(out, sym.getShortMonths()[i], locale);
        }
        for (String p : weekday ? new String[]{"EEEE", "EEE", "cccc", "ccc"} : new String[]{"MMMM", "MMM", "LLLL", "LLL"}) {
            try {
                add(out, new SimpleDateFormat(p, locale).format(now.getTime()), locale);
            } catch (IllegalArgumentException ignored) {
                // pattern letter not supported by this runtime
            }
        }
        return out;
    }

    private static void add(List<String> out, String name, Locale locale) {
        if (name == null) return;
        String n = name.toLowerCase(locale).replace(".", "").trim();
        if (n.length() >= 2 && !out.contains(n)) out.add(n);
    }

    private static boolean anyWord(String text, List<String> words) {
        for (String w : words) {
            // No inline flags such as (?U): Android's ICU regex rejects them (1.2 crashed here). \p{L} is Unicode-aware there anyway.
            if (Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(w) + "($|[^\\p{L}])").matcher(text).find()) return true;
        }
        return false;
    }
}
