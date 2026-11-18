package dev.aniclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure logic: asset paths, time text, settings format, date recognition, placement. */
public class LogicTest {
    @Test public void storedPathsCannotEscape() {
        assertTrue(Digits.isValidRelativePath("animated/0.gif"));
        assertTrue(Digits.isValidRelativePath("static/colon.gif"));
        assertFalse(Digits.isValidRelativePath("../animated/0.gif"));
        assertFalse(Digits.isValidRelativePath("animated/../../0.gif"));
        assertFalse(Digits.isValidRelativePath("animated/x/0.gif"));
        assertFalse(Digits.isValidRelativePath("other/0.gif"));
        assertFalse(Digits.isValidRelativePath("animated/evil.gif"));
        assertFalse(Digits.isValidRelativePath("/animated/0.gif"));
        assertFalse(Digits.isValidRelativePath(null));
    }

    @Test public void timeText() {
        assertEquals("21:41", Digits.timeText(21, 41, 7, true, false));
        assertEquals("09:05", Digits.timeText(9, 5, 0, true, false));
        assertEquals("9:41", Digits.timeText(21, 41, 7, false, false));
        assertEquals("12:00", Digits.timeText(0, 0, 0, false, false));
        assertEquals("12:30", Digits.timeText(12, 30, 0, false, false));
        assertEquals("21:41:07", Digits.timeText(21, 41, 7, true, true));
        assertEquals("colon.gif", Digits.fileFor(':'));
        assertNull(Digits.fileFor('A'));
    }

    @Test public void configRoundTripAndLimits() {
        Config c = new Config();
        c.enabled = false;
        c.style = Config.STYLE_STATIC;
        c.format = Config.FORMAT_24H;
        c.seconds = true;
        c.sizePct = 200;
        c.spacingPct = 70;
        c.offsetDp = -35;
        c.hideDate = false;
        c.tint = true;
        c.tintColor = 0xFF12ABEF;
        assertEquals(c.serialize(), Config.parse(c.serialize()).serialize());

        Config bad = Config.parse("size=9999;spacing=-1;offset=777;style=5;format=x;tintColor=zz;unknown=1");
        assertEquals(Config.SIZE_MAX, bad.sizePct);
        assertEquals(Config.SPACING_MIN, bad.spacingPct);
        assertEquals(Config.OFFSET_MAX, bad.offsetDp);
        assertEquals(1, bad.style);
        assertEquals(Config.FORMAT_SYSTEM, bad.format);
        assertEquals(new Config().tintColor, bad.tintColor);
        assertEquals(new Config().serialize(), Config.parse(null).serialize());

        Config d = new Config();                          // only the main switch starts on
        org.junit.Assert.assertTrue(d.enabled && !d.seconds && !d.tint);
        assertTrue("date starts hidden", d.hideDate);
        assertFalse("show date survives serialization", Config.parse(c.serialize()).hideDate);
        assertFalse(Config.parse("date=0").hideDate);
        assertTrue(Config.parse("date=1").hideDate);
    }

    @Test public void hexParsing() {
        assertEquals(Integer.valueOf(0xFFFF8800), Config.parseHex("#ff8800"));
        assertEquals(Integer.valueOf(0xFFFF8800), Config.parseHex("F80"));
        assertNull(Config.parseHex("12345"));
        assertNull(Config.parseHex("zzzzzz"));
        assertEquals("#12ABEF", Config.hex(0xFF12ABEF));
    }

    @Test public void recognisesTodaysDateLine() {
        java.util.Locale ru = new java.util.Locale("ru", "RU");
        java.util.Calendar sun = new java.util.GregorianCalendar(2025, java.util.Calendar.NOVEMBER, 2);
        assertTrue(DateMatcher.looksLikeDate("вс, 2 ноября", sun, ru));
        assertTrue(DateMatcher.looksLikeDate("Воскресенье", sun, ru));
        assertTrue(DateMatcher.looksLikeDate("2 ноября", sun, ru));
        assertTrue(DateMatcher.looksLikeDate("Ноябрь 2", sun, ru));
        assertTrue(DateMatcher.looksLikeDate("02.11.2025", sun, ru));
        assertTrue(DateMatcher.looksLikeDate("11月2日 周日", sun, ru));
        assertTrue(DateMatcher.looksLikeDate("Sun, Nov 2", sun, java.util.Locale.US));
        assertTrue(DateMatcher.looksLikeDate("Sunday, November 2", sun, java.util.Locale.US));
        assertFalse(DateMatcher.looksLikeDate("Встреча 5 ноября", sun, ru));
        assertFalse(DateMatcher.looksLikeDate("Батарея 2%", sun, ru));
        assertFalse(DateMatcher.looksLikeDate("23°C", sun, ru));
        assertFalse(DateMatcher.looksLikeDate("12:30", sun, ru));
        assertFalse(DateMatcher.looksLikeDate("все уведомления", sun, ru));
        assertFalse(DateMatcher.looksLikeDate("Mon, Nov 3", sun, java.util.Locale.US));
    }

    @Test public void recognisesAnyDateLineNextToTheClock() {
        java.util.Locale ru = new java.util.Locale("ru", "RU");
        assertTrue(DateMatcher.looksLikeAnyDate("Пн, 3 ноя", ru));
        assertTrue(DateMatcher.looksLikeAnyDate("ПОНЕДЕЛЬНИК", ru));
        assertTrue(DateMatcher.looksLikeAnyDate("3 ноября, понедельник", ru));
        assertTrue(DateMatcher.looksLikeAnyDate("Вторник 4 нояб.", ru));
        assertTrue(DateMatcher.looksLikeAnyDate("03/11 Mon", ru));
        assertTrue(DateMatcher.looksLikeAnyDate("11月3日 星期一", ru));
        assertTrue(DateMatcher.looksLikeAnyDate("Monday, November 3", java.util.Locale.US));
        assertFalse(DateMatcher.looksLikeAnyDate("12:30", ru));
        assertFalse(DateMatcher.looksLikeAnyDate("Проведите вверх", ru));
        assertFalse(DateMatcher.looksLikeAnyDate("Москва 5°", ru));
        assertFalse(DateMatcher.looksLikeAnyDate("Зарядка 80%", ru));
    }

    @Test public void placementFollowsTheSystemClock() {
        // host 0..1000 with 16 px margins; row 300 px wide
        assertEquals(350f, ClockRenderer.placeLeft(300, 400, 600, 16, 984), 0.01f);   // centred clock
        assertEquals(60f, ClockRenderer.placeLeft(300, 60, 260, 16, 984), 0.01f);     // start-aligned clock
        assertEquals(640f, ClockRenderer.placeLeft(300, 740, 940, 16, 984), 0.01f);   // end-aligned clock
        assertEquals(16f, ClockRenderer.placeLeft(300, -50, 100, 16, 984), 0.01f);    // kept on screen
        assertEquals(684f, ClockRenderer.placeLeft(300, 900, 1100, 16, 984), 0.01f);
        assertEquals(0.5f, ClockRenderer.fitScale(2000, 1000), 0.001f);
        assertEquals(1f, ClockRenderer.fitScale(500, 1000), 0.001f);
    }

    /** Android's ICU regex rejects inline flags like (?U); the JVM accepts them, so check the sources. */
    @Test public void noInlineRegexFlags() throws Exception {
        java.io.File dir = new java.io.File("src/main/java/dev/aniclock");
        if (!dir.isDirectory()) dir = new java.io.File("app/src/main/java/dev/aniclock");
        java.io.File[] files = dir.listFiles();
        assertTrue(files != null && files.length > 0);
        java.util.regex.Pattern flag = java.util.regex.Pattern.compile("\\(\\?[a-zA-Z]+[):]");
        for (java.io.File f : files) {
            for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                if (line.trim().startsWith("//")) continue;
                assertFalse(f.getName() + ": " + line, flag.matcher(line).find());
                assertFalse(f.getName() + ": " + line, line.contains("UNICODE_CHARACTER_CLASS"));
            }
        }
    }
}
