package com.lightchase.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.lightchase.contracts.GroundMaterial;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.WallCell;
import com.lightchase.data.DataFiles;

import java.util.Map;

/**
 * The minimap panel in the top-left corner of the match screen.
 *
 * <p>It used to be the last 80 lines of {@code WorldRenderer}: a second UI pass with its own camera
 * and its own blend state, sitting inside the class that draws the room. Nothing about it belongs to
 * the world pass, so it lives on its own and takes the few fades it needs.
 */
final class Minimap {

    private static final float TARGET_W = 210f;
    private static final float TARGET_H = 150f;
    private static final float MARGIN = 64f;
    private static final float PAD = 7f;

    private static final Color WALL_TOP = new Color(0.169f, 0.212f, 0.259f, 1f);
    private static final Color WALL_FACE = new Color(0.106f, 0.137f, 0.173f, 1f);
    private static final Color MATERIAL = new Color(0.949f, 0.741f, 0.239f, 1f);
    private static final Color FOE = new Color(0.882f, 0.278f, 0.278f, 1f);
    private static final Color LIVE = new Color(0.36f, 0.92f, 0.70f, 1f);
    private static final Color DROP_BLUE = new Color(0.42f, 0.62f, 1f, 1f);
    private static final Color EXIT_RED = new Color(0.86f, 0.26f, 0.22f, 1f);

    private final ShapeRenderer shapes;
    private final Viewport viewport;
    private final OrthographicCamera camera;
    private final DataFiles data;
    private final Map<String, float[]> dropOpen;
    private final Map<String, float[]> exitOpen;
    private final Map<String, float[]> lootFade;

    private final float w;
    private final float h;
    private final float scale;

    Minimap(ShapeRenderer shapes, Viewport viewport, OrthographicCamera camera, DataFiles data,
            Map<String, float[]> dropOpen, Map<String, float[]> exitOpen,
            Map<String, float[]> lootFade) {
        this.shapes = shapes;
        this.viewport = viewport;
        this.camera = camera;
        this.data = data;
        this.dropOpen = dropOpen;
        this.exitOpen = exitOpen;
        this.lootFade = lootFade;
        this.scale = scaleOf(data.map.width, data.map.height);
        this.w = data.map.width * scale;
        this.h = data.map.height * scale;
    }

    /** Keeps the panel inside its target box whatever the map's aspect is. */
    static float scaleOf(int mapWidth, int mapHeight) {
        if (mapWidth <= 0 || mapHeight <= 0) return 1f;
        return Math.min(TARGET_W / mapWidth, TARGET_H / mapHeight);
    }

    /** Draws the panel; call between frames, it owns its own begin/end and blend state. */
    void render(PlayerView view, ViewInterpolator interpolator) {
        viewport.apply();
        shapes.setProjectionMatrix(camera.combined);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Top-left corner: the HUD readouts live to its right.
        float x0 = MARGIN;
        float y0 = viewport.getWorldHeight() - 40f - h;

        // Soft drop shadow, then a graduated frame, then the slab: the map reads as a panel
        // floating over the room instead of a rectangle stamped onto it.
        ShapeKit.rounded(shapes, x0 - PAD - 6f, y0 - PAD - 8f, w + PAD * 2f + 12f,
                h + PAD * 2f + 12f, PAD + 10f, new Color(0f, 0f, 0f, 0.35f));
        ShapeKit.rounded(shapes, x0 - PAD, y0 - PAD, w + PAD * 2f, h + PAD * 2f,
                PAD + 5f, new Color(1f, 1f, 1f, 0.20f));
        ShapeKit.rounded(shapes, x0 - PAD + 2f, y0 - PAD + 2f, w + PAD * 2f - 4f,
                h + PAD * 2f - 4f, PAD + 3f, new Color(0.043f, 0.055f, 0.070f, 0.90f));

        shapes.setColor(WALL_FACE.r, WALL_FACE.g, WALL_FACE.b, 0.85f);
        for (WallCell wall : data.map.walls) {
            shapes.rect(x0 + wall.col * scale, y0 + wall.row * scale, scale, scale);
        }
        shapes.setColor(WALL_TOP.r, WALL_TOP.g, WALL_TOP.b, 0.85f);
        for (WallCell wall : data.map.walls) {
            shapes.rect(x0 + wall.col * scale, y0 + wall.row * scale + scale * 0.6f,
                    scale, scale * 0.4f);
        }

        // Loot is white, drop points blue, exits red; both turn green when they are usable.
        if (view != null && view.thief != null) {
            for (GroundMaterial material : view.thief.groundMaterials) {
                float[] f = lootFade.get(material.materialId);
                shapes.setColor(1f, 1f, 1f, f == null ? 1f : f[0]);
                shapes.circle(x0 + (float) material.x * scale, y0 + (float) material.y * scale,
                        Math.max(2f, scale * 0.22f), 10);
            }
        }
        for (MapPoint point : data.map.deliveries) {
            shapes.setColor(new Color(DROP_BLUE).lerp(LIVE, openness(dropOpen, point.id)));
            shapes.circle(x0 + (float) point.x * scale, y0 + (float) point.y * scale,
                    Math.max(2.2f, scale * 0.26f), 12);
        }
        for (MapPoint point : data.map.exits) {
            shapes.setColor(new Color(EXIT_RED).lerp(LIVE, openness(exitOpen, point.id)));
            shapes.circle(x0 + (float) point.x * scale, y0 + (float) point.y * scale,
                    Math.max(2.6f, scale * 0.30f), 12);
        }
        if (view != null && view.thief != null) {
            shapes.setColor(MATERIAL);
            for (GroundMaterial material : view.thief.groundMaterials) {
                shapes.circle(x0 + (float) material.x * scale, y0 + (float) material.y * scale,
                        Math.max(1.8f, scale * 0.18f), 10);
            }
        }
        if (interpolator.opponentVisible()) {
            shapes.setColor(FOE);
            shapes.circle(x0 + (float) interpolator.opponentX() * scale,
                    y0 + (float) interpolator.opponentY() * scale, 3.2f, 12);
        }
        if (view != null) {
            float dx = x0 + (float) interpolator.selfX() * scale;
            float dy = y0 + (float) interpolator.selfY() * scale;
            shapes.setColor(1f, 1f, 1f, 0.28f);
            shapes.circle(dx, dy, 6f, 16);
            shapes.setColor(Color.WHITE);
            shapes.circle(dx, dy, 3.2f, 14);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** 0..1 opening amount of one point, so its colour can cross-fade. */
    private static float openness(Map<String, float[]> states, String id) {
        float[] state = states.get(id);
        return state == null ? 0f : state[0];
    }
}
