package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.geometry.WorldGeometry;
import com.lightchase.data.DataFiles;

/**
 * The police flashlight cone.
 *
 * <p>It is the one piece of the world pass that is a small simulation of its own: rays cast against
 * the same wall geometry the server judges with, three passes to give the cone depth, and a rim so
 * it has a readable boundary on the floor. It lived at the end of {@code WorldRenderer}; it reads
 * better on its own, and the wall test it uses is the contract's, not a local copy.
 */
final class BeamLayer {

    /** Rays across the cone; enough for a smooth edge on a phone, cheap enough for 60 fps. */
    private static final int RAYS = 56;

    private static final Color BEAM = new Color(1f, 0.925f, 0.741f, 1f);

    private final ShapeRenderer shapes;
    private final DataFiles data;
    private final float[] rayX = new float[RAYS];
    private final float[] rayY = new float[RAYS];

    BeamLayer(ShapeRenderer shapes, DataFiles data) {
        this.shapes = shapes;
        this.data = data;
    }

    /**
     * Draws the cone for a police view.
     *
     * @param interpolator the smoothed poses; when present the cone comes out of the drawn body
     *                     rather than the raw snapshot, which used to make it step once per tick
     */
    void draw(PlayerView view, ViewInterpolator interpolator) {
        if (view == null || view.role != Role.POLICE) return;
        boolean live = interpolator != null && interpolator.hasCurrent();
        double cx = live ? interpolator.selfX() : view.self.x;
        double cy = live ? interpolator.selfY() : view.self.y;
        double heading = live ? interpolator.selfHeading() : view.self.headingRad;

        RuleConfig rules = data.rules;
        double half = Math.toRadians(rules.beamAngleDeg) / 2.0;
        double range = rules.beamRange;

        float[] innerX = new float[RAYS];
        float[] innerY = new float[RAYS];

        // Three passes: a wide soft glow, the body, then a tighter core.
        for (int pass = 0; pass < 3; pass++) {
            double shrink = 1.0 - pass * 0.30;
            for (int i = 0; i < RAYS; i++) {
                double angle = heading - half + (2.0 * half) * i / (RAYS - 1);
                double dx = Math.cos(angle);
                double dy = Math.sin(angle);
                double dist = unblockedDistance(cx, cy, dx, dy, range * shrink);
                rayX[i] = (float) (cx + dx * dist);
                rayY[i] = (float) (cy + dy * dist);
                if (pass == 0) {
                    innerX[i] = rayX[i];
                    innerY[i] = rayY[i];
                }
            }
            shapes.setColor(BEAM.r, BEAM.g, BEAM.b,
                    pass == 0 ? 0.045f : pass == 1 ? 0.075f : 0.060f);
            for (int i = 0; i < RAYS - 1; i++) {
                shapes.triangle((float) cx, (float) cy, rayX[i], rayY[i], rayX[i + 1], rayY[i + 1]);
            }
        }

        // Bright rim along the far edge so the cone has a readable boundary on the floor.
        shapes.setColor(BEAM.r, BEAM.g, BEAM.b, 0.55f);
        for (int i = 0; i < RAYS - 1; i++) {
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
        if (!WorldGeometry.segmentBlocked(data.map, x, y, x + dx * range, y + dy * range)) {
            return range;
        }
        double low = 0;
        double high = range;
        for (int step = 0; step < 5 + 7; step++) {
            double mid = (low + high) / 2.0;
            if (WorldGeometry.segmentBlocked(data.map, x, y, x + dx * mid, y + dy * mid)) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return low;
    }
}
