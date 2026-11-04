package com.lightchase.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The cross-fade rule every changing line in the UI follows: a new string is never swapped in
 * place - the old one goes first, the string changes while invisible, then the new one comes up.
 */
public class TextFadeTest {

    private static final float FRAME = 1f / 60f;

    private static void run(TextFade fade, String target, int frames) {
        for (int i = 0; i < frames; i++) fade.step(FRAME, target);
    }

    @Test
    public void aFirstStringFadesInFromNothing() {
        TextFade fade = new TextFade();
        fade.step(FRAME, "READY");
        assertTrue("it must not appear at full strength in one frame", fade.alpha() < 0.2f);
        run(fade, "READY", 60);
        assertEquals("READY", fade.shown());
        assertTrue(fade.alpha() > 0.95f);
    }

    @Test
    public void aNewStringIsNeverSwappedInPlace() {
        TextFade fade = new TextFade();
        run(fade, "READY", 60);
        fade.step(FRAME, "CANCEL");
        assertEquals("the old text must still be on screen while it fades out", "READY", fade.shown());
        assertTrue(fade.alpha() < 1f);
        run(fade, "CANCEL", 120);
        assertEquals("CANCEL", fade.shown());
        assertTrue(fade.alpha() > 0.95f);
    }

    @Test
    public void anEmptyTargetFadesTheLineOut() {
        TextFade fade = new TextFade();
        run(fade, "ERROR", 60);
        run(fade, "", 60);
        assertTrue(fade.alpha() < 0.05f);
        assertFalse("nothing is visible with no text", fade.visible());
    }

    @Test
    public void primingShowsTheFirstStringImmediately() {
        TextFade fade = new TextFade();
        fade.primeWith("CONNECTED");
        assertEquals("CONNECTED", fade.shown());
        assertEquals(1f, fade.alpha(), 0.001f);
    }

    @Test
    public void resetClearsWithoutAnimating() {
        TextFade fade = new TextFade();
        run(fade, "LEFT", 60);
        fade.reset();
        assertEquals("", fade.shown());
        assertEquals(0f, fade.alpha(), 0.001f);
    }
}
