package com.lightchase.client;

/**
 * One line of changing text, cross-faded.
 *
 * <p>The rule the whole UI follows: text never swaps in place. When the target changes the current
 * string fades out (0.15 s), the string is replaced only once it is invisible, and the new one fades
 * in (about 0.3 s). Identical targets just hold, and an empty target fades to nothing.
 *
 * <p>Every changing label on every page owns one instance: the error line, the notice, the
 * connection status, the lobby footer, the three match readouts and the countdown digit. They used
 * to be nine hand-written copies of the same three-branch state machine.
 */
final class TextFade {

    /** Fade-in speed; {@link UiAnim#approach} reaches the target in roughly 1/speed seconds. */
    private static final float IN_SPEED = 3.2f;
    /** Fade-out takes this many seconds, slightly quicker so a change feels responsive. */
    private static final float OUT_SECONDS = 0.15f;

    private String shown = "";
    private float alpha;

    /** The text currently drawn. Never null. */
    String shown() {
        return shown;
    }

    /** 0 when invisible, 1 when settled. */
    float alpha() {
        return alpha;
    }

    /** True when this line has something visible to draw. */
    boolean visible() {
        return !shown.isEmpty() && alpha > 0.01f;
    }

    /** Call once per frame with the text this line should be showing. */
    void step(float dt, String target) {
        String next = target == null ? "" : target;
        if (next.equals(shown)) {
            alpha = UiAnim.approach(alpha, next.isEmpty() ? 0f : 1f, dt, IN_SPEED);
        } else {
            alpha = Math.max(0f, alpha - dt / OUT_SECONDS);
            if (alpha <= 0f) shown = next;
        }
    }

    /** Snap back to empty without animating, for a hard reset between sessions. */
    void reset() {
        shown = "";
        alpha = 0f;
    }

    /** Immediately show the given text at full strength, used for the very first frame. */
    void primeWith(String text) {
        shown = text == null ? "" : text;
        alpha = shown.isEmpty() ? 0f : 1f;
    }
}
