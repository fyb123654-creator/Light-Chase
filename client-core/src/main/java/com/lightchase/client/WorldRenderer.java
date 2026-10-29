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

import java.util.HashSet;
import java.util.Set;

/**
 * Draws the match world: map, wall-clipped police beam, actors, and the minimap.
 *
 * <p>Reads only role-filtered {@link PlayerView} data plus the shared map/rules. The beam boundary
 * is computed with {@link WorldGeometry#segmentBlocked} so the drawn cone and the server's judgement
 * come from one implementation.
 *
 * <p><b>Art direction.</b> The menu is flat black and white; the match is a lit room seen from
 * above. What makes a top-down tile room read well is not texture detail but four things, all of
 * which are geometry here: per-tile value variation so a large floor is never one flat slab; a wall
 * with a top face and a side face; a dark outline wherever geometry meets floor; and a light source
 * that gives the eye somewhere to rest. Everything below is procedural.
 */
final class WorldRenderer {

    private static final int BEAM_RAYS = 56;
    private static final int BEAM_REFINE = 5;

    static final float MM_X = 1010f;
    static final float MM_Y = 372f;
    static final float MM_SCALE = 10f;

    // ---- palette -------------------------------------------------------------------------
    private static final Color FLOOR = new Color(0.055f, 0.075f, 0.098f, 1f);   // #0E1319
    private static final Color FLOOR_EDGE = new Color(0.035f, 0.047f, 0.063f, 1f);
    private static final Color WALL_TOP = new Color(0.169f, 0.212f, 0.259f, 1f);   // #2B3642
    private static final Color WALL_FACE = new Color(0.106f, 0.137f, 0.173f, 1f);  // #1B232C
    private static final Color WALL_LINE = new Color(0.031f, 0.043f, 0.055f, 1f);
    private static final Color DROP = new Color(0.180f, 0.690f, 0.569f, 1f);
    private static final Color EXIT_SHUT = new Color(0.400f, 0.310f, 0.125f, 1f);
    private static final Color EXIT_OPEN = new Color(0.965f, 0.729f, 0.184f, 1f);
    private static final Color MATERIAL = new Color(0.949f, 0.741f, 0.239f, 1f);
    private static final Color BEAM = new Color(1f, 0.925f, 0.741f, 1f);
    private static final Color SELF_POLICE = new Color(0.435f, 0.718f, 0.910f, 1f);
    private static final Color SELF_THIEF = new Color(0.878f, 0.416f, 0.353f, 1f);
    private static final Color FOE = new Color(0.882f, 0.278f, 0.278f, 1f);

    private final ShapeRenderer shapes;
    private final OrthographicCamera worldCamera;
    private final OrthographicCamera uiCamera;
    private final FitViewport worldViewport;
    private final Viewport uiViewport;
    private final DataFiles data;
    private final float[] beamX = new float[BEAM_RAYS];
    private final float[] beamY = new float[BEAM_RAYS];
    private final Set<Long> wallCells = new HashSet<Long>();

    WorldRenderer(ShapeRenderer shapes, OrthographicCamera worldCamera, OrthographicCamera uiCamera,
                  FitViewport worldViewport, Viewport uiViewport, DataFiles data) {
        this.shapes = shapes;
        this.worldCamera = worldCamera;
        this.uiCamera = uiCamera;
        this.worldViewport = worldViewport;
        this.uiViewport = uiViewport;
        this.data = data;
        for (WallCell wall : data.map.walls) wallCells.add(key(wall.col, wall.row));
    }

    private static long key(int col, int row) {
        return ((long) col << 32) ^ (row & 0xffffffffL);
    }

    private boolean isWall(int col, int row) {
        return wallCells.contains(key(col, row));
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

        drawFrame();
        drawFloor();
        drawFloorDecals();
        drawLightPools(view);
        drawWalls();
        if (view != null) drawBeam(view);
        drawPoints(view);
        if (view != null) drawActors(view, interpolator);

        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // ---- environment ---------------------------------------------------------------------

    /** Outer dark band: the room reads as carved out of the void rather than floating. */
    private void drawFrame() {
        float w = data.map.width;
        float h = data.map.height;
        // A wide, slightly lighter skirt: the room is a lit floor inside a dim building, not a
        // rectangle floating in pure black (which is what the menu background looks like).
        shapes.setColor(0.045f, 0.055f, 0.068f, 1f);
        shapes.rect(-60f, -60f, w + 120f, h + 120f);
        shapes.setColor(FLOOR_EDGE);
        shapes.rect(-4f, -4f, w + 8f, h + 8f);
        shapes.setColor(WALL_LINE);
        shapes.rect(-0.9f, -0.9f, w + 1.8f, h + 1.8f);
        shapes.setColor(1f, 1f, 1f, 0.05f);
        shapes.rect(-0.55f, -0.55f, w + 1.1f, h + 1.1f);
        shapes.setColor(FLOOR);
        shapes.rect(0f, 0f, w, h);
    }

    /** One tile per world unit, each a hair different: a large floor is never one flat colour. */
    private void drawFloor() {
        for (int x = 0; x < data.map.width; x++) {
            for (int y = 0; y < data.map.height; y++) {
                if (isWall(x, y)) continue;
                int n = hash(x, y);
                float v = 0.965f + (n % 5) * 0.014f;
                shapes.setColor(FLOOR.r * v, FLOOR.g * v, FLOOR.b * v, 1f);
                shapes.rect(x, y, 1f, 1f);
                // A bevel, not a grid: light on the top-left edge, shadow on the bottom-right.
                shapes.setColor(1f, 1f, 1f, 0.035f);
                shapes.rect(x + 0.02f, y + 0.975f, 0.96f, 0.025f);
                shapes.rect(x + 0.02f, y + 0.02f, 0.025f, 0.955f);
                shapes.setColor(0f, 0f, 0f, 0.30f);
                shapes.rect(x + 0.02f, y, 0.96f, 0.022f);
                shapes.rect(x + 0.955f, y, 0.025f, 1f);
            }
        }
        // A darker ring just inside the walls, the way a painted room has a border band.
        shapes.setColor(0f, 0f, 0f, 0.20f);
        float w = data.map.width;
        float h = data.map.height;
        shapes.rect(0f, 0f, w, 0.35f);
        shapes.rect(0f, h - 0.35f, w, 0.35f);
        shapes.rect(0f, 0f, 0.35f, h);
        shapes.rect(w - 0.35f, 0f, 0.35f, h);
    }

    /** Debris and worn patches: sparse, deterministic, always under everything else. */
    private void drawFloorDecals() {
        for (int x = 0; x < data.map.width; x++) {
            for (int y = 0; y < data.map.height; y++) {
                if (isWall(x, y)) continue;
                int n = hash(x + 71, y + 13);
                if (n % 9 != 0) continue;
                float fx = x + 0.22f + (n % 5) * 0.12f;
                float fy = y + 0.22f + ((n >> 3) % 5) * 0.12f;
                shapes.setColor(1f, 1f, 1f, 0.028f);
                shapes.rect(fx, fy, 0.24f, 0.15f);
                shapes.setColor(0f, 0f, 0f, 0.28f);
                shapes.rect(fx + 0.05f, fy + 0.05f, 0.11f, 0.04f);
            }
        }
    }

    /** Warm pools around live exits, cold ones around drop points: the room needs light sources. */
    private void drawLightPools(PlayerView view) {
        boolean open = view != null && view.exitsOpen;
        for (MapPoint point : data.map.exits) {
            float x = (float) point.x;
            float y = (float) point.y;
            for (int ring = 2; ring >= 1; ring--) {
                shapes.setColor(EXIT_OPEN.r, EXIT_OPEN.g, EXIT_OPEN.b,
                        (open ? 0.055f : 0.016f) * ring);
                shapes.circle(x, y, 0.50f * ring, 32);
            }
        }
        for (MapPoint point : data.map.deliveries) {
            float x = (float) point.x;
            float y = (float) point.y;
            for (int ring = 2; ring >= 1; ring--) {
                shapes.setColor(DROP.r, DROP.g, DROP.b, 0.030f * ring);
                shapes.circle(x, y, 0.46f * ring, 32);
            }
        }
    }

    /**
     * Walls as blocks with a top face, a face, and an outline on the edges that touch floor.
     * That is what gives a flat plan its thickness.
     */
    private void drawWalls() {
        for (WallCell wall : data.map.walls) {
            float x = wall.col;
            float y = wall.row;
            shapes.setColor(WALL_FACE);
            shapes.rect(x, y, 1f, 1f);
            // The lit cap belongs to the top edge of a wall mass, not to every block, otherwise a
            // vertical wall reads as a stack of bars.
            if (!isWall(wall.col, wall.row + 1)) {
                shapes.setColor(WALL_TOP);
                shapes.rect(x, y + 0.66f, 1f, 0.34f);
                shapes.setColor(0f, 0f, 0f, 0.22f);
                shapes.rect(x, y + 0.60f, 1f, 0.06f);
            }
        }
        // Outlines after the fills, so neighbouring blocks do not paint over each other's edge.
        shapes.setColor(WALL_LINE);
        for (WallCell wall : data.map.walls) {
            float x = wall.col;
            float y = wall.row;
            if (!isWall(wall.col, wall.row + 1)) shapes.rect(x, y + 0.94f, 1f, 0.06f);
            if (!isWall(wall.col, wall.row - 1)) shapes.rect(x, y, 1f, 0.06f);
            if (!isWall(wall.col - 1, wall.row)) shapes.rect(x, y, 0.06f, 1f);
            if (!isWall(wall.col + 1, wall.row)) shapes.rect(x + 0.94f, y, 0.06f, 1f);
        }
        // A lit lip where a block's face meets the floor: cheap depth, reads as a chamfer.
        shapes.setColor(1f, 1f, 1f, 0.07f);
        for (WallCell wall : data.map.walls) {
            float x = wall.col;
            float y = wall.row;
            if (!isWall(wall.col, wall.row - 1)) shapes.rect(x, y, 1f, 0.05f);
        }
    }

    /** Stable per-cell noise; the floor must look the same every frame. */
    private static int hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return Math.abs(h ^ (h >> 16));
    }

    // ---- points and actors ---------------------------------------------------------------

    private void drawPoints(PlayerView view) {
        for (MapPoint point : data.map.deliveries) {
            float x = (float) point.x;
            float y = (float) point.y;
            shapes.setColor(DROP);
            shapes.circle(x, y, 0.32f, 28);
            shapes.setColor(1f, 1f, 1f, 0.85f);
            shapes.circle(x, y, 0.15f, 20);
        }
        boolean open = view != null && view.exitsOpen;
        for (MapPoint point : data.map.exits) {
            float x = (float) point.x;
            float y = (float) point.y;
            shapes.setColor(open ? EXIT_OPEN : EXIT_SHUT);
            shapes.rect(x - 0.34f, y - 0.34f, 0.68f, 0.68f);
            shapes.setColor(open ? Color.WHITE : new Color(0.62f, 0.50f, 0.24f, 1f));
            shapes.rect(x - 0.34f, y + 0.22f, 0.68f, 0.12f);
        }
    }

    private void drawActors(PlayerView view, ViewInterpolator interpolator) {
        if (view.thief != null) {
            for (GroundMaterial material : view.thief.groundMaterials) {
                float x = (float) material.x;
                float y = (float) material.y;
                shapes.setColor(1f, 1f, 1f, 0.16f);
                shapes.circle(x, y, 0.26f, 16);
                shapes.setColor(MATERIAL);
                shapes.circle(x, y, 0.17f, 16);
                shapes.setColor(1f, 1f, 1f, 0.75f);
                shapes.circle(x, y, 0.07f, 12);
            }
        }
        if (interpolator.opponentVisible()) {
            Role theirs = view.role == Role.THIEF ? Role.POLICE : Role.THIEF;
            drawBadge((float) interpolator.opponentX(), (float) interpolator.opponentY(), theirs,
                    Math.PI / 2.0, 0.22f);
            if (interpolator.opponentCarrying()) {
                // Contract §7: carrying is the only carry-related fact the police gets.
                shapes.setColor(MATERIAL);
                shapes.circle((float) interpolator.opponentX() + 0.30f,
                        (float) interpolator.opponentY() + 0.30f, 0.11f, 14);
            }
        }
        drawBadge((float) interpolator.selfX(), (float) interpolator.selfY(), view.role,
                view.self.headingRad, 0.20f);
    }

    /**
     * One actor, drawn as a small version of the lobby icon: the police is a cap with a lens, the
     * thief is a dark hood with a bright rim, a headband, and two scarf tails. Both point along
     * {@code heading} so movement direction is readable at a glance.
     */
    private void drawBadge(float x, float y, Role role, double heading, float halo) {
        float c = (float) Math.cos(heading);
        float s = (float) Math.sin(heading);
        float px = -s;
        float py = c;
        Color tint = role == Role.THIEF ? SELF_THIEF : SELF_POLICE;

        shapes.setColor(tint.r, tint.g, tint.b, halo * 0.35f);
        shapes.circle(x, y, 0.54f, 28);
        shapes.setColor(tint.r, tint.g, tint.b, halo * 0.75f);
        shapes.circle(x, y, 0.42f, 28);

        if (role == Role.POLICE) {
            // Same construction as the lobby icon: a white rim, the cap on top of it, then the
            // lamp as a small rounded protrusion pointing where the officer is looking.
            shapes.setColor(0.86f, 0.86f, 0.90f, 1f);
            shapes.circle(x, y, 0.31f, 28);
            shapes.setColor(SELF_POLICE);
            shapes.circle(x, y, 0.28f, 28);
            shapes.setColor(0.20f, 0.42f, 0.62f, 1f);
            shapes.circle(x, y, 0.19f, 24);
            shapes.setColor(0.88f, 0.95f, 1f, 1f);
            shapes.circle(x, y, 0.10f, 20);
            shapes.setColor(1f, 1f, 1f, 1f);
            shapes.circle(x, y, 0.045f, 14);

            float lx = x + c * 0.30f;
            float ly = y + s * 0.30f;
            float nx = x + c * 0.22f;
            float ny = y + s * 0.22f;
            shapes.setColor(0.30f, 0.33f, 0.38f, 1f);
            shapes.rectLine(nx, ny, lx, ly, 0.05f);
            shapes.circle(nx, ny, 0.025f, 12);
            shapes.setColor(0.70f, 0.74f, 0.79f, 1f);
            shapes.circle(lx, ly, 0.065f, 16);
            shapes.setColor(1f, 1f, 0.96f, 0.95f);
            shapes.circle(lx, ly, 0.038f, 12);
        } else {
            // Scarf tails stream out behind the hood.
            shapes.setColor(SELF_THIEF);
            shapes.triangle(x - c * 0.12f + px * 0.13f, y - s * 0.12f + py * 0.13f,
                    x - c * 0.12f - px * 0.06f, y - s * 0.12f - py * 0.06f,
                    x - c * 0.54f + px * 0.05f, y - s * 0.54f + py * 0.05f);
            shapes.triangle(x - c * 0.12f - px * 0.13f, y - s * 0.12f - py * 0.13f,
                    x - c * 0.12f + px * 0.06f, y - s * 0.12f + py * 0.06f,
                    x - c * 0.46f - px * 0.12f, y - s * 0.46f - py * 0.12f);
            // Hood with its bright rim.
            shapes.setColor(0.86f, 0.86f, 0.90f, 1f);
            shapes.circle(x, y, 0.31f, 28);
            shapes.setColor(0.10f, 0.10f, 0.12f, 1f);
            shapes.circle(x, y, 0.27f, 28);
            // Headband across the brow.
            shapes.setColor(SELF_THIEF);
            shapes.rectLine(x - px * 0.20f + c * 0.08f, y - py * 0.20f + s * 0.08f,
                    x + px * 0.20f + c * 0.08f, y + py * 0.20f + s * 0.08f, 0.07f);
        }
    }

    // ---- beam -----------------------------------------------------------------------------

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

        for (int pass = 0; pass < 2; pass++) {
            double shrink = 1.0 - pass * 0.45;
            double widthScale = 1.0;
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
            shapes.setColor(BEAM.r, BEAM.g, BEAM.b, pass == 0 ? 0.085f : 0.075f);
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

    // ---- minimap --------------------------------------------------------------------------

    /** Minimap. Ground materials appear only when the thief view carries them (contract §7). */
    void renderMinimap(PlayerView view, ViewInterpolator interpolator) {
        uiViewport.apply();
        shapes.setProjectionMatrix(uiCamera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        float w = data.map.width * MM_SCALE;
        float h = data.map.height * MM_SCALE;

        shapes.setColor(1f, 1f, 1f, 0.90f);
        shapes.rect(MM_X - 4f, MM_Y - 4f, w + 8f, h + 8f);
        shapes.setColor(FLOOR_EDGE);
        shapes.rect(MM_X - 2f, MM_Y - 2f, w + 4f, h + 4f);
        shapes.setColor(0.070f, 0.092f, 0.118f, 1f);
        shapes.rect(MM_X, MM_Y, w, h);

        shapes.setColor(WALL_FACE);
        for (WallCell wall : data.map.walls) {
            shapes.rect(MM_X + wall.col * MM_SCALE, MM_Y + wall.row * MM_SCALE, MM_SCALE, MM_SCALE);
        }
        shapes.setColor(WALL_TOP);
        for (WallCell wall : data.map.walls) {
            shapes.rect(MM_X + wall.col * MM_SCALE, MM_Y + wall.row * MM_SCALE + MM_SCALE * 0.6f,
                    MM_SCALE, MM_SCALE * 0.4f);
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
