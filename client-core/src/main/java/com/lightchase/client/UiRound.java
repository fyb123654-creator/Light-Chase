package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.NinePatch;

/**
 * Rounded corners without an artist.
 *
 * <p>{@code ShapeRenderer} only draws rectangles, so the primary button gets its radius from a
 * texture generated at start-up: one anti-aliased rounded square, sliced into a nine-patch so the
 * corners keep their shape at any button size.
 */
final class UiRound {

    private static final int TEX = 96;          // supersampled source
    private static final int RADIUS = 22;       // in source pixels
    private static final int SLICE = RADIUS + 4;

    private NinePatch patch;
    private com.badlogic.gdx.graphics.Texture dot;

    void create() {
        com.badlogic.gdx.graphics.Pixmap one = new com.badlogic.gdx.graphics.Pixmap(
                1, 1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        one.setColor(com.badlogic.gdx.graphics.Color.WHITE);
        one.fill();
        dot = new Texture(one);
        one.dispose();
        Texture texture = new Texture(buildPixmap());
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        patch = new NinePatch(texture, SLICE, SLICE, SLICE, SLICE);
    }

    /** Filled rounded rectangle, tinted by the batch colour. */
    void fill(Batch batch, Color color, float x, float top, float w, float h) {
        if (patch == null) return;
        batch.setColor(color);
        patch.draw(batch, x, UiTheme.yFromTop(top, h), w, h);
        batch.setColor(Color.WHITE);
    }

    /** A plain filled rectangle in the batch pass, where shapes are not available. */
    void rect(Batch batch, Color color, float x, float top, float w, float h) {
        if (dot == null) return;
        batch.setColor(color);
        batch.draw(dot, x, UiTheme.yFromTop(top, h), w, h);
        batch.setColor(Color.WHITE);
    }

    void dispose() {
        if (patch != null) patch.getTexture().dispose();
        if (dot != null) dot.dispose();
    }

    /**
     * Alpha is computed per pixel from the distance to the rounded-rectangle boundary, which gives
     * a clean edge after the linear downscale; drawing it with plain pixmap fills would alias.
     */
    private static Pixmap buildPixmap() {
        Pixmap map = new Pixmap(TEX, TEX, Pixmap.Format.RGBA8888);
        map.setBlending(Pixmap.Blending.None);
        map.setColor(0f, 0f, 0f, 0f);
        map.fill();
        float half = TEX / 2f;
        float inner = half - RADIUS;
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                float dx = Math.abs(x + 0.5f - half) - inner;
                float dy = Math.abs(y + 0.5f - half) - inner;
                float dist;
                if (dx <= 0 || dy <= 0) {
                    dist = Math.max(Math.max(dx, dy), 0f);
                } else {
                    dist = (float) Math.sqrt(dx * dx + dy * dy);
                }
                // 1 px of coverage across the boundary
                float alpha = Math.min(1f, Math.max(0f, RADIUS - dist + 0.5f));
                if (alpha <= 0f) continue;
                map.setColor(1f, 1f, 1f, alpha);
                map.drawPixel(x, y);
            }
        }
        return map;
    }
}
