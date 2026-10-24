package com.lightchase.client;

import com.lightchase.contracts.InputFrame;
import org.junit.Test;
import static org.junit.Assert.*;

public class TouchControllerTest {

    private static final float CX = TouchController.JOY_X;
    private static final float CY = TouchController.JOY_Y;
    private static final float HX = TouchController.HOLD_X;
    private static final float HY = TouchController.HOLD_Y;

    @Test public void deadzoneProducesNoMovement() {
        TouchController c = new TouchController();
        c.down(1, CX + 5, CY, true);
        assertEquals(0, c.moveX(), 0);
        assertEquals(0, c.moveY(), 0);
        assertTrue(c.frame().interactHeld == false);
    }

    @Test public void stickIsNormalisedAndNeverExceedsUnitLength() {
        TouchController c = new TouchController();
        // Inside the grab radius but well past the deadzone: full deflection.
        assertTrue(c.down(1, CX + 100, CY, true));
        assertEquals(1.0, Math.hypot(c.moveX(), c.moveY()), 1e-6);
        assertEquals(1.0, c.moveX(), 1e-6);
        assertEquals(0.0, c.moveY(), 1e-6);

        // Diagonal deflection is normalised, not doubled.
        c.clear();
        assertTrue(c.down(2, CX + 80, CY + 80, true));
        double x = c.moveX();
        double y = c.moveY();
        assertTrue("components stay within the contract range", Math.abs(x) <= 1 && Math.abs(y) <= 1);
        assertEquals(1.0, Math.hypot(x, y), 1e-6);
        assertEquals(Math.sqrt(0.5), x, 1e-6);
        assertEquals(Math.sqrt(0.5), y, 1e-6);
    }

    @Test public void pressesFarFromTheStickDoNotGrabIt() {
        TouchController c = new TouchController();
        assertFalse("a touch outside the grab radius must be ignored", c.down(1, CX + 400, CY + 400, true));
        assertEquals(0, c.moveX(), 0);
        assertEquals(-1, c.joystickPointer());
    }

    @Test public void twoPointersCanMoveAndHoldTogether() {
        TouchController c = new TouchController();
        assertTrue(c.down(1, CX + 60, CY, true));
        assertTrue(c.down(2, HX, HY, true));
        assertEquals(1, c.joystickPointer());
        assertEquals(2, c.holdPointer());
        InputFrame f = c.frame();
        assertTrue("movement and hold must coexist", f.interactHeld);
        assertTrue(f.moveX > 0);
    }

    @Test public void releasingTheStickKeepsTheHold() {
        TouchController c = new TouchController();
        c.down(1, CX + 60, CY, true);
        c.down(2, HX, HY, true);
        c.up(1);
        InputFrame f = c.frame();
        assertEquals(0, f.moveX, 0);
        assertTrue("releasing the stick must not cancel the hold", f.interactHeld);
    }

    @Test public void cancellingAPointerClearsThatControl() {
        TouchController c = new TouchController();
        c.down(1, CX + 60, CY, true);
        c.down(2, HX, HY, true);
        c.cancel(2);
        InputFrame f = c.frame();
        assertFalse("a cancelled touch must not keep holding", f.interactHeld);
        assertTrue(f.moveX > 0);
    }

    @Test public void slidingOffTheHoldButtonReleasesAndSlidingBackReArms() {
        TouchController c = new TouchController();
        c.down(2, HX, HY, true);
        assertTrue(c.interacting());
        c.drag(2, HX + 200, HY);
        assertFalse("leaving the button cancels the hold", c.interacting());
        assertEquals("the pointer stays captured", 2, c.holdPointer());
        c.drag(2, HX, HY);
        assertTrue("returning re-arms without a second touch", c.interacting());
    }

    @Test public void clearResetsPointersAndMovement() {
        TouchController c = new TouchController();
        c.down(1, CX + 60, CY, true);
        c.down(2, HX, HY, true);
        c.down(3, TouchController.DROP_X, TouchController.DROP_Y, true);
        c.clear();
        assertFalse(c.hasPointers());
        assertEquals(-1, c.joystickPointer());
        assertEquals(-1, c.holdPointer());
        InputFrame f = c.frame();
        assertEquals(0, f.moveX, 0);
        assertEquals(0, f.moveY, 0);
        assertFalse(f.interactHeld);
        assertFalse("a cleared drop must not fire later", f.dropPressed);
    }

    @Test public void policeCannotUseThiefControls() {
        TouchController c = new TouchController();
        assertFalse(c.down(1, HX, HY, false));
        assertFalse(c.down(2, TouchController.DROP_X, TouchController.DROP_Y, false));
        InputFrame f = c.frame();
        assertFalse(f.interactHeld);
        assertFalse(f.dropPressed);
        assertTrue("the stick still works for the police", c.down(3, CX + 60, CY, false));
    }

    @Test public void dropIsOneShot() {
        TouchController c = new TouchController();
        assertTrue(c.down(1, TouchController.DROP_X, TouchController.DROP_Y, true));
        assertTrue(c.frame().dropPressed);
        assertFalse("the pulse must not repeat on the next frame", c.frame().dropPressed);
    }
}
