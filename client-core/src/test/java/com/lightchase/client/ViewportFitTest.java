package com.lightchase.client;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Scaling;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Proves requirement A06 without a device: whatever the screen aspect ratio, the visible world
 * extent is identical and the remaining space becomes a letterbox.
 *
 * <p>{@code FitViewport} cannot be exercised here because its {@code update()} touches {@code Gdx.gl}.
 * It is built on {@link Scaling#fit}, which is pure maths, so the test drives that directly and then
 * pins the concrete numbers observed on the two emulators (phone 2424x1080, tablet 2560x1600).
 */
public class ViewportFitTest {

    private static final float WORLD_WIDTH = 128f / 9f;   // 14.222...
    private static final float WORLD_HEIGHT = 8f;

    /** The viewport FitViewport would produce for a given screen, as {x, y, width, height}. */
    private static float[] fit(int screenWidth, int screenHeight) {
        // Scaling.fit returns the scaled SIZE (not the scale factor).
        Vector2 size = Scaling.fit.apply(WORLD_WIDTH, WORLD_HEIGHT, screenWidth, screenHeight);
        float x = (screenWidth - size.x) / 2f;
        float y = (screenHeight - size.y) / 2f;
        return new float[]{x, y, size.x, size.y};
    }

    /** Pixels per world unit, derived from the letterboxed height. */
    private static float pixelsPerUnit(float[] viewport) {
        return viewport[3] / WORLD_HEIGHT;
    }

    @Test public void worldExtentIsIdenticalOnEveryAspectRatio() {
        int[][] screens = {
                {2424, 1080},   // phone, 20.2:9
                {2560, 1600},   // tablet, 16:10
                {1920, 1080},   // 16:9 reference
                {1080, 2424},   // portrait
                {2400, 1080},   // 20:9
        };
        for (int[] s : screens) {
            float[] v = fit(s[0], s[1]);
            float scale = pixelsPerUnit(v);
            assertEquals("the visible world width must never change", WORLD_WIDTH, v[2] / scale, 1e-3);
            assertTrue("content must fit inside the screen", v[2] <= s[0] + 0.5f && v[3] <= s[1] + 0.5f);
        }
    }

    @Test public void phoneLetterboxesOnTheSides() {
        float[] v = fit(2424, 1080);
        // scale = min(2424 / 14.222, 1080 / 8) = 135 -> 1920 x 1080 centred horizontally.
        assertEquals(1920f, v[2], 0.5f);
        assertEquals(1080f, v[3], 0.5f);
        assertEquals(252f, v[0], 0.5f);
        assertEquals(0f, v[1], 0.5f);
    }

    @Test public void tabletLetterboxesTopAndBottom() {
        float[] v = fit(2560, 1600);
        // scale = min(2560 / 14.222, 1600 / 8) = 180 -> 2560 x 1440 centred vertically.
        assertEquals(2560f, v[2], 0.5f);
        assertEquals(1440f, v[3], 0.5f);
        assertEquals(0f, v[0], 0.5f);
        assertEquals(80f, v[1], 0.5f);
    }

    @Test public void widePhoneLetterboxesOnSidesTallTabletOnTopAndBottom() {
        float[] phone = fit(2424, 1080);
        float[] tablet = fit(2560, 1600);
        assertTrue("a wider screen must letterbox on the sides", phone[0] > 0);
        assertTrue("a taller screen must letterbox top and bottom", tablet[1] > 0);
        assertEquals("both show the same world width",
                phone[2] / pixelsPerUnit(phone), tablet[2] / pixelsPerUnit(tablet), 1e-3);
    }
}
