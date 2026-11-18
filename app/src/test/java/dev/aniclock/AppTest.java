package dev.aniclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** Built-in images, decoding and drawing, the settings screen's controls. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {31, 35}, qualifiers = "ru")
public class AppTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void builtInSetsAreCompleteAndDecode() {
        Context c = RuntimeEnvironment.getApplication();
        DigitSet anim = DigitSet.load(ModuleAssets.of(c.getAssets()), dev.aniclock.Config.STYLE_ANIMATED);
        assertEquals(12, anim.size());
        assertTrue(anim.isComplete());
        assertEquals(90, anim.glyph(0, '3').getIntrinsicWidth());
        assertEquals(46, anim.glyph(2, ':').getIntrinsicWidth());
        // Each position has its own drawable: a shared animated drawable drew only one of two equal digits.
        assertTrue(anim.glyph(0, '2') != anim.glyph(1, '2'));
        assertTrue(anim.glyph(0, '2') == anim.glyph(0, '2'));
        anim.release();

        DigitSet still = DigitSet.load(ModuleAssets.of(c.getAssets()), dev.aniclock.Config.STYLE_STATIC);
        assertTrue(still.isComplete());
        assertEquals(136, still.glyph(0, '0').getIntrinsicWidth());
        assertEquals(66, still.glyph(1, ':').getIntrinsicWidth());
        still.release();
    }

    @Test public void drawsEveryDigitOf2222() {
        Context c = RuntimeEnvironment.getApplication();
        DigitSet set = DigitSet.load(ModuleAssets.of(c.getAssets()), dev.aniclock.Config.STYLE_STATIC);
        Bitmap bmp = Bitmap.createBitmap(800, 300, Bitmap.Config.ARGB_8888);
        ClockRenderer.drawRow(new Canvas(bmp), set, "22:22", 0, 0, 300, 1f, 255);
        // Four digits of 136 px plus a 66 px colon: every column band must contain some pixels.
        int[] starts = {0, 136, 272, 338, 474};
        for (int i = 0; i < starts.length; i++) {
            assertTrue("glyph " + i, columnHasInk(bmp, starts[i] + 10, starts[i] + (i == 2 ? 56 : 126)));
        }
        set.release();
    }

    private static boolean columnHasInk(Bitmap b, int x0, int x1) {
        for (int x = x0; x < x1; x++) {
            for (int y = 0; y < b.getHeight(); y += 2) {
                if (Color.alpha(b.getPixel(x, y)) > 0) return true;
            }
        }
        return false;
    }

    @Test public void readsImagesFromTheModuleApk() throws Exception {
        java.io.File apk = TestGifs.fakeApk(tmp.getRoot());
        DigitSet set = DigitSet.load(ModuleAssets.ofApk(apk.getPath()), dev.aniclock.Config.STYLE_STATIC);
        assertTrue("static falls back to animated frames", set.isComplete());
        set.release();
        DigitSet.Source src = ModuleAssets.ofApk(apk.getPath());
        assertNull(src.read("animated", "../../classes.dex"));
        assertNull(src.read("other", "0.gif"));
        assertNull(ModuleAssets.ofApk(new java.io.File(tmp.getRoot(), "missing.apk").getPath()).read("animated", "0.gif"));
    }

    /** Phones in any language other than Russian get the English texts. */
    @Test @Config(qualifiers = "en") public void englishTexts() {
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        View root = ac.get().getWindow().getDecorView();
        click(root, ac.get().getString(R.string.style_static));
        click(root, ac.get().getString(R.string.format_24));
        assertNull(find(root, "Статика"));
        ac.pause().stop().destroy();
    }

    @Test public void screenLaunchesAndSurvivesEveryControl() {
        ActivityController<MainActivity> ac = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ac.get();
        View root = a.getWindow().getDecorView();
        assertNull("import removed", find(root, "Импорт из ZIP"));

        click(root, a.getString(R.string.style_static));
        click(root, a.getString(R.string.format_24));
        click(root, a.getString(R.string.seconds));
        click(root, a.getString(R.string.tint));
        EditText hex = first(root, EditText.class);
        hex.setText("ff8800");
        hex.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        for (SeekBar bar : all(root, SeekBar.class)) {
            bar.setProgress(0);
            bar.setProgress(bar.getMax());
        }
        ShadowLooper.idleMainLooper();
        ac.pause().resume();
        ac.pause().stop().destroy();
    }

    @Test @Config(qualifiers = "ru-night") public void launchesInDarkTheme() {
        Robolectric.buildActivity(MainActivity.class).setup().pause().stop().destroy();
    }

    @Test public void hideDateSwitchIsSavedAndRestored() {
        android.content.SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0);
        prefs.edit().clear().commit();
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = first.get();
            View root = activity.getWindow().getDecorView();
            View label = find(root, activity.getString(R.string.date_hide));
            assertNotNull(label);
            assertTrue(first((View) label.getParent(), PixelSwitch.class).isChecked());
            click(root, activity.getString(R.string.date_hide));
            ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertFalse(dev.aniclock.Config.parse(prefs.getString("cfg", "")).hideDate);
        } finally {
            first.pause().stop().destroy();
        }
        ActivityController<MainActivity> reopened = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = reopened.get();
            View root = activity.getWindow().getDecorView();
            View label = find(root, activity.getString(R.string.date_hide));
            assertFalse(first((View) label.getParent(), PixelSwitch.class).isChecked());
            click(root, activity.getString(R.string.date_hide));
            ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue(dev.aniclock.Config.parse(prefs.getString("cfg", "")).hideDate);
        } finally {
            reopened.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------ helpers

    interface Check { boolean ok(); }

    static void waitFor(Check check) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            ShadowLooper.idleMainLooper(25, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (check.ok()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out");
    }

    static View find(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = find(g.getChildAt(i), text);
                if (f != null) return f;
            }
        }
        return null;
    }

    static void click(View root, String text) {
        View v = find(root, text);
        assertNotNull(text, v);
        // switch rows: the label's parent row is the clickable element
        if (!v.hasOnClickListeners() && v.getParent() instanceof View) v = (View) v.getParent();
        assertTrue(text, v.performClick());
        ShadowLooper.idleMainLooper();
    }

    static <T extends View> T first(View root, Class<T> type) {
        List<T> l = all(root, type);
        assertFalse(type.getSimpleName(), l.isEmpty());
        return l.get(0);
    }

    static <T extends View> List<T> all(View v, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(v, type, out);
        return out;
    }

    private static <T extends View> void collect(View v, Class<T> type, List<T> out) {
        if (type.isInstance(v)) out.add(type.cast(v));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), type, out);
        }
    }
}
