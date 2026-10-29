package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Drawing primitives for the blackout layout.
 *
 * <p>The old screens were built from four flat {@code ShapeRenderer.rect} calls plus left-aligned
 * text. Everything here exists to make the mockup reproducible with the two things the client
 * actually has: filled rectangles and a font. No textures, no rounded corners, no shadows.
 */
final class UiDraw {

    /** Reused so drawing a frame does not allocate a layout per string. */
    private static final GlyphLayout LAYOUT = new GlyphLayout();
    private static final java.util.IdentityHashMap<BitmapFont, Float> CAPS =
            new java.util.IdentityHashMap<>();

    private UiDraw() { }

    // ------------------------------------------------------------------ shapes

    /** A horizontal rule, three units thick so it survives a phone's pixel density. */
    static void rule(ShapeRenderer shapes, float x, float top, float width) {
        shapes.setColor(UiTheme.LINE);
        shapes.rect(x, UiTheme.yFromTop(top, UiTheme.RULE_H), width, UiTheme.RULE_H);
    }

    /** A one pixel vertical rule, anchored at {@code left} and running {@code height} downward. */
    static void ruleV(ShapeRenderer shapes, float left, float top, float height) {
        shapes.setColor(UiTheme.LINE);
        shapes.rect(left, UiTheme.yFromTop(top, height), UiTheme.RULE_H, height);
    }

    static void fill(ShapeRenderer shapes, Color color, float x, float top, float w, float h) {
        shapes.setColor(color);
        shapes.rect(x, UiTheme.yFromTop(top, h), w, h);
    }

    /** Primary action: a solid light slab with dark text. The only filled control on the screen. */
    static void primaryButton(ShapeRenderer shapes, float x, float top, float w, float h, boolean enabled) {
        fill(shapes, enabled ? UiTheme.TEXT : UiTheme.FAINT, x, top, w, h);
    }

    static void underline(ShapeRenderer shapes, float x, float top, float w) {
        shapes.setColor(UiTheme.ACCENT);
        shapes.rect(x, UiTheme.yFromTop(top, 2f), w, 2f);
    }

    /**
     * Shows a marker for either a sticky state (the current choice) or a transient one (this row
     * was just tapped). A press that is only waiting for the server still draws something.
     */
    static void underline(ShapeRenderer shapes, float x, float top, float w, boolean sticky, boolean transientPress) {
        if (!sticky && !transientPress) return;
        shapes.setColor(transientPress ? UiTheme.TEXT : UiTheme.ACCENT);
        shapes.rect(x, UiTheme.yFromTop(top, 2f), w, 2f);
    }

    // ------------------------------------------------------------------ text

    /** Draws with the cap top at {@code top} instead of a baseline, so layout reads like the mockup. */
    static void text(Batch batch, BitmapFont font, String s, float x, float top, Color color) {
        text(batch, font, s, x, top, color, 0f);
    }

    /** Same, with letter spacing in pixels. Tracking is what makes small caps look deliberate. */
    static void text(Batch batch, BitmapFont font, String s, float x, float top, Color color, float tracking) {
        if (s == null || s.isEmpty()) return;
        font.setColor(color);
        // BitmapFont.draw places the top of the line at y, not the baseline, so the top-based
        // coordinate converts straight across.
        float baseline = UiTheme.H - top;
        if (tracking == 0f) {
            font.draw(batch, s, x, baseline);
            return;
        }
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            font.draw(batch, String.valueOf(c), cx, baseline);
            BitmapFont.Glyph g = font.getData().getGlyph(c);
            cx += (g != null ? g.xadvance : font.getSpaceXadvance()) + tracking;
        }
    }

    static float capHeight(BitmapFont font) {
        Float cached = CAPS.get(font);
        if (cached != null) return cached;
        // Ink height of a cap letter. getCapHeight() and the layout height both include the face's
        // ascent, which drew every label roughly a tenth of an em too low.
        BitmapFont.Glyph g = font.getData().getGlyph('H');
        float h = g != null ? g.height : font.getCapHeight() * 0.72f;
        CAPS.put(font, h);
        return h;
    }

    /** Width of a tracked string, for right-aligned columns and underlines. */
    static float width(BitmapFont font, String s, float tracking) {
        if (s == null || s.isEmpty()) return 0f;
        if (tracking == 0f) return font.getData().getGlyph('M') == null ? s.length() * 8f : measure(font, s);
        float w = 0f;
        for (int i = 0; i < s.length(); i++) {
            BitmapFont.Glyph g = font.getData().getGlyph(s.charAt(i));
            w += (g != null ? g.xadvance : font.getSpaceXadvance()) + tracking;
        }
        return w - tracking;
    }

    /**
     * Truncates to fit {@code maxWidth}. Room ids and opponent descriptions come from the server,
     * so a long one must not push the layout into the neighbouring column.
     */
    static String fit(BitmapFont font, String s, float maxWidth, float tracking) {
        if (s == null) return "";
        if (width(font, s, tracking) <= maxWidth) return s;
        String ellipsis = "...";
        float e = width(font, ellipsis, tracking);
        StringBuilder out = new StringBuilder();
        float w = 0f;
        for (int i = 0; i < s.length(); i++) {
            BitmapFont.Glyph g = font.getData().getGlyph(s.charAt(i));
            float adv = (g != null ? g.xadvance : font.getSpaceXadvance()) + tracking;
            if (w + adv + e > maxWidth) break;
            out.append(s.charAt(i));
            w += adv;
        }
        return out + ellipsis;
    }

    private static float measure(BitmapFont font, String s) {
        float w = 0f;
        for (int i = 0; i < s.length(); i++) {
            BitmapFont.Glyph g = font.getData().getGlyph(s.charAt(i));
            w += g != null ? g.xadvance : font.getSpaceXadvance();
        }
        return w;
    }
}
