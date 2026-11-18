package dev.aniclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The promo is a header inside the ScrollView: hidden at rest, revealed by scrolling to the top. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PullDownPromoTest {

    @Test public void hidesAtRestAndFollowsScroll() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        ScrollView scroll = new ScrollView(a);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        View tall = new View(a);
        content.addView(tall, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4000));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PullDownPromo.install(a, scroll, content);
        layout(scroll);

        LinearLayout column = (LinearLayout) scroll.getChildAt(0);
        FrameLayout header = (FrameLayout) column.getChildAt(0);
        ImageView image = (ImageView) header.getChildAt(0);
        int headerHeight = header.getHeight();

        assertTrue("promo header has a real height", headerHeight > 0);
        assertEquals("settings start at the top, promo is hidden", headerHeight, scroll.getScrollY());
        assertEquals("promo is transparent when closed", 0f, image.getAlpha(), 0.001f);

        scroll.scrollTo(0, 0);
        assertEquals("promo is fully visible at the top", 1f, image.getAlpha(), 0.001f);
        assertEquals(1f, image.getScaleX(), 0.001f);
        assertEquals("the list is never transformed", 0f, column.getTranslationY(), 0.001f);

        scroll.scrollTo(0, headerHeight / 2);
        assertEquals("the list remains untransformed while moving",
                0f, column.getTranslationY(), 0.001f);
        assertEquals("the GIF remains at its final size", 1f, image.getScaleX(), 0.001f);

        scroll.scrollTo(0, headerHeight);
        assertEquals("promo is invisible again at rest", 0f, image.getAlpha(), 0.001f);
        assertEquals(1f, image.getScaleX(), 0.001f);
        assertEquals("the list is still untransformed", 0f, column.getTranslationY(), 0.001f);
    }

    @Test public void heavyPullUsesRealScrollWithoutLayerTransforms() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        ScrollView scroll = new ScrollView(a);
        LinearLayout content = new LinearLayout(a);
        content.addView(new View(a), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 4000));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        PullDownPromo.install(a, scroll, content);
        layout(scroll);

        LinearLayout column = (LinearLayout) scroll.getChildAt(0);
        FrameLayout header = (FrameLayout) column.getChildAt(0);
        ImageView image = (ImageView) header.getChildAt(0);
        int height = header.getHeight();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 500, 500, 0);
        MotionEvent move = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE,
                500, 500 + height, 0);
        scroll.dispatchTouchEvent(down);
        scroll.dispatchTouchEvent(move);
        down.recycle();
        move.recycle();

        int revealed = height - scroll.getScrollY();
        assertTrue("a full-header finger drag reveals something", revealed > 0);
        assertTrue("the pull is strongly resisted", revealed < height / 2);
        assertEquals("no translated copy of the interface", 0f,
                column.getTranslationY(), 0.001f);
        assertEquals("no smaller animated copy", 1f, image.getScaleX(), 0.001f);
        assertEquals(1f, image.getScaleY(), 0.001f);
    }

    @Test public void shortSettingsPageStillLetsPromoScrollAway() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        ScrollView scroll = new ScrollView(a);
        LinearLayout content = new LinearLayout(a);
        View shortPage = new View(a);
        content.addView(shortPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 200));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PullDownPromo.install(a, scroll, content);
        layout(scroll);
        // On a real device this callback runs from the window's layout pass.
        scroll.getViewTreeObserver().dispatchOnGlobalLayout();
        layout(scroll);
        scroll.getViewTreeObserver().dispatchOnGlobalLayout();
        layout(scroll);

        LinearLayout column = (LinearLayout) scroll.getChildAt(0);
        FrameLayout header = (FrameLayout) column.getChildAt(0);
        ImageView image = (ImageView) header.getChildAt(0);
        int headerHeight = header.getHeight();
        assertTrue(headerHeight > 0);
        assertEquals("promo is hidden even on a short page", headerHeight, scroll.getScrollY());
        assertEquals(0f, image.getAlpha(), 0.001f);

        scroll.scrollTo(0, 100000);
        assertTrue("the list can scroll at least a full header up",
                scroll.getScrollY() >= headerHeight);
        scroll.scrollTo(0, headerHeight);
    }

    @Test public void tapOpensTelegram() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        ScrollView scroll = new ScrollView(a);
        LinearLayout content = new LinearLayout(a);
        View tall = new View(a);
        content.addView(tall, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4000));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PullDownPromo.install(a, scroll, content);
        layout(scroll);

        LinearLayout column = (LinearLayout) scroll.getChildAt(0);
        FrameLayout header = (FrameLayout) column.getChildAt(0);
        ImageView image = (ImageView) header.getChildAt(0);
        scroll.scrollTo(0, 0);
        image.performClick();

        Intent started = shadowOf(a).getNextStartedActivity();
        assertEquals("tg://resolve?domain=arch1place", String.valueOf(started.getData()));
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1920);
        view.getViewTreeObserver().dispatchOnPreDraw();
    }
}
