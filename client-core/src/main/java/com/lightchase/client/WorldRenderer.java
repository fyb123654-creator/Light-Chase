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

    private static final int BEAM_REFINE = 5;

    // Minimap: small, rounded, parked in the bottom-right corner so it never covers the beam.
    private float mmScale;
    private float mmW;
    private float mmH;

    // ---- palette -------------------------------------------------------------------------
    private static final Color FLOOR = new Color(0.055f, 0.075f, 0.098f, 1f);   // #0E1319
    private static final Color FLOOR_EDGE = new Color(0.035f, 0.047f, 0.063f, 1f);
    private static final Color WALL_TOP = new Color(0.169f, 0.212f, 0.259f, 1f);   // #2B3642
    private static final Color WALL_FACE = new Color(0.106f, 0.137f, 0.173f, 1f);  // #1B232C
    private static final Color WALL_LINE = new Color(0.031f, 0.043f, 0.055f, 1f);
    private static final Color SELF_POLICE = new Color(0.435f, 0.718f, 0.910f, 1f);
    private static final Color SELF_THIEF = new Color(0.878f, 0.416f, 0.353f, 1f);
    private static final Color FOE = new Color(0.882f, 0.278f, 0.278f, 1f);

    private final ShapeRenderer shapes;
    private final OrthographicCamera worldCamera;
    private final OrthographicCamera uiCamera;
    private final FitViewport worldViewport;
    private final Viewport uiViewport;
    private final DataFiles data;
    /** The top-left panel; it owns its own UI pass. */
    private final Minimap minimap;
    private final BeamLayer beam;
    private final TaskArt tasks;
    private final Set<Long> wallCells = new HashSet<Long>();
    /** 0..1 "is this drop point the one the carried loot belongs to", eased per point. */
    private final LobbyArt actors = new LobbyArt();
    /** 0..1 weight of the gem in the thief's hands and of the sack at the hip. */
    private float gemCarry;
    /** Seconds since the renderer was created; the characters' idle motion runs on it. */
    private float clock;
    private float sackCarry;
    private final java.util.Map<String, float[]> dropOpen = new java.util.HashMap<>();
    /** 0..1 opening angle per exit, eased so the leaf swings instead of snapping. */
    private final java.util.Map<String, float[]> exitOpen = new java.util.HashMap<>();
    /** Loot that vanished from the ground this frame, kept for a beat so it fades out. */
    private final java.util.Map<String, float[]> lootFade = new java.util.HashMap<>();

    WorldRenderer(ShapeRenderer shapes, OrthographicCamera worldCamera, OrthographicCamera uiCamera,
                  FitViewport worldViewport, Viewport uiViewport, DataFiles data) {
        this.shapes = shapes;
        this.worldCamera = worldCamera;
        this.uiCamera = uiCamera;
        this.worldViewport = worldViewport;
        this.uiViewport = uiViewport;
        this.data = data;
        this.minimap = new Minimap(shapes, uiViewport, uiCamera, data, dropOpen, exitOpen,
                lootFade);
        this.beam = new BeamLayer(shapes, data);
        this.tasks = new TaskArt(shapes, data, dropOpen, exitOpen, lootFade);
        for (WallCell wall : data.map.walls) wallCells.add(key(wall.col, wall.row));
        mmScale = minimapScale(data.map.width, data.map.height);
        mmW = data.map.width * mmScale;
        mmH = data.map.height * mmScale;
    }

    /** Pixels per tile so any map fits the same corner box. */
    static float minimapScale(int mapWidth, int mapHeight) {
        return Minimap.scaleOf(mapWidth, mapHeight);
    }

    /** Rounded rectangle out of a cross plus four corner discs: ShapeRenderer has no radius. */

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
        drawFloorVignette();
        drawWalls();
        if (view != null) beam.draw(view, interpolator);
        float dt = Gdx.graphics.getDeltaTime();
        clock += dt;
        tasks.drawPoints(view, dt);
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

    /** Contact shadow under every wall and a dark falloff at the room's edges. */
    private void drawFloorVignette() {
        float mw = data.map.width;
        float mh = data.map.height;

        // Soft shadow cast by wall masses onto the floor around them.
        for (WallCell wall : data.map.walls) {
            float x = wall.col;
            float y = wall.row;
            for (int i = 0; i < 3; i++) {
                float a = 0.16f - i * 0.05f;
                float off = 0.10f + i * 0.10f;
                if (!isWall(wall.col, wall.row - 1)) {
                    shapes.setColor(0f, 0f, 0f, a);
                    shapes.rect(x, y - off, 1f, off);
                }
                if (!isWall(wall.col - 1, wall.row)) {
                    shapes.setColor(0f, 0f, 0f, a);
                    shapes.rect(x - off, y, off, 1f);
                }
                if (!isWall(wall.col + 1, wall.row)) {
                    shapes.setColor(0f, 0f, 0f, a);
                    shapes.rect(x + 1f, y, off, 1f);
                }
            }
        }

        // Room edges fall away so the play area reads as lit from the middle.
        float band = 3.2f;
        for (int i = 0; i < 6; i++) {
            float a = 0.05f;
            float d = i * band / 6f;
            shapes.setColor(0f, 0f, 0f, a);
            shapes.rect(0f, d, mw, band / 6f);
            shapes.rect(0f, mh - d - band / 6f, mw, band / 6f);
            shapes.rect(d, 0f, band / 6f, mh);
            shapes.rect(mw - d - band / 6f, 0f, band / 6f, mh);
        }
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
    /** Warm pools around live exits, cold ones around drop points: the room needs light sources. */
    private void drawLightPools(PlayerView view) {
        boolean open = view != null && view.exitsOpen;
        // The pool under a point follows the point's own rounded footprint: no circular spots.
        for (MapPoint point : data.map.exits) {
            float x = (float) point.x;
            float y = (float) point.y;
            for (int ring = 2; ring >= 1; ring--) {
                float half = 0.26f * ring;
                float a = (open ? 0.055f : 0.016f) * ring;
                ShapeKit.rounded(shapes, x - half, y - half, half * 2f, half * 2f, half * 0.34f,
                        new Color(TaskArt.EXIT_OPEN.r, TaskArt.EXIT_OPEN.g, TaskArt.EXIT_OPEN.b, a));
            }
        }
        for (MapPoint point : data.map.deliveries) {
            float x = (float) point.x;
            float y = (float) point.y;
            for (int ring = 2; ring >= 1; ring--) {
                float half = 0.31f * ring;
                float a = 0.030f * ring;
                ShapeKit.rounded(shapes, x - half, y - half, half * 2f, half * 2f, half * 0.40f,
                        new Color(TaskArt.DROP.r, TaskArt.DROP.g, TaskArt.DROP.b, a));
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
            ShapeKit.rounded(shapes, x, y, 1f, 1f, 0.18f, WALL_FACE);
            // Bridges keep neighbouring blocks merged, so only the outer silhouette is rounded.
            if (isWall(wall.col + 1, wall.row)) {
                shapes.rect(x + 1f - 0.18f, y + 0.02f, 0.36f, 0.96f);
            }
            if (isWall(wall.col, wall.row + 1)) {
                shapes.rect(x + 0.02f, y + 1f - 0.18f, 0.96f, 0.36f);
            }
            // The lit cap belongs to the top edge of a wall mass, not to every block, otherwise a
            // vertical wall reads as a stack of bars.
            if (!isWall(wall.col, wall.row + 1)) {
                shapes.setColor(WALL_TOP);
                shapes.rect(x, y + 0.66f, 1f, 0.34f);
                shapes.setColor(1f, 1f, 1f, 0.10f);
                shapes.rect(x, y + 0.94f, 1f, 0.06f);
                shapes.setColor(0f, 0f, 0f, 0.26f);
                shapes.rect(x, y + 0.60f, 1f, 0.06f);
            }
            // Ambient occlusion inside the block's own edges.
            shapes.setColor(0f, 0f, 0f, 0.10f);
            shapes.rect(x, y, 1f, 0.08f);
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

    /** The delivery point the carried material belongs to, or null when the thief carries nothing. */

    /** Loot silhouettes: phone, diamond, painting, cash, ring - assigned by map order. */


    /** Rounded slab: ShapeRenderer has no radius, so a cross plus four corner discs. */
    /**
     * A glow that follows a rounded rectangle instead of a circle: three nested shells, each a
     * little larger and weaker, so the falloff tracks the silhouette's own outline.
     */
    private void softGlow(float cx, float cy, float w, float h, float r, Color tint,
                          float strength) {
        for (int i = 3; i >= 1; i--) {
            float k = 1f + i * 0.07f;          // tight: the halo traces the outline, not a blob
            float a = strength * (0.26f / i);
            ShapeKit.rounded(shapes, cx - w * k / 2f, cy - h * k / 2f, w * k, h * k, r * k,
                    new Color(tint.r, tint.g, tint.b, a));
        }
    }

    /** Outline of a rounded rectangle, drawn as four edges plus four corner arcs. */


    /**
     * One piece of loot on the floor: a soft shadow, a rim, and a silhouette that says what it is.
     * The phone, the painting, the cash and the ring are all rounded; only the diamond keeps hard
     * edges, because hard is what a cut stone looks like.
     */

    /**
     * A deposit hatch: a bolted metal panel with a recessed chute, its lid shut or lifted, and a
     * slot that reads green when this is the destination for what you carry.
     */

    /**
     * A cellar hatch: a stone-rimmed pit with steps going down and a heavy lid that lifts on its
     * hinge as the exit opens.
     */


    private void drawActors(PlayerView view, ViewInterpolator interpolator) {
        float dt = Gdx.graphics.getDeltaTime();
        if (view.thief != null) {
            java.util.Set<String> present = new java.util.HashSet<String>();
            for (GroundMaterial material : view.thief.groundMaterials) {
                present.add(material.materialId);
                float[] f = lootFade.get(material.materialId);
                if (f == null) {
                    // alpha, x, y - three slots, allocated once per material
                    f = new float[]{1f, (float) material.x, (float) material.y};
                    lootFade.put(material.materialId, f);
                } else {
                    f[0] = UiAnim.approach(f[0], 1f, dt, 8f);
                    f[1] = (float) material.x;
                    f[2] = (float) material.y;
                }
                tasks.drawLoot((float) material.x, (float) material.y, tasks.lootType(material.materialId), 1f);
            }
            // Anything that left the ground keeps a ghost that fades out over ~0.3 s.
            java.util.Iterator<java.util.Map.Entry<String, float[]>> it = lootFade.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, float[]> e = it.next();
                float[] f = e.getValue();
                if (present.contains(e.getKey())) continue;
                f[0] = Math.max(0f, f[0] - dt / 0.30f);
                if (f[0] <= 0.01f) { it.remove(); continue; }
                tasks.drawLoot(f[1], f[2], tasks.lootType(e.getKey()), f[0]);
            }
        }
        // The thief wears the lobby character in both modes; the police keeps the badge.
        boolean exitsOpen = view.exitsOpen;
        boolean selfThief = view.role == Role.THIEF;
        boolean selfCarrying = view.thief != null && view.thief.carriedMaterialId != null;
        gemCarry = UiAnim.approach(gemCarry, selfCarrying ? 1f : 0f, dt, 7f);
        sackCarry = UiAnim.approach(sackCarry, exitsOpen && !selfCarrying ? 1f : 0f, dt, 5f);

        if (interpolator.opponentVisible()) {
            Role theirs = selfThief ? Role.POLICE : Role.THIEF;
            float ox = (float) interpolator.opponentX();
            float oy = (float) interpolator.opponentY();
            if (theirs == Role.THIEF) {
                // The visible opponent's carry is the one fact §7 grants: gem or nothing.
                float theirGem = UiAnim.approach(1f, 1f, 0f, 1f);
                actors.thiefActor(shapes, ox, oy, clock, 0.30f,
                        interpolator.opponentCarrying() ? 1f : 0f, exitsOpen ? 1f : 0f);
            } else {
                drawBadge(ox, oy, theirs, Math.PI / 2.0, 0.22f);
            }
        }
        if (selfThief) {
            actors.thiefActor(shapes, (float) interpolator.selfX(), (float) interpolator.selfY(),
                    clock, 0.30f, gemCarry, sackCarry);
        } else {
            drawBadge((float) interpolator.selfX(), (float) interpolator.selfY(), view.role,
                    interpolator.selfHeading(), 0.20f);
        }
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

    /**
     * Longest wall-free distance along one ray, using {@link WorldGeometry#segmentBlocked} so the
     * drawn cone and the server's judgement share a single implementation.
     */

    // ---- minimap --------------------------------------------------------------------------

    /** Minimap. Ground materials appear only when the thief view carries them (contract §7). */

    /** Delegates to {@link Minimap}; kept so the app keeps talking to one renderer. */
    void renderMinimap(PlayerView view, ViewInterpolator interpolator) {
        minimap.render(view, interpolator);
    }
}
