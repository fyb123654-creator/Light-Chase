package com.lightchase.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.lightchase.contracts.*;
import com.lightchase.contracts.geometry.WorldGeometry;
import com.lightchase.data.DataFiles;

/**
 * Draws the match world: map, wall-clipped police beam, actors, and the minimap.
 *
 * <p>Reads only role-filtered {@link PlayerView} data plus the shared map/rules. The beam boundary
 * is computed with {@link WorldGeometry#segmentBlocked} so the drawn cone and the server's judgement
 * come from one implementation.
 */
final class WorldRenderer {

    private static final int BEAM_RAYS = 40;
    private static final int BEAM_REFINE = 5;

    static final float MM_X = 1010f;
    static final float MM_Y = 462f;
    static final float MM_SCALE = 10f;

    private final ShapeRenderer shapes;
    private final OrthographicCamera worldCamera;
    private final OrthographicCamera uiCamera;
    private final FitViewport worldViewport;
    private final FitViewport uiViewport;
    private final DataFiles data;
    private final float[] beamX = new float[BEAM_RAYS];
    private final float[] beamY = new float[BEAM_RAYS];

    WorldRenderer(ShapeRenderer shapes, OrthographicCamera worldCamera, OrthographicCamera uiCamera,
                  FitViewport worldViewport, FitViewport uiViewport, DataFiles data) {
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

        shapes.setColor(0.08f, 0.12f, 0.18f, 1f);
        shapes.rect(0, 0, data.map.width, data.map.height);
        shapes.setColor(0.18f, 0.25f, 0.34f, 1f);
        for (WallCell wall : data.map.walls) shapes.rect(wall.col, wall.row, 1f, 1f);

        if (view != null) drawBeam(view);

        shapes.setColor(0.15f, 0.65f, 0.55f, 1f);
        for (MapPoint point : data.map.deliveries) {
            shapes.rect((float) point.x - 0.2f, (float) point.y - 0.2f, 0.4f, 0.4f);
        }
        shapes.setColor(0.85f, 0.56f, 0.17f, 1f);
        for (MapPoint point : data.map.exits) {
            shapes.rect((float) point.x - 0.3f, (float) point.y - 0.3f, 0.6f, 0.6f);
        }

        if (view != null) {
            if (view.thief != null) {
                shapes.setColor(Color.GOLD);
                for (GroundMaterial material : view.thief.groundMaterials) {
                    shapes.circle((float) material.x, (float) material.y, 0.15f, 16);
                }
            }
            if (interpolator.opponentVisible()) {
                shapes.setColor(new Color(0.9f, 0.25f, 0.25f, 0.18f));
                shapes.circle((float) interpolator.opponentX(), (float) interpolator.opponentY(), 0.40f, 24);
                shapes.setColor(Color.RED);
                shapes.circle((float) interpolator.opponentX(), (float) interpolator.opponentY(), 0.25f, 24);
            }
            shapes.setColor(view.role == Role.THIEF ? Color.CYAN : new Color(0.53f, 0.81f, 0.92f, 1f));
            shapes.circle((float) interpolator.selfX(), (float) interpolator.selfY(), 0.25f, 24);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawBeam(PlayerView view) {
        if (view.role != Role.POLICE) return;
        double cx = view.self.x;
        double cy = view.self.y;
        double heading = view.self.headingRad;
        double half = Math.toRadians(data.rules.beamAngleDeg) / 2.0;
        double range = data.rules.beamRange;

        for (int i = 0; i < BEAM_RAYS; i++) {
            double angle = heading - half + (2.0 * half) * i / (BEAM_RAYS - 1);
            double dx = Math.cos(angle);
            double dy = Math.sin(angle);
            double dist = unblockedDistance(cx, cy, dx, dy, range);
            beamX[i] = (float) (cx + dx * dist);
            beamY[i] = (float) (cy + dy * dist);
        }
        shapes.setColor(0.95f, 0.85f, 0.35f, 0.20f);
        for (int i = 0; i < BEAM_RAYS - 1; i++) {
            shapes.triangle((float) cx, (float) cy, beamX[i], beamY[i], beamX[i + 1], beamY[i + 1]);
        }
        shapes.setColor(0.95f, 0.85f, 0.35f, 0.55f);
        for (int i = 0; i < BEAM_RAYS - 1; i++) {
            shapes.rectLine(beamX[i], beamY[i], beamX[i + 1], beamY[i + 1], 0.03f);
        }
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

        shapes.setColor(0.03f, 0.05f, 0.08f, 0.92f);
        shapes.rect(MM_X - 6, MM_Y - 6, data.map.width * MM_SCALE + 12, data.map.height * MM_SCALE + 12);
        shapes.setColor(0.10f, 0.14f, 0.20f, 1f);
        shapes.rect(MM_X, MM_Y, data.map.width * MM_SCALE, data.map.height * MM_SCALE);
        shapes.setColor(0.26f, 0.34f, 0.44f, 1f);
        for (WallCell wall : data.map.walls) {
            shapes.rect(MM_X + wall.col * MM_SCALE, MM_Y + wall.row * MM_SCALE, MM_SCALE, MM_SCALE);
        }
        shapes.setColor(0.15f, 0.65f, 0.55f, 1f);
        for (MapPoint point : data.map.deliveries) {
            shapes.rect(MM_X + (float) point.x * MM_SCALE - 2, MM_Y + (float) point.y * MM_SCALE - 2, 4, 4);
        }
        shapes.setColor(view != null && view.exitsOpen ? new Color(0.95f, 0.75f, 0.25f, 1f)
                : new Color(0.45f, 0.38f, 0.20f, 1f));
        for (MapPoint point : data.map.exits) {
            shapes.rect(MM_X + (float) point.x * MM_SCALE - 3, MM_Y + (float) point.y * MM_SCALE - 3, 6, 6);
        }
        if (view != null && view.thief != null) {
            shapes.setColor(Color.GOLD);
            for (GroundMaterial material : view.thief.groundMaterials) {
                shapes.circle(MM_X + (float) material.x * MM_SCALE, MM_Y + (float) material.y * MM_SCALE, 2.5f, 10);
            }
        }
        if (interpolator.opponentVisible()) {
            shapes.setColor(Color.RED);
            shapes.circle(MM_X + (float) interpolator.opponentX() * MM_SCALE,
                    MM_Y + (float) interpolator.opponentY() * MM_SCALE, 3f, 10);
        }
        if (view != null) {
            shapes.setColor(view.role == Role.THIEF ? Color.CYAN : new Color(0.53f, 0.81f, 0.92f, 1f));
            shapes.circle(MM_X + (float) interpolator.selfX() * MM_SCALE,
                    MM_Y + (float) interpolator.selfY() * MM_SCALE, 3.5f, 12);
        }
        shapes.end();
    }
}
