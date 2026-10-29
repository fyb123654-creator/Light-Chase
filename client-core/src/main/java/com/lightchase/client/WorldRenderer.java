package com.lightchase.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.lightchase.contracts.*;
import com.lightchase.contracts.geometry.WorldGeometry;
import com.lightchase.data.DataFiles;

/**
 * Draws the match world: map, wall-clipped police beam, actors, and the minimap.
 *
 * <p>Reads only role-filtered {@link PlayerView} data plus the shared map/rules. The beam boundary
 * is computed with {@link WorldGeometry#segmentBlocked} so the drawn cone and the server's judgement
 * come from one implementation.
 *
 * <p>Art direction is deliberately separate from the menu: the menu is pure black and white, while
 * the match is a dark room seen from above - deep blue floor, slate walls, one warm beam, and
 * role-coloured actors. Both share the same restraint: no gradients, no shadows, few colours.
 */
final class WorldRenderer {

    private static final int BEAM_RAYS = 56;
    private static final int BEAM_REFINE = 5;

    static final float MM_X = 1010f;
    static final float MM_Y = 372f;
    static final float MM_SCALE = 10f;

    // Match palette.
    private static final Color FLOOR = new Color(0.043f, 0.063f, 0.090f, 1f);   // #0B1017
    private static final Color GRID = new Color(1f, 1f, 1f, 0.035f);
    private static final Color WALL = new Color(0.110f, 0.157f, 0.212f, 1f);   // #1C2836
    private static final Color WALL_EDGE = new Color(0.165f, 0.231f, 0.306f, 1f);
    private static final Color DROP = new Color(0.165f, 0.663f, 0.549f, 1f);   // teal
    private static final Color EXIT_SHUT = new Color(0.420f, 0.325f, 0.125f, 1f);
    private static final Color EXIT_OPEN = new Color(0.941f, 0.706f, 0.161f, 1f);
    private static final Color MATERIAL = new Color(0.941f, 0.706f, 0.161f, 1f);
    private static final Color BEAM = new Color(1f, 0.925f, 0.741f, 1f);
    private static final Color SELF_POLICE = new Color(0.435f, 0.718f, 0.910f, 1f);
    private static final Color SELF_THIEF = new Color(0.878f, 0.416f, 0.353f, 1f);
    private static final Color FOE = new Color(0.882f, 0.278f, 0.278f, 1f);
    private static final Color INK = new Color(0.020f, 0.031f, 0.043f, 1f);

    private final ShapeRenderer shapes;
    private final OrthographicCamera worldCamera;
    private final OrthographicCamera uiCamera;
    private final FitViewport worldViewport;
    private final Viewport uiViewport;
    private final DataFiles data;
    private final float[] beamX = new float[BEAM_RAYS];
    private final float[] beamY = new float[BEAM_RAYS];

    WorldRenderer(ShapeRenderer shapes, OrthographicCamera worldCamera, OrthographicCamera uiCamera,
                  FitViewport worldViewport, Viewport uiViewport, DataFiles data) {
        this.shapes = shapes;
        this.worldCamera = worldCamera;
        this.uiCamera = uiCamera;
        this.worldViewport = worldViewport;
        this.uiViewport = uiViewport;
        this.data = data;
    }

    void renderWorld(PlayerView view, ViewInterpolator interpolator) {
        worldViewport.apply();
        double cx = view == null ? data.map.width / 2.0 : interpolator.selfX();
        double cy = view == null ? data.map.height / 2.0 : interpolator.selfY();
        worldCamera.position.set((float) cx, (float) cy, 0f);
        worldCamera.update();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(worldCamera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        drawFloor();
        drawWalls();
        if (view != null) drawBeam(view);
        drawPoints(view);
        if (view != null) drawActors(view, interpolator);

        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Floor plus a one-unit grid; the grid is what makes movement readable. */
    private void drawFloor() {
        shapes.setColor(FLOOR);
        shapes.rect(0, 0, data.map.width, data.map.height);
        shapes.setColor(GRID);
        for (int x = 1; x < data.map.width; x++) shapes.rect(x - 0.01f, 0, 0.02f, data.map.height);
        for (int y = 1; y < data.map.height; y++) shapes.rect(0, y - 0.01f, data.map.width, 0.02f);
    }

    private void drawWalls() {
        shapes.setColor(WALL);
        for (WallCell wall : data.map.walls) shapes.rect(wall.col, wall.row, 1f, 1f);
        // A lighter cap on the side that faces the camera: the flat top-down view would otherwise
        // read as one solid mass.
        shapes.setColor(WALL_EDGE);
        for (WallCell wall : data.map.walls) shapes.rect(wall.col, wall.row + 0.86f, 1f, 0.14f);
    }

    private void drawPoints(PlayerView view) {
        // Drop points: a teal disc with a quiet outer ring.
        shapes.setColor(DROP);
        for (MapPoint point : data.map.deliveries) {
            shapes.circle((float) point.x, (float) point.y, 0.30f, 24);
        }
        shapes.setColor(1f, 1f, 1f, 0.35f);
        for (MapPoint point : data.map.deliveries) {
            shapes.circle((float) point.x, (float) point.y, 0.20f, 24);
        }
        // Exits: shut ones stay muted, open ones glow.
        boolean open = view != null && view.exitsOpen;
        shapes.setColor(open ? EXIT_OPEN : EXIT_SHUT);
        for (MapPoint point : data.map.exits) {
            shapes.rect((float) point.x - 0.32f, (float) point.y - 0.32f, 0.64f, 0.64f);
        }
        if (open) {
            shapes.setColor(1f, 0.85f, 0.45f, 0.30f);
            for (MapPoint point : data.map.exits) {
                shapes.circle((float) point.x, (float) point.y, 0.62f, 24);
            }
        }
    }

    private void drawActors(PlayerView view, ViewInterpolator interpolator) {
        if (view.thief != null) {
            for (GroundMaterial material : view.thief.groundMaterials) {
                shapes.setColor(1f, 1f, 1f, 0.18f);
                shapes.circle((float) material.x, (float) material.y, 0.24f, 16);
                shapes.setColor(MATERIAL);
                shapes.circle((float) material.x, (float) material.y, 0.16f, 16);
            }
        }
        if (interpolator.opponentVisible()) {
            float ox = (float) interpolator.opponentX();
            float oy = (float) interpolator.opponentY();
            shapes.setColor(FOE.r, FOE.g, FOE.b, 0.22f);
            shapes.circle(ox, oy, 0.46f, 32);
            shapes.setColor(FOE);
            shapes.circle(ox, oy, 0.26f, 32);
            shapes.setColor(INK);
            shapes.circle(ox, oy, 0.16f, 24);
            if (interpolator.opponentCarrying()) {
                // Contract §7: carrying is the only carry-related fact the police gets.
                shapes.setColor(MATERIAL);
                shapes.circle(ox + 0.34f, oy + 0.34f, 0.11f, 14);
            }
        }
        float sx = (float) interpolator.selfX();
        float sy = (float) interpolator.selfY();
        Color mine = view.role == Role.THIEF ? SELF_THIEF : SELF_POLICE;
        shapes.setColor(mine.r, mine.g, mine.b, 0.20f);
        shapes.circle(sx, sy, 0.42f, 32);
        shapes.setColor(mine);
        shapes.circle(sx, sy, 0.26f, 32);
        shapes.setColor(INK);
        shapes.circle(sx, sy, 0.15f, 24);
        // A heading tick: the beam already shows the police direction, but the thief needs one too.
        double heading = view.self.headingRad;
        float hx = (float) Math.cos(heading);
        float hy = (float) Math.sin(heading);
        shapes.setColor(mine);
        shapes.triangle(sx + hx * 0.44f, sy + hy * 0.44f,
                sx + hx * 0.26f - hy * 0.10f, sy + hy * 0.26f + hx * 0.10f,
                sx + hx * 0.26f + hy * 0.10f, sy + hy * 0.26f - hx * 0.10f);
    }

    /** The police cone: three nested passes give it depth without a real gradient. */
    private void drawBeam(PlayerView view) {
        if (view.role != Role.POLICE) return;
        double cx = view.self.x;
        double cy = view.self.y;
        double heading = view.self.headingRad;
        double half = Math.toRadians(data.rules.beamAngleDeg) / 2.0;
        double range = data.rules.beamRange;

        float[] innerX = new float[BEAM_RAYS];
        float[] innerY = new float[BEAM_RAYS];

        for (int pass = 0; pass < 3; pass++) {
            double shrink = 1.0 - pass * 0.22;
            double widthScale = 1.0 - pass * 0.18;
            for (int i = 0; i < BEAM_RAYS; i++) {
                double angle = heading - half * widthScale
                        + (2.0 * half * widthScale) * i / (BEAM_RAYS - 1);
                double dx = Math.cos(angle);
                double dy = Math.sin(angle);
                double dist = unblockedDistance(cx, cy, dx, dy, range * shrink);
                beamX[i] = (float) (cx + dx * dist);
                beamY[i] = (float) (cy + dy * dist);
                if (pass == 0) {
                    innerX[i] = beamX[i];
                    innerY[i] = beamY[i];
                }
            }
            shapes.setColor(BEAM.r, BEAM.g, BEAM.b, 0.075f);
            for (int i = 0; i < BEAM_RAYS - 1; i++) {
                shapes.triangle((float) cx, (float) cy, beamX[i], beamY[i], beamX[i + 1], beamY[i + 1]);
            }
        }

        // Bright rim along the far edge so the cone has a readable boundary on the floor.
        shapes.setColor(BEAM.r, BEAM.g, BEAM.b, 0.55f);
        for (int i = 0; i < BEAM_RAYS - 1; i++) {
            shapes.rectLine(innerX[i], innerY[i], innerX[i + 1], innerY[i + 1], 0.035f);
        }
        shapes.setColor(BEAM.r, BEAM.g, BEAM.b, 0.85f);
        shapes.circle((float) cx, (float) cy, 0.12f, 16);
    }

    /**
     * Longest wall-free distance along one ray, using {@link WorldGeometry#segmentBlocked} so the
     * drawn cone and the server's judgement share a single implementation.
     */
    private double unblockedDistance(double x, double y, double dx, double dy, double range) {
        if (!WorldGeometry.segmentBlocked(data.map, x, y, x + dx * range, y + dy * range)) return range;
        double lo = 0;
        double hi = range;
        for (int r = 0; r < BEAM_REFINE + 7; r++) {
            double mid = (lo + hi) / 2.0;
            if (WorldGeometry.segmentBlocked(data.map, x, y, x + dx * mid, y + dy * mid)) hi = mid;
            else lo = mid;
        }
        return lo;
    }

    /** Minimap. Ground materials appear only when the thief view carries them (contract §7). */
    void renderMinimap(PlayerView view, ViewInterpolator interpolator) {
        uiViewport.apply();
        shapes.setProjectionMatrix(uiCamera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        float w = data.map.width * MM_SCALE;
        float h = data.map.height * MM_SCALE;

        shapes.setColor(1f, 1f, 1f, 0.90f);                       // outer border
        shapes.rect(MM_X - 4f, MM_Y - 4f, w + 8f, h + 8f);
        shapes.setColor(FLOOR);
        shapes.rect(MM_X - 2f, MM_Y - 2f, w + 4f, h + 4f);
        shapes.setColor(0.055f, 0.078f, 0.110f, 1f);
        shapes.rect(MM_X, MM_Y, w, h);

        shapes.setColor(WALL_EDGE);
        for (WallCell wall : data.map.walls) {
            shapes.rect(MM_X + wall.col * MM_SCALE, MM_Y + wall.row * MM_SCALE, MM_SCALE, MM_SCALE);
        }
        shapes.setColor(DROP);
        for (MapPoint point : data.map.deliveries) {
            shapes.rect(MM_X + (float) point.x * MM_SCALE - 2f, MM_Y + (float) point.y * MM_SCALE - 2f, 4f, 4f);
        }
        shapes.setColor(view != null && view.exitsOpen ? EXIT_OPEN : EXIT_SHUT);
        for (MapPoint point : data.map.exits) {
            shapes.rect(MM_X + (float) point.x * MM_SCALE - 3f, MM_Y + (float) point.y * MM_SCALE - 3f, 6f, 6f);
        }
        if (view != null && view.thief != null) {
            shapes.setColor(MATERIAL);
            for (GroundMaterial material : view.thief.groundMaterials) {
                shapes.circle(MM_X + (float) material.x * MM_SCALE, MM_Y + (float) material.y * MM_SCALE,
                        2.5f, 10);
            }
        }
        if (interpolator.opponentVisible()) {
            shapes.setColor(FOE);
            shapes.circle(MM_X + (float) interpolator.opponentX() * MM_SCALE,
                    MM_Y + (float) interpolator.opponentY() * MM_SCALE, 3.2f, 12);
        }
        if (view != null) {
            float dx = MM_X + (float) interpolator.selfX() * MM_SCALE;
            float dy = MM_Y + (float) interpolator.selfY() * MM_SCALE;
            shapes.setColor(1f, 1f, 1f, 0.30f);
            shapes.circle(dx, dy, 6f, 16);
            shapes.setColor(Color.WHITE);
            shapes.circle(dx, dy, 3.4f, 14);
        }
        shapes.end();
    }
}
