package com.lightchase.client;

/**
 * Tiny easing helpers. Everything animated in the lobby goes through these so the motion is
 * consistent: exponential approach for state, sine for idle motion.
 */
final class UiAnim {

    private UiAnim() { }

    /** Frame-rate independent approach: {@code speed} is roughly "how many times per second". */
    static float approach(float current, float target, float dt, float speed) {
        float k = Math.min(1f, dt * speed);
        return current + (target - current) * k;
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Smooth 0..1 curve, no overshoot. */
    static float smooth(float t) {
        float x = clamp01(t);
        return x * x * (3f - 2f * x);
    }

    /** Mixes two colours into {@code out} without allocating. */
    static void mix(com.badlogic.gdx.graphics.Color out, com.badlogic.gdx.graphics.Color a,
                    com.badlogic.gdx.graphics.Color b, float t) {
        float k = clamp01(t);
        out.r = a.r + (b.r - a.r) * k;
        out.g = a.g + (b.g - a.g) * k;
        out.b = a.b + (b.b - a.b) * k;
        out.a = a.a + (b.a - a.a) * k;
    }

    /** Idle sway in -1..1, used for the wandering flashlight and the bobbing gem. */
    static float sway(float seconds, float speed) {
        return (float) Math.sin(seconds * speed);
    }
}
