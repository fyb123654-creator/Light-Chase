package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.lightchase.contracts.Role;

/**
 * The lobby page: two role cards, the four fact chips, the READY action and the footer.
 *
 * <p>This is the last page to leave the application class. It owns exactly one thing of its own -
 * the per-chip cross-fade, because that is a property of the chips and not of the room - and takes
 * everything else through {@link Frame}, which the app refills every frame.
 */
final class LobbyView {

    private static final int CHIPS = 4;

    private final String[] shown = new String[CHIPS];
    private final String[] previous = new String[CHIPS];
    private final float[] fade = new float[CHIPS];
    private final Color scratch = new Color();
    private final Color scratchLit = new Color();

    private static final Color ROLE_POLICE = new Color(0.16f, 0.32f, 0.52f, 1f);
    private static final Color ROLE_THIEF = new Color(0.62f, 0.22f, 0.20f, 1f);
    private static final Color LABEL_GREY = new Color(0.22f, 0.22f, 0.22f, 1f);
    private static final Color HINT_GREY = new Color(0.42f, 0.42f, 0.42f, 1f);
    private static final Color MUTED = new Color(0.62f, 0.62f, 0.62f, 1f);

    /** Advances the chip cross-fades. Called once per frame by the app. */
    void step(float dt) {
        for (int i = 0; i < CHIPS; i++) {
            fade[i] = Math.min(1f, fade[i] + dt * 3.4f);
        }
    }

    /** Everything the page needs for one frame; the app owns all of it. */
    static final class Frame {
        String roomId = "";
        String mapId = "";
        String youLabel = "-";
        String opponentLabel = "-";
        float selectPolice;
        float selectThief;
        float beamOn;
        float roleNoticeT;
        Role roleNoticeRole;
        String roleNoticeText = "";
        boolean busy;
        float reveal;
        float pressLit;
        String cta = "READY";
        boolean ctaMuted;
        float leaveHot;
        TextFade lobbyStatus;
        String modeLabel = "";
        float bottomShift;

        // layout, resolved by the app
        float margin;
        float worldWidth;
        float kickerTop;
        float roleCx;
        float role1Top;
        float role2Top;
        float iconDx;
        float iconR;
        float ctaTop;
        float ctaW;
        float ctaH;
        float boxH;
        float gridGap;
        float leaveCentreX;
        float footerTop;
    }

    void draw(PageContext ctx, Frame f) {
        float w = f.worldWidth;
        float m = f.margin;
        float bs = f.bottomShift;

        UiDraw.text(ctx.batch, ctx.fonts.display, "LIGHT \u00b7 CHASE", m, 64f, UiTheme.TEXT, 1.5f);
        UiDraw.text(ctx.batch, ctx.fonts.monoBig, "LOBBY", m + 2f, f.kickerTop, UiTheme.ACCENT, 2.5f);

        float modeTop = 64f + (UiDraw.capHeight(ctx.fonts.display)
                - UiDraw.capHeight(ctx.fonts.mono)) / 2f;
        UiDraw.text(ctx.batch, ctx.fonts.mono, f.modeLabel,
                w - m - UiDraw.width(ctx.fonts.mono, f.modeLabel, 1f), modeTop, MUTED, 1f);

        // ---- role cards ------------------------------------------------------------------
        roleCard(ctx, f, f.role1Top, "POLICE", "flashlight / mark three times", f.selectPolice,
                f.beamOn);
        roleCard(ctx, f, f.role2Top, "THIEF", "three drops / then escape", f.selectThief, 0f);

        if (f.roleNoticeT > 0f && f.roleNoticeRole != null) {
            // Fades in for the first 0.2s, holds, fades out over the last 0.4s.
            float a = f.roleNoticeT > 2.4f ? (2.6f - f.roleNoticeT) / 0.2f
                    : f.roleNoticeT < 0.4f ? f.roleNoticeT / 0.4f : 1f;
            float rowTop = f.roleNoticeRole == Role.POLICE ? f.role1Top : f.role2Top;
            scratch.set(UiTheme.ACCENT);
            scratch.a = Math.max(0f, Math.min(1f, a));
            // Centred between the role icon and the first white chip.
            float iconRight = f.roleCx + f.iconDx + f.iconR;
            float gapCentre = (iconRight + w * 0.54f) / 2f;
            float labelW = UiDraw.width(ctx.fonts.mono, f.roleNoticeText, 1.5f);
            UiDraw.text(ctx.batch, ctx.fonts.mono, f.roleNoticeText, gapCentre - labelW / 2f,
                    rowTop + 38f - UiDraw.capHeight(ctx.fonts.mono) / 2f, scratch, 1.5f);
        }

        // ---- fact chips ------------------------------------------------------------------
        float x0 = w * 0.54f;
        float boxW = (w - m - x0 - f.gridGap) / 2f;
        box(ctx, f, 0, x0, f.role1Top, boxW, "ROOM", f.roomId);
        box(ctx, f, 1, x0 + boxW + f.gridGap, f.role1Top, boxW, "MAP", f.mapId);
        box(ctx, f, 2, x0, f.role2Top, boxW, "YOU", f.youLabel);
        box(ctx, f, 3, x0 + boxW + f.gridGap, f.role2Top, boxW, "OPPONENT", f.opponentLabel);

        // ---- primary action ---------------------------------------------------------------
        float bx = m;
        float btop = f.ctaTop + bs;   // fixed: the button fades in, it never slides
        if (f.reveal > 0.01f) {
            scratch.set(f.busy ? UiTheme.LINE : new Color(0.13f, 0.25f, 0.40f, 1f));
            scratch.lerp(Color.WHITE, f.pressLit);
            scratch.a = f.reveal;
            ctx.round.fill(ctx.batch, scratch, bx, btop, f.ctaW, f.ctaH);

            // The label swaps exactly at the peak of the wash, where the slab is white: going up
            // shows the old word, coming down shows the new one. No fade, no cross-over.
            scratch.set(f.ctaMuted ? MUTED : Color.WHITE);
            scratch.a = f.reveal;
            ctx.widgets.fitText(ctx.fonts.cta, f.cta, bx, btop, f.ctaW, f.ctaH, scratch,
                    ctx.widgets.ctaScale(f.ctaW));
        }
        float ctop = f.ctaTop + bs + (f.ctaH - UiDraw.capHeight(ctx.fonts.cta)) / 2f;

        // Same line as READY, centred under the OPPONENT chip.
        float leaveTop = ctop + (UiDraw.capHeight(ctx.fonts.cta)
                - UiDraw.capHeight(ctx.fonts.mono)) / 2f;
        float leaveW = UiDraw.width(ctx.fonts.mono, "LEAVE ROOM", 1.5f);
        scratch.set(MUTED);
        scratch.lerp(Color.WHITE, UiAnim.smooth(f.leaveHot));
        UiDraw.text(ctx.batch, ctx.fonts.mono, "LEAVE ROOM", f.leaveCentreX - leaveW / 2f, leaveTop,
                scratch, 1.5f);

        // ---- footer ------------------------------------------------------------------------
        if (f.lobbyStatus != null && f.lobbyStatus.alpha() > 0.01f) {
            boolean starting = f.lobbyStatus.shown().startsWith("STARTING");
            scratch.set(starting ? UiTheme.ACCENT : MUTED);
            scratch.a = f.lobbyStatus.alpha();
            UiDraw.text(ctx.batch, ctx.fonts.mono, f.lobbyStatus.shown(), m, f.footerTop + bs,
                    scratch, 1f);
        }
    }

    /** One role card: the name, the hint, and whatever the beam has already lit. */
    private void roleCard(PageContext ctx, Frame f, float top, String name, String hint,
                          float selected, float lit) {
        float chosen = UiAnim.smooth(selected);
        // Unselected stays readable but clearly dimmer; selected goes all the way to white.
        scratch.set(0.50f, 0.50f, 0.50f, 1f);
        scratch.lerp(Color.WHITE, chosen);
        centeredLit(ctx, ctx.fonts.displayMid, name, f.roleCx, top + 6f, scratch, Color.WHITE, 1f,
                lit);
        scratch.set(HINT_GREY);
        scratch.lerp(new Color(0.86f, 0.86f, 0.86f, 1f), chosen);
        centeredLit(ctx, ctx.fonts.mono, hint, f.roleCx, top + 62f, scratch, Color.WHITE, 0.4f, lit);
    }

    /**
     * Draws a centred string character by character, mixing in the lit colour for the glyphs the
     * beam has already passed. {@code lit} is 0..1 of how far across the string the light reached.
     */
    private void centeredLit(PageContext ctx, BitmapFont font, String s, float cx, float top,
                             Color base, Color litColor, float tracking, float lit) {
        float width = UiDraw.width(font, s, tracking);
        float x = cx - width / 2f;
        float edge = width * UiAnim.clamp01(lit * 1.25f);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            float adv = advance(font, c, tracking);
            float fromRight = width - (x - (cx - width / 2f)) - adv / 2f;
            float t = UiAnim.clamp01((edge - fromRight) / 26f);
            scratchLit.set(base);
            if (lit > 0.001f) scratchLit.lerp(litColor, t);
            font.setColor(scratchLit);
            font.draw(ctx.batch, String.valueOf(c), x, UiTheme.H - top);
            x += adv;
        }
    }

    private static float advance(BitmapFont font, char c, float tracking) {
        BitmapFont.Glyph g = font.getData().getGlyph(c);
        return (g != null ? g.xadvance : font.getSpaceXadvance()) + tracking;
    }

    /** White chip: a grey label over a value that cross-fades when it changes. */
    private void box(PageContext ctx, Frame f, int index, float x, float top, float w,
                     String label, String value) {
        ctx.round.fill(ctx.batch, Color.WHITE, x, top, w, f.boxH);
        float cx = x + w / 2f;
        // Equal space above the label, between the two lines, and below the value.
        float hLabel = UiDraw.capHeight(ctx.fonts.mono);
        float hValue = UiDraw.capHeight(ctx.fonts.displaySmall);
        float pad = (f.boxH - hLabel - hValue) / 3f;
        centered(ctx.batch, ctx.fonts.mono, label, cx, top + pad, LABEL_GREY, 0.4f);

        if (value != null && !value.equals(shown[index])) {
            previous[index] = shown[index];
            shown[index] = value;
            fade[index] = 0f;
        }
        float k = UiAnim.clamp01(fade[index]);
        float valueTop = top + pad * 2f + hLabel;
        float maxW = w - 32f;
        if (previous[index] != null && k < 0.999f) {
            centeredAlpha(ctx, ctx.fonts.displaySmall, previous[index], cx, valueTop,
                    roleColor(previous[index]), 0.5f, 1f - k, maxW);
        }
        centeredAlpha(ctx, ctx.fonts.displaySmall, shown[index], cx, valueTop,
                roleColor(shown[index]), 0.5f, k, maxW);
    }

    private static Color roleColor(String value) {
        if ("POLICE".equals(value)) return ROLE_POLICE;
        if ("THIEF".equals(value)) return ROLE_THIEF;
        return UiTheme.ON_LIGHT;
    }

    private void centeredAlpha(PageContext ctx, BitmapFont font, String s, float cx, float top,
                               Color color, float tracking, float alpha, float maxWidth) {
        if (s == null || alpha <= 0.01f) return;
        scratch.set(color);
        scratch.a = alpha;
        String fitted = UiDraw.fit(font, s, maxWidth, tracking);
        UiDraw.text(ctx.batch, font, fitted,
                cx - UiDraw.width(font, fitted, tracking) / 2f, top, scratch, tracking);
    }

    private static void centered(Batch batch, BitmapFont font, String s, float cx, float top,
                                 Color color, float tracking) {
        UiDraw.text(batch, font, s, cx - UiDraw.width(font, s, tracking) / 2f, top, color, tracking);
    }
}
