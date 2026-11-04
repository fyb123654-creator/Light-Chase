package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.lightchase.contracts.InteractionType;
import com.lightchase.contracts.PlayerView;

/**
 * The single action button of the match screen, and the whole state machine behind it.
 *
 * <p>It used to live inside {@code LightChaseApp} as eighteen loose fields and five drawing methods
 * mixed into a 2600-line class; every new behaviour meant touching all of them. Everything that
 * decides what the button looks like is now in this one file, driven by a single {@link State}:
 *
 * <ul>
 *   <li>{@code HOLD} - empty hand, disabled until something can be picked up;</li>
 *   <li>{@code DROP} - carrying loot;</li>
 *   <li>{@code DELIVER} - standing on the drop point that matches the carried loot;</li>
 *   <li>{@code ESCAPE} - standing on an open exit.</li>
 * </ul>
 *
 * <p>Two animations layer on top of the state: the long-press progress bar (fills and winds back at
 * one speed) and the success tick (a two-stroke mark, half a second hold). While the tick plays the
 * button is hidden by it, so the state settles underneath and nothing can flash afterwards.
 */
final class ActionButton {

    /** What the button currently represents. */
    private enum State { HOLD, DROP, DELIVER, ESCAPE }

    // ---- eased weights: they add up, and each one owns one appearance ---------------------
    private float holdEnabled;      // 0 disabled, 1 something can be picked up
    private float holdPress;        // 0 lifted, 1 pressed (the sink)
    private float holdFill;         // 0..1 long-press progress
    private float fillAlpha;        // alpha of the green fill; fades as a whole
    private float morph;            // 0 HOLD look, 1 carrying look
    private float deliverFade;      // 0 DROP, 1 DELIVER
    private float escapeFade;       // 0 anything, 1 ESCAPE
    private float iconReveal;       // 0 icon hidden (tick playing), 1 visible
    private float successLift;      // 0..1 the gem rising for the tick
    private float liftCarry;        // weight of the carrying offset on the gem's height
    private float captionDoing;     // 0..1 "the bar is running"
    private float captionTick;
    /** 0..1 the escape is finished: ESCAPE and Wait cross-fade out, WIN ! fades in. */
    private float winFade;      // 0..1 "the tick is playing" (captions fade out)

    // ---- latches -----------------------------------------------------------------------
    private boolean fillComplete;   // a bar finished; its alpha is fading out
    private boolean showDropLook;   // the carrying look, held until the finger lifts
    private boolean escapeDone;     // the escape finished: keep the look
    private boolean lastCarrying;
    private int lastDelivered;
    private float checkT = -1f;     // seconds into the success tick, negative = not playing

    /** Back to a freshly spawned button: called on every new match and every disconnect. */
    void reset() {
        holdEnabled = 0f;
        holdPress = 0f;
        holdFill = 0f;
        fillAlpha = 1f;
        fillComplete = false;
        morph = 0f;
        deliverFade = 0f;
        escapeFade = 0f;
        iconReveal = 0f;
        successLift = 0f;
        liftCarry = 1f;
        captionDoing = 0f;
        captionTick = 0f;
        winFade = 0f;
        showDropLook = false;
        escapeDone = false;
        lastCarrying = false;
        lastDelivered = 0;
        checkT = -1f;
    }

    /** True while the success tick owns the button (nothing else may move). */
    boolean ticking() {
        return checkT >= 0f;
    }

    /**
     * One frame of the state machine.
     *
     * @param view         the role-filtered snapshot, or null before the first one
     * @param pressed      the finger is down on the button
     * @param interactTicks how many ticks a long press needs, from the rules
     * @param tickRate     ticks per second, from the rules
     */
    void update(float dt, PlayerView view, boolean pressed, int interactTicks, int tickRate) {
        boolean carried = view != null && view.thief != null
                && view.thief.carriedMaterialId != null;
        boolean hasTarget = view != null && view.thief != null
                && view.thief.interaction.candidate != null;
        InteractionType targetType = hasTarget ? view.thief.interaction.candidate.type : null;
        boolean canPickUp = view != null && view.thief != null && !carried && hasTarget
                && targetType == InteractionType.PICKUP;
        boolean canDeliver = carried && hasTarget && targetType == InteractionType.DELIVER;
        boolean canEscape = hasTarget && targetType == InteractionType.ESCAPE;
        float fillPerSecond = tickRate / (float) Math.max(1, interactTicks);

        // ---- success detection ---------------------------------------------------------
        int deliveredNow = view == null ? 0 : view.deliveredCount;
        boolean justPicked = carried && !lastCarrying;
        boolean justDelivered = deliveredNow > lastDelivered;
        boolean escaping = canEscape && view.thief.interaction.progressTicks >= interactTicks - 1;
        if (escaping) {
            justDelivered = true;        // the escape ends the match the same way a delivery counts
            escapeDone = true;
        }
        lastCarrying = carried;
        lastDelivered = deliveredNow;
        if ((justPicked || justDelivered) && checkT < 0f) {
            holdFill = 1f;
            fillAlpha = 1f;
            fillComplete = true;
            checkT = 0f;
        }

        // ---- eased values ---------------------------------------------------------------
        boolean held = pressed;
        iconReveal = UiAnim.approach(iconReveal, checkT >= 0f ? 0f : 1f, dt, 4.5f);
        successLift = UiAnim.approach(successLift, checkT >= 0f ? 1f : 0f, dt, 7f);
        liftCarry = UiAnim.approach(liftCarry, checkT >= 0f ? 0f : 1f, dt, 5f);
        boolean barRunning = held && hasTarget
                && (holdFill > 0.02f || progress(view, interactTicks) > 0.01f);
        captionDoing = UiAnim.approach(captionDoing, barRunning ? 1f : 0f, dt, 9f);
        captionTick = UiAnim.approach(captionTick, checkT >= 0f ? 1f : 0f, dt, 9f);
        winFade = UiAnim.approach(winFade, escapeDone ? 1f : 0f, dt, 6f);
        holdEnabled = UiAnim.approach(holdEnabled, canPickUp ? 1f : 0f, dt, 5f);
        holdPress = UiAnim.approach(holdPress, held ? 1f : 0f, dt, 16f);

        // ---- tick ------------------------------------------------------------------------
        if (checkT >= 0f) {
            checkT += dt;
            if (checkT > 1.25f) checkT = -1f;
        } else if (fillComplete) {
            fillAlpha = Math.max(0f, fillAlpha - dt / 0.35f);
            if (fillAlpha <= 0.01f) {
                fillAlpha = 0f;
                fillComplete = false;
                holdFill = 0f;
            }
        } else if (held && hasTarget) {
            // Local smoothing, but the server's own progress is the floor.
            holdFill = Math.max(Math.min(1f, holdFill + dt * fillPerSecond),
                    progress(view, interactTicks));
            fillAlpha = 1f;
        } else if (holdFill > 0f) {
            // Winding back is only the level dropping: the fill keeps full opacity all the way.
            holdFill = Math.max(0f, holdFill - dt * fillPerSecond);
        }

        // ---- the look ---------------------------------------------------------------------
        if (carried) {
            showDropLook = true;
        } else if (!held) {
            showDropLook = false;
        }
        // Under the tick the next state is resolved instantly: when the tick ends there is nothing
        // left to travel, so no intermediate look can flash.
        if (checkT >= 0f) {
            morph = showDropLook ? 1f : 0f;
            deliverFade = canDeliver ? 1f : 0f;
            escapeFade = canEscape || escapeDone ? 1f : 0f;
        } else {
            if (!canEscape && escapeDone) {
                escapeFade = 1f;
            } else {
                escapeFade = UiAnim.approach(escapeFade, canEscape ? 1f : 0f, dt, 6f);
            }
            deliverFade = UiAnim.approach(deliverFade, canDeliver ? 1f : 0f, dt, 6f);
            morph = UiAnim.approach(morph, showDropLook ? 1f : 0f, dt, 3.2f);
        }
    }

    /** Server-side long-press progress, 0..1. */
    private static float progress(PlayerView view, int interactTicks) {
        if (view == null || view.thief == null) return 0f;
        return Math.min(1f, view.thief.interaction.progressTicks / (float) Math.max(1, interactTicks));
    }

    // ================================================================= draw =================

    /** The disc, its icons and the success mark. Called inside the shape pass. */
    void drawShapes(ShapeRenderer shapes, float cx, float cy, float radius) {
        float r = radius * (1f - 0.16f * holdPress);

        Color bed = new Color(0.105f, 0.115f, 0.125f, 1f);          // disabled
        bed.lerp(new Color(0.780f, 0.800f, 0.820f, 1f), holdEnabled); // pickable
        bed.lerp(new Color(0.860f, 0.640f, 0.055f, 1f), morph);       // carrying
        bed.lerp(Color.WHITE, deliverFade * (1f - escapeFade));       // on the drop point
        bed.lerp(new Color(0.78f, 0.20f, 0.18f, 1f), escapeFade);     // on an open exit

        shapes.setColor(1f, 1f, 1f, 0.85f);
        shapes.circle(cx, cy, r, 48);
        shapes.setColor(bed);
        shapes.circle(cx, cy, r - 7.5f, 48);

        ShapeKit.bottomUpFill(shapes, cx, cy, r - 7.5f, holdFill,
                new Color(0.36f, 0.86f, 0.55f, 0.95f * fillAlpha));

        float tickAlpha = 0f;
        float p1 = 0f;
        float p2 = 0f;
        if (checkT >= 0.18f) {
            p1 = Math.min(1f, (checkT - 0.18f) / 0.15f);
            p2 = Math.min(1f, Math.max(0f, (checkT - 0.33f) / 0.18f));
            tickAlpha = checkT > 1.05f ? Math.max(0f, (1.25f - checkT) / 0.2f) : 1f;
        }

        float lift = 10f * morph * liftCarry + 6f * successLift;
        // The gem fades out twice as fast as the exit frame fades in, so it never pokes through.
        float gemWeight = clamp01(1f - escapeFade * 2f);
        if (gemWeight > 0.02f) gem(shapes, cx, cy + 8f + lift, 21f, gemWeight);

        // iconReveal is what fades during the tick: gating on it (and not on checkT) means the
        // icons leave and come back gradually instead of being cut.
        if (iconReveal > 0.02f && morph > 0.02f) {
            float a = iconReveal * morph * (1f - deliverFade);
            if (a > 0.02f) dropArrow(shapes, cx, cy, a);
        }
        if (tickAlpha > 0.02f) tick(shapes, cx, cy - 20f, p1, p2, tickAlpha);
        if (iconReveal > 0.02f && escapeFade > 0.02f) {
            escapePortal(shapes, cx, cy, bed, iconReveal * escapeFade);
        }
        if (iconReveal > 0.02f && deliverFade > 0.02f) {
            deliverTray(shapes, cx, cy, iconReveal * deliverFade);
        }
    }

    /** The six caption weights of one frame. Pure: no GL, so it can be asserted directly. */
    static final class Labels {
        public final float hold;
        public final float drop;
        public final float deliver;
        public final float escape;
        public final float wait;
        public final float win;

        Labels(float hold, float drop, float deliver, float escape, float wait, float win) {
            this.hold = hold;
            this.drop = drop;
            this.deliver = deliver;
            this.escape = escape;
            this.wait = wait;
            this.win = win;
        }
    }

    /**
     * How strongly each word reads this frame.
     *
     * <p>The four modes are a partition - they always sum to one - so exactly one word shows and a
     * decay in one is a rise in another, never a flash of a third. The two "running" words layer on
     * top and take over while they apply; WIN ! cross-fades with ESCAPE once the escape lands.
     */
    Labels labels() {
        float vis = 1f - captionTick;
        float idle = 1f - captionDoing;
        float wEscape = escapeFade;
        float wDeliver = Math.max(0f, deliverFade - wEscape);
        float wDrop = Math.max(0f, morph * clamp01((0.30f - deliverFade) / 0.30f) - wEscape - wDeliver);
        float wHold = Math.max(0f, 1f - wEscape - wDeliver - wDrop);
        return new Labels(wHold * idle * vis, wDrop * idle * vis, wDeliver * idle * vis,
                wEscape * idle * (1f - winFade) * vis, captionDoing * (1f - winFade) * vis,
                wEscape * winFade);
    }

    /** The caption under the button. One word at a time, cross-faded. */
    void drawCaption(Batch batch, BitmapFont font, float cx, float capTop, Color ink) {
        Labels l = labels();
        label(batch, font, "HOLD", cx, capTop, l.hold, ink);
        label(batch, font, "DROP", cx, capTop, l.drop, ink);
        label(batch, font, "DELIVER", cx, capTop, l.deliver, ink);
        label(batch, font, "ESCAPE", cx, capTop, l.escape, ink);
        label(batch, font, "Wait...", cx, capTop, l.wait, ink);
        label(batch, font, "WIN !", cx, capTop, l.win, ink);
    }

    private static void label(Batch batch, BitmapFont font, String text, float cx, float top,
                              float alpha, Color ink) {
        if (alpha <= 0.01f || text.isEmpty()) return;
        float w = UiDraw.width(font, text, 1.5f);
        UiDraw.text(batch, font, text, cx - w / 2f, top,
                new Color(ink.r, ink.g, ink.b, alpha), 1.5f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ---- icon parts ------------------------------------------------------------------------

    /** Faceted blue gem: crown, girdle, pavilion and a glint. */
    private static void gem(ShapeRenderer shapes, float cx, float cy, float half, float alpha) {
        shapes.setColor(1f, 1f, 1f, alpha * 0.85f);
        shapes.triangle(cx, cy + half * 1.05f, cx - half * 0.88f, cy + half * 0.16f,
                cx + half * 0.88f, cy + half * 0.16f);
        shapes.triangle(cx - half * 0.88f, cy + half * 0.16f, cx + half * 0.88f, cy + half * 0.16f,
                cx, cy - half * 1.05f);
        float t = 0.86f;
        shapes.setColor(0.42f, 0.74f, 1f, alpha);
        shapes.triangle(cx, cy + half * t, cx - half * 0.78f, cy + half * 0.20f,
                cx + half * 0.78f, cy + half * 0.20f);
        shapes.setColor(0.62f, 0.86f, 1f, alpha);
        shapes.triangle(cx, cy + half * t, cx - half * 0.30f, cy + half * 0.30f,
                cx + half * 0.16f, cy + half * 0.30f);
        shapes.setColor(0.18f, 0.44f, 0.86f, alpha);
        shapes.triangle(cx - half * 0.78f, cy + half * 0.16f, cx + half * 0.78f, cy + half * 0.16f,
                cx, cy - half * 0.96f);
        shapes.setColor(0.12f, 0.30f, 0.68f, alpha);
        shapes.triangle(cx - half * 0.78f, cy + half * 0.16f, cx - half * 0.10f, cy + half * 0.16f,
                cx, cy - half * 0.96f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.55f);
        shapes.rect(cx - half * 0.80f, cy + half * 0.14f, half * 1.60f, half * 0.07f);
        shapes.setColor(1f, 1f, 1f, alpha * 0.9f);
        shapes.triangle(cx - half * 0.46f, cy + half * 0.72f, cx - half * 0.20f, cy + half * 0.34f,
                cx - half * 0.06f, cy + half * 0.52f);
    }

    /** Arrow under the gem, with a clear gap. */
    private static void dropArrow(ShapeRenderer shapes, float cx, float cy, float alpha) {
        shapes.setColor(0.10f, 0.10f, 0.11f, alpha);
        float ay = cy - 28f;
        shapes.rect(cx - 3.5f, ay + 7f, 7f, 12f);
        shapes.triangle(cx - 12f, ay + 7f, cx + 12f, ay + 7f, cx, ay - 9f);
    }

    /** A tray catching the gem. */
    private static void deliverTray(ShapeRenderer shapes, float cx, float cy, float alpha) {
        shapes.setColor(0.12f, 0.34f, 0.62f, alpha);
        float ty = cy - 34f;
        shapes.rect(cx - 20f, ty, 40f, 6f);
        shapes.rect(cx - 20f, ty, 6f, 16f);
        shapes.rect(cx + 14f, ty, 6f, 16f);
        shapes.setColor(0.12f, 0.34f, 0.62f, alpha * 0.9f);
        shapes.triangle(cx - 26f, ty + 22f, cx - 18f, ty + 22f, cx - 22f, ty + 34f);
        shapes.triangle(cx + 18f, ty + 22f, cx + 26f, ty + 22f, cx + 22f, ty + 34f);
    }

    /** Portal frame with an arrow rising out of it; the hole is carved with the bed colour. */
    private static void escapePortal(ShapeRenderer shapes, float cx, float cy, Color bed,
                                     float alpha) {
        Color frame = new Color(0.96f, 0.96f, 0.97f, alpha);
        ShapeKit.rounded(shapes, cx - 19f, cy - 26f, 38f, 46f, 7f, frame);
        ShapeKit.rounded(shapes, cx - 14f, cy - 21f, 28f, 38f, 4f,
                new Color(bed.r, bed.g, bed.b, 1f));
        shapes.setColor(frame);
        shapes.rect(cx - 3.5f, cy - 12f, 7f, 12f);
        shapes.triangle(cx - 11f, cy, cx + 11f, cy, cx, cy + 14f);
    }

    /** The success mark: white outline, blue core, drawn one stroke at a time. */
    private static void tick(ShapeRenderer shapes, float cx, float cy, float p1, float p2,
                             float alpha) {
        stroke(shapes, cx - 15f, cy + 1f, cx - 5f, cy - 10f, p1, alpha);
        if (p2 > 0f) stroke(shapes, cx - 5f, cy - 10f, cx + 16f, cy + 11f, p2, alpha);
    }

    private static void stroke(ShapeRenderer shapes, float x1, float y1, float x2, float y2,
                               float progress, float alpha) {
        if (progress <= 0f) return;
        float ex = x1 + (x2 - x1) * progress;
        float ey = y1 + (y2 - y1) * progress;
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.rectLine(x1, y1, ex, ey, 9f);
        shapes.setColor(0.30f, 0.64f, 1f, alpha);
        shapes.rectLine(x1, y1, ex, ey, 4.5f);
    }
}
