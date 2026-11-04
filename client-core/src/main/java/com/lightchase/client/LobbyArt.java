package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * The thief character, drawn from above, in two situations: the lobby's role card and the actor in
 * the match. One body, two carry states.
 *
 * <p>Everything is expressed in "lobby units" (an icon radius of {@link #ICON_R}) and multiplied by
 * a scale {@code k}, so the identical recipe renders at 30 px in the lobby and at a fraction of a
 * tile in the world without a second copy of the shapes.
 */
final class LobbyArt {

    /** Outline radius of both characters, in lobby units. */
    static final float ICON_R = 30f;

    private static final Color SELF_POLICE_BLUE = new Color(0.435f, 0.718f, 0.910f, 1f);

    private final Color near = new Color();
    private final Color mid = new Color();
    private final Color far = new Color();

    /**
     * Police cap from above with a flashlight in front.
     *
     * @param turn   0 the cap faces the room, 1 it faces the words
     * @param clock  seconds, for the idle sweep
     * @param beamOn 0 the lamp is off, 1 it is fully on
     */
    void police(ShapeRenderer shapes, float cx, float cy, float turn, float clock, float beamOn) {
        float facing = UiAnim.smooth(turn);
        float base = UiAnim.lerp((float) Math.PI, 0f, facing);
        float wander = UiAnim.sway(clock, 0.9f) * 0.55f;
        float angle = base + wander;
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float body = ICON_R;
        float k = body / 0.31f;
        float lx = cx + c * (0.30f * k);
        float ly = cy + s * (0.30f * k);

        if (beamOn > 0.01f) {
            float reach = 120f * beamOn;
            float nearAlpha = 0.45f * beamOn;
            gradTri(shapes, lx, ly, nearAlpha,
                    lx + (float) Math.cos(angle - 0.26f) * reach,
                    ly + (float) Math.sin(angle - 0.26f) * reach, 0.02f * beamOn,
                    lx + (float) Math.cos(angle + 0.26f) * reach,
                    ly + (float) Math.sin(angle + 0.26f) * reach, 0.02f * beamOn);
        }

        shapes.setColor(0.86f, 0.86f, 0.90f, 1f);
        shapes.circle(cx, cy, body, 32);
        shapes.setColor(SELF_POLICE_BLUE);
        shapes.circle(cx, cy, 0.28f * k, 32);
        shapes.setColor(0.20f, 0.42f, 0.62f, 1f);
        shapes.circle(cx, cy, 0.19f * k, 28);
        shapes.setColor(0.88f, 0.95f, 1f, 1f);
        shapes.circle(cx, cy, 0.10f * k, 22);
        shapes.setColor(1f, 1f, 1f, 1f);
        shapes.circle(cx, cy, 0.045f * k, 16);

        float nx = cx + c * (0.22f * k);
        float ny = cy + s * (0.22f * k);
        shapes.setColor(0.30f, 0.33f, 0.38f, 1f);
        shapes.rectLine(nx, ny, lx, ly, 0.05f * k);
        shapes.circle(nx, ny, 0.025f * k, 14);
        shapes.setColor(0.70f, 0.74f, 0.79f, 1f);
        shapes.circle(lx, ly, 0.065f * k, 18);
        shapes.setColor(1f, 1f, 0.96f, 0.18f + 0.82f * beamOn);
        shapes.circle(lx, ly, 0.038f * k, 14);
    }

    // ---- the thief -----------------------------------------------------------------------

    /**
     * The lobby's role card: selecting the role moves the gem from the hands into the sack.
     *
     * @param selected 0 the role is not chosen, 1 it is
     */
    void thief(ShapeRenderer shapes, float cx, float cy, float selected, float clock) {
        float t = UiAnim.clamp01((UiAnim.smooth(selected) - 0.15f) / 0.65f);
        body(shapes, cx, cy, clock, 1f);
        float hx = cx - 34f;
        float hy = cy - 30f + UiAnim.sway(clock, 2.1f) * 2f;
        // gem travels from the hands into the bag as the role is chosen
        gem(shapes, hx, UiAnim.lerp(hy + 8f, hy + 16f, t), 1f - t, 1f);
        sack(shapes, hx, hy, t, 1f);
    }

    /**
     * The actor in a match. It shows exactly one of two carry states and nothing when empty-handed:
     * the gem while loot is in hand, the sack once every delivery is done and the exit is the goal.
     *
     * @param unit     the icon radius in world units, so the same recipe scales down
     * @param gemAlpha 0..1 weight of the gem in the hands
     * @param sackAlpha 0..1 weight of the sack at the hip
     */
    void thiefActor(ShapeRenderer shapes, float cx, float cy, float clock, float unit,
                    float gemAlpha, float sackAlpha) {
        float k = unit / ICON_R;
        body(shapes, cx, cy, clock, k);
        float hx = cx - 34f * k;
        float hy = cy - 30f * k + UiAnim.sway(clock, 2.1f) * 2f * k;
        float bob = UiAnim.sway(clock, 2.1f) * 2f * k;
        if (sackAlpha > 0.01f) sack(shapes, hx, hy, sackAlpha, k);
        if (gemAlpha > 0.01f) gem(shapes, hx, hy + 8f * k + bob, gemAlpha, k);
    }

    /** Scarf, hood, hair and headband - the part both states share. {@code k} scales lobby units. */
    private void body(ShapeRenderer shapes, float cx, float cy, float clock, float k) {
        float r = ICON_R * k;
        float bob = UiAnim.sway(clock, 2.1f) * 4f * k;
        float wave = UiAnim.sway(clock, 1.6f) * 8f * k;
        float y = cy + bob * 0.35f;

        // scarf ribbons trailing behind
        shapes.setColor(0.58f, 0.16f, 0.18f, 1f);
        shapes.triangle(cx + 14f * k, y + 10f * k, cx + 56f * k, y + 26f * k + wave,
                cx + 54f * k, y + 6f * k + wave);
        shapes.setColor(0.44f, 0.11f, 0.13f, 1f);
        shapes.triangle(cx + 14f * k, y + 2f * k, cx + 52f * k, y + 2f * k + wave,
                cx + 48f * k, y - 12f * k + wave);

        // hood: white rim, dark cloth
        shapes.setColor(Color.WHITE);
        shapes.circle(cx, y, r, 32);
        shapes.setColor(0.09f, 0.09f, 0.11f, 1f);
        shapes.circle(cx, y, r - 2.2f * k, 32);
        shapes.setColor(0.17f, 0.17f, 0.21f, 1f);
        shapes.circle(cx - 4f * k, y + 6f * k, r * 0.48f, 26);

        // hair across the face, and the headband tied at the side
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(cx - 13f * k, y + 1f * k, 26f * k, 2.6f * k);
        shapes.rect(cx - 11f * k, y - 4f * k, 22f * k, 2.0f * k);
        shapes.setColor(0.72f, 0.18f, 0.20f, 1f);
        shapes.rect(cx - 14f * k, y + 8f * k, 28f * k, 3.2f * k);
        shapes.circle(cx + 15f * k, y + 9.5f * k, 4.0f * k, 14);
        shapes.triangle(cx + 15f * k, y + 9f * k, cx + 30f * k, y + 15f * k + wave * 0.5f,
                cx + 29f * k, y + 5f * k + wave * 0.5f);
    }

    /** The hands holding the loot, plus the gem itself, faded by {@code alpha}. */
    private void gem(ShapeRenderer shapes, float gx, float gy, float alpha, float k) {
        if (alpha <= 0.01f) return;
        shapes.setColor(0.93f, 0.93f, 0.96f, alpha);
        shapes.circle(gx - 8f * k, gy - 8f * k, 5.5f * k, 16);
        shapes.circle(gx + 8f * k, gy - 8f * k, 5.5f * k, 16);

        float s2 = 10f * k;
        shapes.setColor(0.50f, 0.86f, 1f, alpha);
        shapes.triangle(gx, gy + s2, gx - s2, gy, gx, gy - s2);
        shapes.triangle(gx, gy + s2, gx + s2, gy, gx, gy - s2);
        shapes.setColor(0.86f, 0.98f, 1f, alpha * 0.85f);
        shapes.triangle(gx, gy + s2 * 0.55f, gx - s2 * 0.45f, gy, gx, gy - s2 * 0.55f);
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.triangle(gx, gy + s2 * 0.72f, gx - s2 * 0.30f, gy + s2 * 0.10f,
                gx + s2 * 0.10f, gy + s2 * 0.05f);
    }

    /** The round sack of loot at the hip, faded by {@code alpha}. */
    private void sack(ShapeRenderer shapes, float hx, float hy, float alpha, float k) {
        if (alpha <= 0.01f) return;
        shapes.setColor(0.93f, 0.93f, 0.96f, alpha);
        shapes.circle(hx - 2f * k, hy + 2f * k, 5.5f * k, 16);
        shapes.setColor(0.36f, 0.37f, 0.42f, alpha);
        shapes.circle(hx, hy - 12f * k, 13f * k, 22);
        shapes.setColor(0.22f, 0.22f, 0.26f, alpha);
        shapes.circle(hx - 4f * k, hy - 16f * k, 8f * k, 18);
        shapes.setColor(0.62f, 0.62f, 0.68f, alpha);
        shapes.circle(hx, hy - 2f * k, 4.5f * k, 14);
    }

    /** Triangle with a different alpha at each vertex: the whole gradient without stair steps. */
    private void gradTri(ShapeRenderer shapes, float x1, float y1, float a1,
                         float x2, float y2, float a2, float x3, float y3, float a3) {
        near.set(1f, 0.985f, 0.94f, a1);
        mid.set(1f, 0.985f, 0.94f, a2);
        far.set(1f, 0.985f, 0.94f, a3);
        shapes.triangle(x1, y1, x2, y2, x3, y3, near, mid, far);
    }
}
