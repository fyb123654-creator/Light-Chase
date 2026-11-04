package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;

/**
 * The inline text editor used to change the server host, the port and the room.
 *
 * <p>It is drawn instead of the platform dialog: a dimmed screen, a light panel, one white field
 * with a blinking caret, and two navy buttons. The keyboard itself is the platform's, so this class
 * only paints; {@code LightChaseApp} decides what an OK or CANCEL press means.
 */
final class FieldEditor {

    static final float W = 780f;
    static final float H = 164f;
    static final float TOP = 8f;
    static final float BTN_TOP = 108f;
    static final float BTN_W = 150f;
    static final float BTN_H = 44f;

    private static final Color PANEL = new Color(0.855f, 0.867f, 0.886f, 1f);
    private static final Color PANEL_LABEL = new Color(0.30f, 0.30f, 0.32f, 1f);
    private static final Color FIELD_INK = new Color(0.13f, 0.13f, 0.15f, 1f);
    /** Same navy as every primary action in the app. */
    private static final Color BUTTON = new Color(0.13f, 0.25f, 0.40f, 1f);

    private FieldEditor() {
    }

    /**
     * Draws the whole editor, including the dimmed backdrop.
     *
     * @param title    the field's name, shown above the input
     * @param text     what has been typed so far
     * @param washOk   0..1 press wash on OK
     * @param washCancel 0..1 press wash on CANCEL
     */
    static void draw(PageContext ctx, String title, String text, float washOk,
                     float washCancel, float alpha) {
        float screenW = ctx.worldWidth;
        float a = Math.min(1f, Math.max(0f, alpha));
        ctx.round.rect(ctx.batch, new Color(0f, 0f, 0f, 0.55f * a), 0f, 0f, screenW, UiTheme.H);

        float px = (screenW - W) / 2f;
        ctx.round.fill(ctx.batch, tint(PANEL, a), px, TOP, W, H);
        UiDraw.text(ctx.batch, ctx.fonts.mono, title, px + 22f, TOP + 12f, tint(PANEL_LABEL, a), 2f);

        float fx = px + 22f;
        float fy = TOP + 30f;
        float fw = W - 44f;
        float fh = 66f;
        ctx.round.fill(ctx.batch, tint(Color.WHITE, a), fx, fy, fw, fh);

        String shown = text;
        while (UiDraw.width(ctx.fonts.monoBig, shown, 1f) > fw - 36f && shown.length() > 1) {
            shown = shown.substring(1);
        }
        boolean caretOn = ((System.nanoTime() / 500_000_000L) & 1L) == 0L;
        UiDraw.text(ctx.batch, ctx.fonts.monoBig, shown + (caretOn ? "|" : ""), fx + 18f,
                fy + (fh - UiDraw.capHeight(ctx.fonts.monoBig)) / 2f, tint(FIELD_INK, a), 1f);

        float by = TOP + BTN_TOP;
        button(ctx, fx, by, "CANCEL", washCancel, a);
        button(ctx, fx + fw - BTN_W, by, "OK", washOk, a);
    }

    /** Navy rounded button that washes white while it is pressed. */
    /** The same colour at {@code alpha}, so the whole panel can fade as one piece. */
    private static Color tint(Color base, float alpha) {
        return new Color(base.r, base.g, base.b, base.a * alpha);
    }

    private static void button(PageContext ctx, float x, float top, String label, float lit,
                              float alpha) {
        Color fill = new Color(BUTTON).lerp(Color.WHITE, lit);
        fill.a = alpha;
        ctx.round.fill(ctx.batch, fill, x, top, BTN_W, BTN_H);
        float cw = UiDraw.width(ctx.fonts.mono, label, 2f);
        UiDraw.text(ctx.batch, ctx.fonts.mono, label, x + (BTN_W - cw) / 2f,
                top + (BTN_H - UiDraw.capHeight(ctx.fonts.mono)) / 2f, tint(Color.WHITE, alpha), 2f);
    }
}
