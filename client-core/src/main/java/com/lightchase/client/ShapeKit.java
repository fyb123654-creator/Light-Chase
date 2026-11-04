package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Every rounded shape the game draws, in one place.
 *
 * <p>{@code ShapeRenderer} only knows rectangles, circles and triangles, so a rounded corner is
 * always built the same way: a cross of three rectangles plus four corner discs. That recipe used to
 * be copied into five different classes with sightly different arguments; this is the single copy.
 *
 * <p>All coordinates are y-up shape coordinates. Callers that work top-down convert first.
 */
final class ShapeKit {

    private ShapeKit() {
    }

    /** Filled rounded rectangle. Radius is clamped so a caller cannot ask for an impossible shape. */
    static void rounded(ShapeRenderer shapes, float x, float y, float w, float h, float radius,
                        Color color) {
        float r = Math.max(0f, Math.min(radius, Math.min(w, h) / 2f));
        shapes.setColor(color);
        if (r <= 0f) {
            shapes.rect(x, y, w, h);
            return;
        }
        shapes.rect(x + r, y, w - 2f * r, h);
        shapes.rect(x, y + r, r, h - 2f * r);
        shapes.rect(x + w - r, y + r, r, h - 2f * r);
        int segments = r > 6f ? 16 : 10;
        shapes.circle(x + r, y + r, r, segments);
        shapes.circle(x + w - r, y + r, r, segments);
        shapes.circle(x + r, y + h - r, r, segments);
        shapes.circle(x + w - r, y + h - r, r, segments);
    }

    /** Filled capsule: a rounded rectangle whose radius is half its height. */
    static void capsule(ShapeRenderer shapes, float x, float y, float w, float h, Color color) {
        rounded(shapes, x, y, w, h, h / 2f, color);
    }

    /** Outline of a rounded rectangle: four edges plus four arcs made of short segments. */
    static void roundedOutline(ShapeRenderer shapes, float x, float y, float w, float h, float r,
                               float thickness, Color color) {
        shapes.setColor(color);
        shapes.rect(x + r, y + h - thickness, w - 2f * r, thickness);
        shapes.rect(x + r, y, w - 2f * r, thickness);
        shapes.rect(x, y + r, thickness, h - 2f * r);
        shapes.rect(x + w - thickness, y + r, thickness, h - 2f * r);
        float arc = r - thickness / 2f;
        int steps = 7;
        for (int corner = 0; corner < 4; corner++) {
            float ccx = (corner == 0 || corner == 3) ? x + r : x + w - r;
            float ccy = (corner < 2) ? y + r : y + h - r;
            float start = (float) (Math.PI * (corner == 0 ? 1.0 : corner == 1 ? 1.5 : corner == 2 ? 2.0 : 2.5));
            float prevX = ccx + (float) Math.cos(start) * arc;
            float prevY = ccy + (float) Math.sin(start) * arc;
            for (int i = 1; i <= steps; i++) {
                float angle = start + (float) (Math.PI / 2.0) * i / steps;
                float nx = ccx + (float) Math.cos(angle) * arc;
                float ny = ccy + (float) Math.sin(angle) * arc;
                shapes.rectLine(prevX, prevY, nx, ny, thickness);
                prevX = nx;
                prevY = ny;
            }
        }
    }

    /** A true ring: segments laid along the circle, so the inside stays see-through. */
    static void ring(ShapeRenderer shapes, float cx, float cy, float r, float thickness,
                     Color color) {
        shapes.setColor(color);
        int segments = 72;
        float px = cx + r;
        float py = cy;
        for (int i = 1; i <= segments; i++) {
            double a = i * 2.0 * Math.PI / segments;
            float nx = cx + (float) Math.cos(a) * r;
            float ny = cy + (float) Math.sin(a) * r;
            shapes.rectLine(px, py, nx, ny, thickness);
            px = nx;
            py = ny;
        }
    }

    /** Fills a disc from the bottom up to {@code level}, clipping every stripe to the circle. */
    static void bottomUpFill(ShapeRenderer shapes, float cx, float cy, float r, float level,
                             Color color) {
        if (level <= 0.01f) return;
        int stripes = 120;
        float top = cy - r + 2f * r * Math.min(1f, level);
        shapes.setColor(color);
        for (int i = 0; i < stripes; i++) {
            float y0 = cy - r + 2f * r * i / stripes;
            if (y0 >= top) break;
            float y1 = Math.min(top, cy - r + 2f * r * (i + 1) / stripes);
            float mid = (y0 + y1) / 2f - cy;
            float half = (float) Math.sqrt(Math.max(0f, r * r - mid * mid));
            shapes.rect(cx - half, y0, half * 2f, Math.max(0.5f, y1 - y0));
        }
    }
}
