package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;

/**
 * The small text primitives every page draws with: a centred line, a caption under a control, a
 * label/value readout, a white plate, a label/value row, and the two fit-to-width helpers.
 *
 * <p>These lived inside the 2300-line {@code LightChaseApp} as private methods, which meant a page
 * could not be moved out of that class without dragging the helpers along. They only need the batch,
 * the fonts and the rounded-plate renderer, so they live here and both the app and any page that
 * gets extracted can share them.
 */
final class Widgets {

    private final Batch batch;
    private final UiFonts fonts;
    private final UiRound round;
    private final Color ink = new Color();

    Widgets(Batch batch, UiFonts fonts, UiRound round) {
        this.batch = batch;
        this.fonts = fonts;
        this.round = round;
    }

    /** A line centred horizontally on {@code cx}, top-based y. */
    void centered(BitmapFont font, String text, float cx, float top, Color color, float tracking) {
        if (text == null || text.isEmpty()) return;
        float w = UiDraw.width(font, text, tracking);
        UiDraw.text(batch, font, text, cx - w / 2f, top, color, tracking);
    }

    /** A grey caption under a control, faded by {@code alpha}. */
    void caption(String text, float cx, float top, float alpha) {
        if (alpha <= 0.01f) return;
        ink.set(UiTheme.MUTED);
        ink.a = alpha;
        centered(fonts.mono, text, cx, top, ink, 1.5f);
    }

    /** One compact row: grey label, value beside it on the same baseline, fading with the value. */
    void readout(String label, TextFade fade, float top) {
        UiDraw.text(batch, fonts.mono, label, UiTheme.MARGIN, top, UiTheme.MUTED, 1.2f);
        if (fade.alpha() <= 0.01f) return;
        ink.set(UiTheme.TEXT);
        ink.a = fade.alpha();
        UiDraw.text(batch, fonts.mono, fade.shown(),
                UiTheme.MARGIN + UiDraw.width(fonts.mono, label, 1.2f) + 16f, top, ink, 1.2f);
    }

    /** White rounded plate: a grey caption over a dark value, both centred. */
    void plate(float x, float top, float w, float h, String label, String value, Color valueColor) {
        round.fill(batch, Color.WHITE, x, top, w, h);
        float labelCap = UiDraw.capHeight(fonts.mono);
        float valueCap = UiDraw.capHeight(fonts.displaySmall);
        float pad = (h - labelCap - valueCap) / 3f;
        ink.set(0.30f, 0.30f, 0.32f, 1f);
        centered(fonts.mono, label, x + w / 2f, top + pad, ink, 0.6f);
        centered(fonts.displaySmall, value, x + w / 2f, top + pad * 2f + labelCap, valueColor, 0.5f);
    }

    /** A grey label followed by a value on the same line; the gap follows the label's width. */
    void line(String label, String value, float x, float top, Color valueColor) {
        UiDraw.text(batch, fonts.mono, label, x, top, UiTheme.MUTED, 1.2f);
        UiDraw.text(batch, fonts.mono, value, x + UiDraw.width(fonts.mono, label, 1.2f) + 22f,
                top, valueColor, 0.6f);
    }

    /** Draws {@code label} fitted into {@code w}, using the widest CTA word as the scale. */
    void fitCta(String label, float x, float top, float w, float h, Color color) {
        fitText(fonts.cta, label, x, top, w, h, color, ctaScale(w));
    }

    /** One scale for every CTA word, measured against the longest one, so text never shifts. */
    float ctaScale(float width) {
        float avail = width - 28f;
        float longest = UiDraw.width(fonts.cta, "WAITING...", 1.5f);
        return longest > avail ? Math.max(0.60f, avail / longest) : 1f;
    }


    // ---- controls with press feedback ------------------------------------------------------

    /** The navy used by every primary action. */
    private static final Color PRIMARY = new Color(0.13f, 0.25f, 0.40f, 1f);

    /**
     * A white chip: a small grey label over a dark value, both centred.
     *
     * @param lit 0..1 press wash. White cannot wash to white, so a press drains the text instead.
     */
    void chip(float x, float top, float w, float h, String label, String value, float lit) {
        round.fill(batch, Color.WHITE, x, top, w, h);
        float cx = x + w / 2f;
        float hLabel = UiDraw.capHeight(fonts.mono);
        float hValue = UiDraw.capHeight(fonts.displaySmall);
        float pad = (h - hLabel - hValue) / 3f;
        Color labelInk = new Color(0.22f, 0.22f, 0.22f, 1f);
        labelInk.lerp(Color.WHITE, lit);
        Color valueInk = new Color(UiTheme.ON_LIGHT);
        valueInk.lerp(Color.WHITE, lit);
        centered(fonts.mono, label, cx, top + pad, labelInk, 0.4f);
        centered(fonts.displaySmall, UiDraw.fit(fonts.displaySmall, value, w - 32f, 0.5f),
                cx, top + pad * 2f + hLabel, valueInk, 0.5f);
    }

    /** Deep navy slab with a white label; washes to white while pressed. */
    void primaryButton(float x, float top, float w, float h, String label, boolean enabled,
                       float lit) {
        Color fill = new Color(enabled ? PRIMARY : UiTheme.LINE);
        fill.lerp(Color.WHITE, lit);
        round.fill(batch, fill, x, top, w, h);
        float cw = UiDraw.width(fonts.cta, label, 1.5f);
        float baseline = top + (h - UiDraw.capHeight(fonts.cta)) / 2f;
        UiDraw.text(batch, fonts.cta, label, x + (w - cw) / 2f, baseline,
                enabled ? Color.WHITE : UiTheme.MUTED, 1.5f);
    }

    /** Accent slab with a white label; the wash is the same as every other control. */
    void framedAction(float x, float top, float w, float h, String label, boolean live, float lit,
                      float alpha) {
        Color fill = new Color(UiTheme.ACCENT);
        fill.lerp(Color.WHITE, lit);
        fill.a = alpha;
        round.fill(batch, fill, x, top, w, h);
        Color ink = live ? Color.WHITE : new Color(1f, 1f, 1f, 0.72f);
        Color text = new Color(ink.r, ink.g, ink.b, ink.a * alpha);
        fitText(fonts.mono, label, x, top, w, h, text);
    }

    /** Draws {@code label} inside a button, shrinking to fit. */
    void fitText(BitmapFont font, String label, float x, float top, float w, float h, Color color) {
        fitText(font, label, x, top, w, h, color, 0f);
    }

    /** As {@link #fitText} but with a forced scale, so swapping the word never shifts the glyphs. */
    void fitText(BitmapFont font, String label, float x, float top, float w, float h, Color color,
                 float forcedK) {
        float avail = w - 28f;
        float full = UiDraw.width(font, label, 1.5f);
        float k = forcedK > 0f ? forcedK : (full > avail ? Math.max(0.60f, avail / full) : 1f);
        BitmapFont.BitmapFontData d = font.getData();
        float base = d.scaleX;
        if (k < 1f) d.setScale(base * k);
        float cap = UiDraw.capHeight(font) * k;
        float ty = top + (h - cap) / 2f;
        UiDraw.text(batch, font, label, x + (w - full * k) / 2f, ty, color, 1.5f);
        if (k < 1f) d.setScale(base);
    }
}
