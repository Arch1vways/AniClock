package dev.aniclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** The System UI side against a fake lock screen: detection, hiding, drawing, restoring. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class ClockControllerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void replacesAndRestoresTheSystemClock() throws Exception {
        ClockController.modulePath = TestGifs.fakeApk(tmp.getRoot()).getPath();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        KeyguardManager km = a.getSystemService(KeyguardManager.class);
        shadowOf(km).setKeyguardLocked(true);

        FrameLayout host = new FrameLayout(a);
        host.setBackgroundColor(Color.BLACK);
        TextView clock = new TextView(a);
        clock.setText("12:30");
        clock.setTextColor(Color.WHITE);
        clock.setShadowLayer(4, 0, 2, 0x80000000);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 72);
        clock.setGravity(Gravity.CENTER);
        TextView date = new TextView(a);
        date.setText(new java.text.SimpleDateFormat("EEE, d MMMM", java.util.Locale.getDefault()).format(new java.util.Date()));
        date.setTextColor(Color.WHITE);
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        TextView hint = new TextView(a);
        hint.setText("Swipe up to unlock");
        hint.setTextColor(Color.WHITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 200;
        host.addView(clock, lp);
        host.addView(date);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        host.addView(hint, hlp);
        a.setContentView(host);
        ShadowLooper.idleMainLooper();

        ConfigStore.ensure(a);
        ClockController.attach(host, true);
        AppTest.waitFor(() -> ClockController.loadedGlyphs() == 12);
        AppTest.waitFor(() -> ClockController.diagnostics().startsWith("time|"));
        assertTrue(ClockController.diagnostics(), ClockController.diagnostics().contains("|12:30|72|1|1"));

        assertEquals("clock text hidden", Color.TRANSPARENT, clock.getCurrentTextColor());
        assertEquals("clock shadow off", 0f, clock.getShadowRadius(), 0f);
        assertEquals("date hidden", 0f, date.getAlpha(), 0f);
        assertEquals("date colour untouched", Color.WHITE, date.getCurrentTextColor());
        assertEquals("other text untouched", Color.WHITE, hint.getCurrentTextColor());
        assertEquals("view alpha untouched", 1f, clock.getAlpha(), 0f);

        // The clock is hidden right away; the digits appear once loaded (static state may be left by other tests).
        AppTest.waitFor(() -> {
            host.getViewTreeObserver().dispatchOnPreDraw();
            Bitmap probe = Bitmap.createBitmap(host.getWidth(), host.getHeight(), Bitmap.Config.ARGB_8888);
            host.draw(new Canvas(probe));
            return (probe.getPixel(host.getWidth() / 2, (int) (200 + clock.getHeight() / 2f)) & 0xFFFFFF) == 0x3366CC;
        });

        // The overlay covers the host, so its own invalidations (seconds, GIF frames) reach the screen.
        android.graphics.drawable.Drawable ov = overlayOf(host);
        assertEquals(new android.graphics.Rect(0, 0, host.getWidth(), host.getHeight()), ov.getBounds());

        // Per frame nothing is changed, only read: drawing settles, no view stays invalidated.
        Bitmap warm = Bitmap.createBitmap(host.getWidth(), host.getHeight(), Bitmap.Config.ARGB_8888);
        host.getViewTreeObserver().dispatchOnPreDraw();
        host.draw(new Canvas(warm));
        host.getViewTreeObserver().dispatchOnPreDraw();
        assertFalse("pre-draw must not invalidate the clock", clock.isDirty());
        assertEquals(1f, clock.getAlpha(), 0f);
        Bitmap bmp = Bitmap.createBitmap(host.getWidth(), host.getHeight(), Bitmap.Config.ARGB_8888);
        host.draw(new Canvas(bmp));
        int y = (int) (200 + clock.getHeight() / 2f);
        assertEquals("glyph pixel", 0x3366CC, bmp.getPixel(host.getWidth() / 2, y) & 0xFFFFFF);

        // The system recolours the clock or fades the date in: hidden again before the next frame.
        clock.setTextColor(Color.YELLOW);
        date.setAlpha(1f);
        host.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(Color.TRANSPARENT, clock.getCurrentTextColor());
        assertEquals(0f, date.getAlpha(), 0f);

        // Screen off and unlocked: stays hidden, so turning the screen on never flashes the old clock.
        android.os.PowerManager pm = a.getSystemService(android.os.PowerManager.class);
        shadowOf(pm).setIsInteractive(false);
        ShadowLooper.idleMainLooper(1100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(Color.TRANSPARENT, clock.getCurrentTextColor());
        shadowOf(km).setKeyguardLocked(false);
        ShadowLooper.idleMainLooper(1100, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(Color.TRANSPARENT, clock.getCurrentTextColor());
        assertEquals(0f, date.getAlpha(), 0f);
        shadowOf(pm).setIsInteractive(true);
        shadowOf(km).setKeyguardLocked(true);


        // Module off: the system clock is back.
        dev.aniclock.Config off = new dev.aniclock.Config();
        off.enabled = false;
        ConfigStore.update(a, off);
        ShadowLooper.idleMainLooper();
        assertEquals(Color.YELLOW, clock.getCurrentTextColor());

        // Lock screen removed: nothing left behind.
        ConfigStore.update(a, new dev.aniclock.Config());
        AppTest.waitFor(() -> clock.getCurrentTextColor() == Color.TRANSPARENT);
        a.getWindowManager().removeViewImmediate(a.getWindow().getDecorView());
        assertEquals(Color.YELLOW, clock.getCurrentTextColor());
    }

    /** A view that draws the date itself (no TextView), as some vendor lock screens do. */
    static final class KeyguardDateView extends android.view.View {
        KeyguardDateView(android.content.Context c) {
            super(c);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(400, 60);
        }
    }

    @Test public void hidesCustomDateViewsAndNewClocksBeforeTheirFirstFrame() throws Exception {
        ClockController.modulePath = TestGifs.fakeApk(tmp.getRoot()).getPath();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(a.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        ConfigStore.ensure(a);
        ConfigStore.update(a, new dev.aniclock.Config());

        android.widget.LinearLayout host = new android.widget.LinearLayout(a);
        host.setOrientation(android.widget.LinearLayout.VERTICAL);
        KeyguardDateView date = new KeyguardDateView(a);
        TextView weekday = new TextView(a);
        weekday.setText("Mon, Nov 3");           // maybe not today, but right above the clock
        weekday.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        TextView clock = big(a, "09:41");
        host.addView(date);
        host.addView(weekday);
        host.addView(clock);
        TextView hint = new TextView(a);
        hint.setText("Проведите вверх");
        host.addView(hint);
        a.setContentView(host);
        ShadowLooper.idleMainLooper();

        ClockController.attach(host, true);
        AppTest.waitFor(() -> clock.getCurrentTextColor() == Color.TRANSPARENT);
        AppTest.waitFor(() -> date.getAlpha() == 0f && weekday.getAlpha() == 0f);
        assertEquals("unrelated text untouched", 1f, hint.getAlpha(), 0f);
        assertTrue(ClockController.diagnostics(), ClockController.diagnostics().contains("KeyguardDateView"));

        // The lock screen re-inflates its clock (e.g. on screen on): hidden in the same layout pass.
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS);
        host.removeView(clock);
        TextView fresh = big(a, "09:42");
        host.addView(fresh, 2);
        host.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY));
        host.layout(0, 0, 1080, 1920);
        host.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("new clock hidden before drawing", Color.TRANSPARENT, fresh.getCurrentTextColor());
        a.getWindowManager().removeViewImmediate(a.getWindow().getDecorView());
        assertEquals(1f, date.getAlpha(), 0f);
    }

    @Test public void hidesNewAodClockWhileScreenIsNonInteractive() throws Exception {
        ClockController.modulePath = TestGifs.fakeApk(tmp.getRoot()).getPath();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(a.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        android.os.PowerManager power = a.getSystemService(android.os.PowerManager.class);
        shadowOf(power).setIsInteractive(false);
        ConfigStore.ensure(a);
        ConfigStore.update(a, new dev.aniclock.Config());

        FrameLayout host = new FrameLayout(a);
        TextView first = big(a, "08:15");
        host.addView(first);
        a.setContentView(host);
        ShadowLooper.idleMainLooper();
        ClockController.attach(host, true);
        AppTest.waitFor(() -> {
            host.getViewTreeObserver().dispatchOnPreDraw();
            return first.getCurrentTextColor() == Color.TRANSPARENT;
        });

        host.removeView(first);
        TextView aod = big(a, "08:16");
        host.addView(aod);
        host.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY));
        host.layout(0, 0, 1080, 1920);
        host.getViewTreeObserver().dispatchOnGlobalLayout();
        host.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("new AOD clock hidden before drawing", Color.TRANSPARENT, aod.getCurrentTextColor());
        a.getWindowManager().removeViewImmediate(a.getWindow().getDecorView());
    }

    @Test public void movesTheWidgetsBelowTheClockUnderTheDigits() throws Exception {
        ClockController.modulePath = TestGifs.fakeApk(tmp.getRoot()).getPath();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(a.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        ConfigStore.ensure(a);
        dev.aniclock.Config big = new dev.aniclock.Config();
        big.sizePct = 250;
        ConfigStore.update(a, big);

        FrameLayout host = new FrameLayout(a);
        android.widget.LinearLayout column = new android.widget.LinearLayout(a);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView clock = big(a, "10:08");
        clock.setGravity(Gravity.CENTER);
        android.view.View widget = new android.view.View(a);
        column.addView(clock, new android.widget.LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        column.addView(widget, new android.widget.LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 120));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = 150;
        host.addView(column, clp);
        TextView hint = new TextView(a);
        hint.setText("Swipe up to unlock");
        host.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        a.setContentView(host);
        ShadowLooper.idleMainLooper();

        ClockController.attach(host, true);
        AppTest.waitFor(() -> {
            host.getViewTreeObserver().dispatchOnPreDraw();
            return widget.getTranslationY() > 0f;
        });
        Object c = controllerOf(host);
        float bottom = field(c, "rowTop") + field(c, "rowHeight");
        float shift = widget.getTranslationY();
        assertEquals("right below the digits", bottom, 150 + widget.getTop() + shift, 1.5f);
        assertEquals("the bottom area stays", 0f, hint.getTranslationY(), 0f);
        assertEquals("the clock itself stays", 0f, clock.getTranslationY(), 0f);

        // Looked for again (every second): not moved twice.
        ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS);
        host.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(shift, widget.getTranslationY(), 0f);

        // The system moves the widget itself: its own translation is kept, ours added.
        widget.setTranslationY(30);
        host.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(30 + shift, widget.getTranslationY(), 0f);

        // Module off: exactly where the system has it.
        dev.aniclock.Config off = new dev.aniclock.Config();
        off.enabled = false;
        ConfigStore.update(a, off);
        ShadowLooper.idleMainLooper();
        assertEquals(30f, widget.getTranslationY(), 0f);
        ConfigStore.update(a, new dev.aniclock.Config());
        ShadowLooper.idleMainLooper();
        a.getWindowManager().removeViewImmediate(a.getWindow().getDecorView());
    }

    @Test public void dateIsNotDrawnInsteadOfMadeTransparent() throws Exception {
        ClockController.modulePath = TestGifs.fakeApk(tmp.getRoot()).getPath();
        ClockController.drawHooked = true;
        try {
            Activity a = Robolectric.buildActivity(Activity.class).setup().get();
            shadowOf(a.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
            ConfigStore.ensure(a);
            ConfigStore.update(a, new dev.aniclock.Config());

            android.widget.LinearLayout host = new android.widget.LinearLayout(a);
            host.setOrientation(android.widget.LinearLayout.VERTICAL);
            TextView date = dateView(a);
            TextView clock = big(a, "07:05");
            TextView hint = new TextView(a);
            hint.setText("Swipe up to unlock");
            host.addView(date);
            host.addView(clock);
            host.addView(hint);
            a.setContentView(host);
            ShadowLooper.idleMainLooper();

            ClockController.attach(host, true);
            AppTest.waitFor(() -> ClockController.skipDraw(clock));   // the stock clock is not drawn at all
            AppTest.waitFor(() -> ClockController.skipDraw(date));
            assertEquals("alpha left to the system", 1f, date.getAlpha(), 0f);
            assertTrue("its colour stays the system's (nothing to re-apply)", clock.getCurrentTextColor() != Color.TRANSPARENT);
            assertFalse("unrelated text drawn", ClockController.skipDraw(hint));

            dev.aniclock.Config shown = new dev.aniclock.Config();
            shown.hideDate = false;
            ConfigStore.update(a, shown);
            ShadowLooper.idleMainLooper();
            host.getViewTreeObserver().dispatchOnPreDraw();
            assertFalse("the switch restores the native date drawing", ClockController.skipDraw(date));
            assertTrue("the stock clock is still replaced", ClockController.skipDraw(clock));
            shown.hideDate = true;
            ConfigStore.update(a, shown);
            ShadowLooper.idleMainLooper();
            assertTrue("the switch hides the date again", ClockController.skipDraw(date));

            // Screen off: the system hides the date, on screen on it shows and fades it in again. Never drawn.
            date.setVisibility(android.view.View.INVISIBLE);
            ShadowLooper.idleMainLooper(3100, java.util.concurrent.TimeUnit.MILLISECONDS);
            date.setVisibility(android.view.View.VISIBLE);
            date.setAlpha(0.5f);
            assertTrue("still not drawn, without waiting for a scan", ClockController.skipDraw(date));

            // Re-inflated: a new date view with the same id is not drawn from its first frame.
            TextView fresh = dateView(a);
            host.removeView(date);
            host.addView(fresh, 0);
            assertTrue("re-inflated date not drawn", ClockController.skipDraw(fresh));
            TextView other = new TextView(a);
            host.addView(other);
            assertFalse(ClockController.skipDraw(other));


            a.getWindowManager().removeViewImmediate(a.getWindow().getDecorView());
            assertFalse("lock screen gone: nothing blocked", ClockController.skipDraw(fresh));
        } finally {
            ClockController.drawHooked = false;
        }
    }

    @Test public void largeDigitsGrowDownFromTheNativeClockPosition() throws Exception {
        ClockController.modulePath = TestGifs.fakeApk(tmp.getRoot()).getPath();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(a.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        ConfigStore.ensure(a);
        dev.aniclock.Config hidden = new dev.aniclock.Config();
        hidden.sizePct = 250;
        ConfigStore.update(a, hidden);

        FrameLayout host = new FrameLayout(a);
        android.widget.LinearLayout column = new android.widget.LinearLayout(a);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView date = dateView(a);
        date.setGravity(Gravity.CENTER);
        TextView clock = big(a, "10:08");
        clock.setGravity(Gravity.CENTER);
        column.addView(date, new android.widget.LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        column.addView(clock, new android.widget.LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = 150;
        host.addView(column, clp);
        a.setContentView(host);
        ShadowLooper.idleMainLooper();

        ClockController.attach(host, true);
        Object c = controllerOf(host);
        AppTest.waitFor(() -> {
            host.getViewTreeObserver().dispatchOnPreDraw();
            return date.getAlpha() == 0f && flag(c, "visible");
        });
        float hiddenTop = num(c, "rowTop");
        float dateBottom = 150 + date.getBottom();
        assertTrue("larger digits stay below the date area", hiddenTop >= dateBottom);
        assertTrue("the clock's top stays at its native position", hiddenTop >= 150 + clock.getTop());

        assertEquals("the date keeps its place", dateBottom, 150 + date.getBottom(), 0f);
        ConfigStore.update(a, new dev.aniclock.Config());
        ShadowLooper.idleMainLooper();
        a.getWindowManager().removeViewImmediate(a.getWindow().getDecorView());
    }

    @Test @Config(qualifiers = "w1080dp-h1920dp-mdpi")
    public void shownDateAboveStaysPutWhileTheClockKeepsItsDefaultGap() throws Exception {
        try (DateScene scene = new DateScene(true, true, true)) {
            float textSize = scene.date.getTextSize();
            scene.size(140, 0);
            float normalY = scene.date.getTranslationY();
            float normalTop = scene.artBounds().top;
            float gap = normalTop - scene.bounds(scene.date).bottom;
            assertEquals("the date stays in its native position", 3f, normalY, 0.001f);
            for (int size : new int[]{60, 100, 140, 180, 250}) {
                scene.size(size, 0);
                assertEquals("constant date position at " + size, normalY, scene.date.getTranslationY(), 0.001f);
                assertEquals("the top edge stays at its default-size position at " + size,
                        normalTop, scene.artBounds().top, 2f);
                assertEquals("constant visible-pixel gap at " + size,
                        gap, scene.artBounds().top - scene.bounds(scene.date).bottom, 2f);
            }
            assertFalse("date can leave the stock clock column", scene.column.getClipChildren());
            assertEquals(textSize, scene.date.getTextSize(), 0f);
            assertEquals(1f, scene.date.getScaleX(), 0f);
            assertEquals(1f, scene.date.getScaleY(), 0f);
            scene.size(140, 40);
            assertEquals(40f, scene.artBounds().top - normalTop, 2f);
            assertEquals(gap, scene.artBounds().top - scene.bounds(scene.date).bottom, 2f);
            scene.date.setTranslationY(13f); // a new native animation offset must survive disabling the module
            scene.frame();
            scene.config.enabled = false;
            ConfigStore.update(scene.activity, scene.config);
            ShadowLooper.idleMainLooper();
            assertEquals(13f, scene.date.getTranslationY(), 0f);
            assertTrue(scene.column.getClipChildren());
            assertTrue(scene.column.getClipToPadding());
        }
    }

    @Test @Config(qualifiers = "w1080dp-h1920dp-mdpi")
    public void shownDateBelowMovesDownOnlyWhenLargeClockNeedsRoom() throws Exception {
        try (DateScene scene = new DateScene(false, false, true)) {
            float requiredGap = Math.max(4f, scene.stockGap);
            scene.size(60, 0);
            float smallY = scene.date.getTranslationY();
            android.graphics.RectF small = scene.artBounds();
            assertEquals("a small clock leaves the date at its native position", 3f, smallY, 0.001f);
            assertTrue(scene.bounds(scene.date).top - small.bottom >= requiredGap - 2f);
            scene.size(140, 0);
            float normalY = scene.date.getTranslationY();
            android.graphics.RectF normal = scene.artBounds();
            scene.size(250, 0);
            android.graphics.RectF large = scene.artBounds();
            float largeY = scene.date.getTranslationY();
            assertEquals("one clock anchor is used at every size", normal.top, large.top, 2f);
            assertEquals(small.top, large.top, 2f);
            assertTrue("the large clock moves the date down instead of covering it", largeY > normalY);
            assertEquals(requiredGap, scene.bounds(scene.date).top - large.bottom, 2f);
            assertTrue("the widget stays below the moved date",
                    scene.bounds(scene.widget).top >= scene.bounds(scene.date).bottom - 1f);
            float position = largeY;
            ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS);
            for (int i = 0; i < 40; i++) scene.frame();
            assertEquals("a scan must not learn our own offset", position, scene.date.getTranslationY(), 0.001f);
            scene.config.hideDate = true;
            ConfigStore.update(scene.activity, scene.config);
            ShadowLooper.idleMainLooper();
            scene.frame();
            assertEquals(0f, scene.date.getAlpha(), 0f);
            assertEquals(3f, scene.date.getTranslationY(), 0f);
            assertTrue(scene.column.getClipChildren());
            scene.config.hideDate = false;
            ConfigStore.update(scene.activity, scene.config);
            ShadowLooper.idleMainLooper();
            scene.frame();
            assertEquals(1f, scene.date.getAlpha(), 0f);
            assertEquals(position, scene.date.getTranslationY(), 0.001f);
            assertEquals(requiredGap, scene.bounds(scene.date).top - scene.bottom(), 2f);
        }
    }

    @Test @Config(qualifiers = "w1080dp-h1920dp-mdpi")
    public void dateDoesNotJumpAfterTheFirstTickFollowingScreenOn() throws Exception {
        try (DateScene scene = new DateScene(false, false, true)) {
            scene.size(250, 0);
            float y = scene.date.getTranslationY();
            float gap = scene.bounds(scene.date).top - scene.artBounds().bottom;
            android.graphics.RectF before = scene.artBounds();
            android.os.PowerManager power = scene.activity.getSystemService(android.os.PowerManager.class);
            shadowOf(power).setIsInteractive(false);
            scene.date.setVisibility(android.view.View.INVISIBLE);
            scene.activity.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF));
            ShadowLooper.idleMainLooper(1100, java.util.concurrent.TimeUnit.MILLISECONDS);
            scene.date.setTranslationY(3f);
            scene.date.setAlpha(0.6f);
            shadowOf(power).setIsInteractive(true);
            scene.activity.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_SCREEN_ON));
            ShadowLooper.idleMainLooper();
            scene.frame();
            assertEquals("the clock keeps one anchor while the date is not visible yet", before.top, scene.artBounds().top, 2f);
            scene.date.setVisibility(android.view.View.VISIBLE);
            scene.frame(); // transition rescan places the newly visible date before this frame is drawn
            float firstFrame = scene.date.getTranslationY();
            ShadowLooper.idleMainLooper(1100, java.util.concurrent.TimeUnit.MILLISECONDS);
            scene.frame();
            assertEquals(y, firstFrame, 0.001f);
            assertEquals(firstFrame, scene.date.getTranslationY(), 0.001f);
            assertEquals(0.6f, scene.date.getAlpha(), 0f);
            assertEquals(gap, scene.bounds(scene.date).top - scene.artBounds().bottom, 2f);
        }
    }

    @Test @Config(qualifiers = "w1080dp-h1920dp-mdpi")
    public void aodUsesTheSameAnchorAndMovesDateBelowLargeClock() throws Exception {
        try (DateScene scene = new DateScene(false, false, true)) {
            android.os.PowerManager power = scene.activity.getSystemService(android.os.PowerManager.class);
            shadowOf(power).setIsInteractive(false);
            scene.activity.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF));
            ShadowLooper.idleMainLooper();
            float top = Float.NaN, previousDateY = Float.NEGATIVE_INFINITY;
            float requiredGap = Math.max(4f, scene.stockGap);
            for (int size : new int[]{60, 140, 250}) {
                scene.size(size, 0);
                android.graphics.RectF art = scene.artBounds();
                android.graphics.RectF date = scene.bounds(scene.date);
                assertTrue("AOD keeps a clear date gap at " + size,
                        date.top - art.bottom >= requiredGap - 2f);
                assertTrue("the AOD date stays below the clock at " + size, date.top >= art.bottom);
                assertTrue("the AOD date only moves down as the clock grows at " + size,
                        scene.date.getTranslationY() >= previousDateY);
                if (!Float.isNaN(top)) assertEquals("AOD never switches the clock anchor", top, art.top, 2f);
                top = art.top;
                previousDateY = scene.date.getTranslationY();
            }
            assertTrue("a large AOD clock moves the date below its artwork", previousDateY > 3f);
            ShadowLooper.idleMainLooper(1100, java.util.concurrent.TimeUnit.MILLISECONDS);
            scene.frame();
            assertEquals(top, scene.artBounds().top, 2f);
        }
    }

    @Test @Config(qualifiers = "w1080dp-h1920dp-mdpi")
    public void smallClockDoesNotChangePositionWhenInteractiveStateFlips() throws Exception {
        try (DateScene scene = new DateScene(false, false, true)) {
            scene.size(60, 0);
            float top = scene.artBounds().top;
            float dateY = scene.date.getTranslationY();
            android.os.PowerManager power = scene.activity.getSystemService(android.os.PowerManager.class);
            shadowOf(power).setIsInteractive(false);
            scene.activity.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF));
            ShadowLooper.idleMainLooper();
            scene.frame();
            assertEquals("entering AOD does not select another clock position", top, scene.artBounds().top, 2f);
            assertEquals(dateY, scene.date.getTranslationY(), 0.001f);
            shadowOf(power).setIsInteractive(true);
            scene.activity.sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_SCREEN_ON));
            ShadowLooper.idleMainLooper();
            scene.frame();
            assertEquals("leaving AOD does not select another clock position", top, scene.artBounds().top, 2f);
            assertEquals(dateY, scene.date.getTranslationY(), 0.001f);
        }
    }

    private final class DateScene implements AutoCloseable {
        final org.robolectric.android.controller.ActivityController<Activity> activityController;
        final Activity activity;
        final FrameLayout host;
        final android.widget.LinearLayout column;
        final TextView date;
        final android.view.View widget;
        final dev.aniclock.Config config = new dev.aniclock.Config();
        final Object controller;
        final float stockGap;

        DateScene(boolean above, boolean scaledParent, boolean padded) throws Exception {
            ClockController.modulePath = TestGifs.fakeApk(tmp.getRoot(), padded).getPath();
            activityController = Robolectric.buildActivity(Activity.class).setup().visible();
            activity = activityController.get();
            shadowOf(activity.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
            ConfigStore.ensure(activity);
            config.hideDate = false;
            config.sizePct = 100;
            ConfigStore.update(activity, config);
            host = new FrameLayout(activity);
            column = new android.widget.LinearLayout(activity);
            column.setOrientation(android.widget.LinearLayout.VERTICAL);
            if (scaledParent) column.setScaleY(0.8f);
            date = dateView(activity);
            date.setGravity(Gravity.CENTER);
            date.setTranslationY(3f);
            TextView clock = big(activity, "10:08");
            clock.setGravity(Gravity.CENTER);
            widget = new android.view.View(activity);
            if (above) column.addView(date);
            column.addView(clock);
            if (!above) {
                android.widget.LinearLayout lower = new android.widget.LinearLayout(activity);
                lower.setOrientation(android.widget.LinearLayout.VERTICAL);
                lower.addView(date);
                lower.addView(widget, new android.widget.LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 80));
                column.addView(lower);
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = 320;
            host.addView(column, params);
            activity.setContentView(host);
            ShadowLooper.idleMainLooper();
            stockGap = above ? bounds(clock).top - bounds(date).bottom : bounds(date).top - bounds(clock).bottom;
            ClockController.attach(host, true);
            controller = controllerOf(host);
            AppTest.waitFor(() -> { frame(); return flag(controller, "visible"); });
        }

        void frame() { host.getViewTreeObserver().dispatchOnPreDraw(); }

        void size(int percent, int offset) {
            config.sizePct = percent;
            config.offsetDp = offset;
            ConfigStore.update(activity, config);
            ShadowLooper.idleMainLooper();
            frame();
        }

        float bottom() { return artBounds().bottom; }

        android.graphics.RectF artBounds() {
            int left = (int) Math.floor(num(controller, "rowLeft")), top = (int) Math.floor(num(controller, "rowTop"));
            int width = Math.max(1, (int) Math.ceil(num(controller, "rowRight")) - left);
            int height = Math.max(1, (int) Math.ceil(num(controller, "rowTop") + num(controller, "rowHeight")) - top);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.translate(-left, -top);
                overlayOf(host).draw(canvas);
                android.graphics.RectF bounds = pixels(bitmap);
                bounds.offset(left, top);
                return bounds;
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                bitmap.recycle();
            }
        }

        android.graphics.RectF bounds(android.view.View view) {
            android.graphics.Matrix transform = new android.graphics.Matrix();
            view.transformMatrixToGlobal(transform);
            android.graphics.Matrix hostTransform = new android.graphics.Matrix(), inverse = new android.graphics.Matrix();
            host.transformMatrixToGlobal(hostTransform);
            assertTrue(hostTransform.invert(inverse));
            transform.postConcat(inverse);
            android.graphics.RectF bounds = new android.graphics.RectF(0, 0, view.getWidth(), view.getHeight());
            if (view instanceof TextView) {
                Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    view.draw(new Canvas(bitmap));
                    bounds = pixels(bitmap);
                } finally {
                    bitmap.recycle();
                }
            }
            transform.mapRect(bounds);
            return bounds;
        }

        @Override public void close() {
            config.enabled = false;
            ConfigStore.update(activity, config);
            ShadowLooper.idleMainLooper();
            activityController.pause().stop().destroy();
        }
    }

    private static android.graphics.RectF pixels(Bitmap bitmap) {
        int left = bitmap.getWidth(), top = bitmap.getHeight(), right = -1, bottom = -1;
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) {
                if ((row[x] >>> 24) == 0) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = y;
            }
        }
        assertTrue("the view draws visible pixels", right >= left && bottom >= top);
        return new android.graphics.RectF(left, top, right + 1, bottom + 1);
    }

    private static TextView dateView(Activity a) {
        TextView t = new TextView(a);
        t.setId(android.R.id.text1);
        t.setText(new java.text.SimpleDateFormat("EEE, d MMMM", java.util.Locale.getDefault()).format(new java.util.Date()));
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        return t;
    }

    private static float num(Object c, String name) {
        try {
            return field(c, name);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean flag(Object c, String name) {
        try {
            java.lang.reflect.Field f = ClockController.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object controllerOf(android.view.View host) throws Exception {
        java.lang.reflect.Field hosts = ClockController.class.getDeclaredField("HOSTS");
        hosts.setAccessible(true);
        return ((java.util.Map<?, ?>) hosts.get(null)).get(host);
    }

    private static float field(Object c, String name) throws Exception {
        java.lang.reflect.Field f = ClockController.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(c);
    }

    private static TextView big(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 72);
        return t;
    }

    private static android.graphics.drawable.Drawable overlayOf(android.view.View host) throws Exception {
        java.lang.reflect.Field hosts = ClockController.class.getDeclaredField("HOSTS");
        hosts.setAccessible(true);
        Object c = ((java.util.Map<?, ?>) hosts.get(null)).get(host);
        java.lang.reflect.Field f = ClockController.class.getDeclaredField("overlay");
        f.setAccessible(true);
        return (android.graphics.drawable.Drawable) f.get(c);
    }
}
