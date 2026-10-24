package com.lightchase.client;

import com.lightchase.contracts.InputFrame;

/**
 * Pure touch state machine: turns pointer events into the contract {@link InputFrame}.
 *
 * <p>Deliberately free of libGDX types so multi-touch, release, cancel and lifecycle clearing can
 * be unit tested instead of only inspected on a device. Coordinates are UI-space (1280x720).
 */
public final class TouchController {

    public static final float UI_WIDTH = 1280f;
    public static final float UI_HEIGHT = 720f;

    public static final float JOY_X = 145f;
    public static final float JOY_Y = 130f;
    public static final float JOY_R = 85f;
    /** The stick grabs a little outside its own radius, as a finger is not a point. */
    public static final float JOY_GRAB = JOY_R * 1.35f;

    public static final float HOLD_X = 1125f;
    public static final float HOLD_Y = 145f;
    public static final float HOLD_R = 70f;

    public static final float DROP_X = 965f;
    public static final float DROP_Y = 105f;
    public static final float DROP_R = 50f;

    /** Inside this fraction of the stick radius the direction is treated as zero. */
    public static final double DEADZONE = 0.15;

    private int joystickPointer = -1;
    private int holdPointer = -1;
    private double moveX;
    private double moveY;
    private boolean interact;
    private boolean dropPulse;

    /** @return true when the event was consumed by a control. */
    public boolean down(int pointer, float x, float y, boolean thiefRole) {
        if (joystickPointer == -1 && distance(x, y, JOY_X, JOY_Y) <= JOY_GRAB) {
            joystickPointer = pointer;
            updateJoystick(x, y);
            return true;
        }
        if (thiefRole && holdPointer == -1 && distance(x, y, HOLD_X, HOLD_Y) <= HOLD_R) {
            holdPointer = pointer;
            interact = true;
            return true;
        }
        if (thiefRole && distance(x, y, DROP_X, DROP_Y) <= DROP_R) {
            dropPulse = true;
            return true;
        }
        return false;
    }

    public boolean drag(int pointer, float x, float y) {
        if (pointer == joystickPointer) {
            updateJoystick(x, y);
            return true;
        }
        if (pointer == holdPointer) {
            // Sliding off the button releases the hold but keeps the pointer captured, so sliding
            // back re-arms it without a second touch.
            interact = distance(x, y, HOLD_X, HOLD_Y) <= HOLD_R;
            return true;
        }
        return false;
    }

    public void up(int pointer) { release(pointer); }

    public void cancel(int pointer) { release(pointer); }

    private void release(int pointer) {
        if (pointer == joystickPointer) {
            joystickPointer = -1;
            moveX = 0;
            moveY = 0;
        }
        if (pointer == holdPointer) {
            holdPointer = -1;
            interact = false;
        }
    }

    /** Called on page change, background, disconnect and match reset. */
    public void clear() {
        joystickPointer = -1;
        holdPointer = -1;
        moveX = 0;
        moveY = 0;
        interact = false;
        dropPulse = false;
    }

    /** Builds the frame to submit and consumes the one-shot drop pulse. */
    public InputFrame frame() {
        InputFrame frame = new InputFrame(moveX, moveY, interact, dropPulse);
        dropPulse = false;
        return frame;
    }

    public boolean hasPointers() { return joystickPointer != -1 || holdPointer != -1; }
    public int joystickPointer() { return joystickPointer; }
    public int holdPointer() { return holdPointer; }
    public double moveX() { return moveX; }
    public double moveY() { return moveY; }
    public boolean interacting() { return interact; }

    private void updateJoystick(float x, float y) {
        double dx = (x - JOY_X) / JOY_R;
        double dy = (y - JOY_Y) / JOY_R;
        double length = Math.hypot(dx, dy);
        if (length <= DEADZONE) {
            moveX = 0;
            moveY = 0;
            return;
        }
        double strength = Math.min(1.0, (length - DEADZONE) / (1.0 - DEADZONE));
        moveX = dx / length * strength;
        moveY = dy / length * strength;
    }

    private static double distance(float ax, float ay, float bx, float by) {
        return Math.hypot(ax - bx, ay - by);
    }
}
