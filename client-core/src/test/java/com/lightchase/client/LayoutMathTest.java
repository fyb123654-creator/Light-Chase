package com.lightchase.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The layout arithmetic that can be checked without a GL context.
 *
 * <p>Pixels still need a screenshot, but the numbers behind them - the minimap's scale, the
 * top-to-bottom conversion, the UI-space height - are plain maths and are asserted here.
 */
public class LayoutMathTest {

    @Test
    public void theMinimapFitsItsBoxWhateverTheMapShape() {
        float wide = Minimap.scaleOf(24, 18);
        // 24x18 is wider than 210x150, so the height is what binds.
        assertEquals(150f / 18f, wide, 0.0001f);
        assertTrue("it must fit the width too", 24 * wide <= 210f + 0.001f);

        float tall = Minimap.scaleOf(10, 40);
        assertEquals("a tall map is bound by the height", 150f / 40f, tall, 0.0001f);
        assertTrue(40 * tall <= 150f + 0.001f);

        assertEquals("a degenerate map must not divide by zero", 1f, Minimap.scaleOf(0, 0), 0.0001f);
    }

    @Test
    public void aTopBasedYConvertsToTheBottomUpSpace() {
        // The UI draws top-down, the shape pass draws bottom-up; every page crosses this line.
        assertEquals(720f - 40f - 56f, UiTheme.yFromTop(40f, 56f), 0.0001f);
        assertEquals("a box at the bottom of the screen sits at y=0", 0f, UiTheme.yFromTop(720f, 0f), 0.0001f);
        assertEquals("a full-height box starts at 0", 0f, UiTheme.yFromTop(0f, 720f), 0.0001f);
        assertEquals("its top edge is the screen height", 720f, UiTheme.yFromTop(0f, 0f), 0.0001f);
    }

    @Test
    public void theUiHeightIsSixteenByNine() {
        assertEquals(1280f, UiTheme.W, 0.0001f);
        assertEquals(720f, UiTheme.H, 0.0001f);
    }
}
