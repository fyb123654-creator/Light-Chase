package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;

/**
 * Blackout theme: near-black canvas, one accent, no decoration that is not information.
 *
 * <p>Every colour and every lobby coordinate lives here so the hit boxes in
 * {@code LightChaseApp.tap()} and the shapes drawn in {@code drawUi()} cannot drift apart.
 * Coordinates are given the way the mockup reads them - as a distance from the top edge - and
 * converted by {@link #yFromTop}.
 */
final class UiTheme {

    static final float W = 1280f;
    static final float H = 720f;

    // ---- palette -------------------------------------------------------------------------
    static final Color BG        = new Color(0.039f, 0.039f, 0.039f, 1f);   // #0A0A0A
    static final Color TEXT      = new Color(0.960f, 0.960f, 0.960f, 1f);
    static final Color TEXT_SOFT = new Color(0.960f, 0.960f, 0.960f, 1f);   // grey text is gone
    static final Color DIM       = new Color(0.960f, 0.960f, 0.960f, 1f);
    static final Color FAINT     = new Color(0.960f, 0.960f, 0.960f, 1f);
    static final Color LINE      = new Color(0.260f, 0.260f, 0.260f, 1f);
    static final Color ACCENT    = new Color(0.760f, 0.380f, 0.330f, 1f);   // #C26154, softer
    static final Color ON_LIGHT  = new Color(0.039f, 0.039f, 0.039f, 1f);

    // ---- typography sizes ----------------------------------------------------------------
    static final int SIZE_TITLE = 58;
    static final int SIZE_ROLE  = 40;
    static final int SIZE_ROOM  = 30;
    static final int SIZE_BODY  = 19;
    static final int SIZE_CTA   = 32;
    static final int SIZE_HUGE  = 110;

    // ---- lobby geometry (distance from the top edge, as in the mockup) -------------------
    static final float MARGIN = 72f;

    static final float TITLE_TOP = 64f;
    static final float KICKER_TOP = 148f;
    static final float RULE_TOP = 176f;

    /** First row of the three columns: the role list, the room facts, the players. */
    static final float COL1_TOP = 240f;
    /** Second row of each column. */
    static final float COL2_TOP = 400f;

    static final float INFO_X = 900f;

    static final float RULE2_TOP = 528f;
    static final float CTA_TOP = 560f;
    static final float CTA_H = 64f;
    static final float CTA_W = 228f;
    static final float FOOTER_TOP = 652f;

    /** Rule thickness. A single unit vanishes on a phone; five matches the mockup weight. */
    static final float RULE_H = 5f;

    /** How long a press keeps its highlight; long enough to see, short enough not to lag. */
    static final long PRESS_MS = 140;

    /** Width available to a value in the info column before it would touch the right margin. */
    static final float INFO_W = W - MARGIN - INFO_X;

    private UiTheme() { }

    /** Converts a distance-from-top plus a height into the y of a bottom-left rect origin. */
    static float yFromTop(float top, float height) {
        return H - top - height;
    }

    /** Baseline for text whose cap top sits {@code top} pixels below the screen top. */
    static float baselineFromTop(float top, float capHeight) {
        return H - top - capHeight;
    }
}
