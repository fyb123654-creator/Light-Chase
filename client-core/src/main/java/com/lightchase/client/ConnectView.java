package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;

/**
 * The connection page: three white chips over the host, the port and the room, then the actions.
 *
 * <p>It is a painter, not a controller: {@link Frame} carries what the app has already decided
 * (values, wash amounts, the two status lines) and this class only decides where the pixels go. The
 * app keeps owning the state, so there is still exactly one place that mutates it.
 */
final class ConnectView {

    private ConnectView() {
    }

    /** Everything the page needs for one frame. The app fills this in; the view never keeps it. */
    static final class Frame {
        String host = "";
        String port = "";
        String room = "";
        /** Which chip was edited last, to flash it, and when. */
        int editedChip = -1;
        long editedAtMs;
        float scenarioFade;
        String scenarioLabel = "";
        String modeLabel = "";
        boolean lanAvailable;
        /** Null until the platform has reported a permission state. */
        LanPermission permission;
        TextFade status;
        TextFade detail;
        TextFade permissionLine;
        /** 0..1 wash per control: CONNECT, SCENARIO, MODE, then the three chips. */
        java.util.function.IntFunction<Float> wash;

        // layout, resolved by the app so the page never guesses
        float margin;
        float chipTop;
        float chipW;
        float chipH;
        float gap;
        float actionTop;
        float ctaW;
        float ctaH;
        float scenarioW;
        float scenarioX;
        float modeW;
        float modeX;
        float rule2Top;
        float kickerTop;
        float bottomShift;
    }

    static void draw(PageContext ctx, Frame f) {
        float w = ctx.worldWidth;
        float m = f.margin;

        UiDraw.text(ctx.batch, ctx.fonts.display, "LIGHT \u00b7 CHASE", m, 64f, UiTheme.TEXT, 1.5f);
        UiDraw.text(ctx.batch, ctx.fonts.monoBig, "CONNECTION", m + 2f, f.kickerTop,
                UiTheme.ACCENT, 2.5f);

        long since = System.nanoTime() / 1_000_000L - f.editedAtMs;
        float[] chips = {
                f.editedChip == 0 && since < 700 ? f.wash.apply(4) : 0f,
                f.editedChip == 1 && since < 700 ? f.wash.apply(5) : 0f,
                f.editedChip == 2 && since < 700 ? f.wash.apply(6) : 0f,
        };
        ctx.widgets.chip(m, f.chipTop, f.chipW, f.chipH, "SERVER HOST", f.host, chips[0]);
        ctx.widgets.chip(m + f.chipW + f.gap, f.chipTop, f.chipW, f.chipH, "PORT", f.port, chips[1]);
        ctx.widgets.chip(m + 2f * (f.chipW + f.gap), f.chipTop, f.chipW, f.chipH, "ROOM",
                f.room, chips[2]);

        ctx.widgets.primaryButton(m, f.actionTop, f.ctaW, f.ctaH, "CONNECT", f.lanAvailable,
                f.wash.apply(0));
        if (f.scenarioFade > 0.01f) {
            ctx.widgets.framedAction(f.scenarioX, f.actionTop, f.scenarioW, f.ctaH,
                    f.scenarioLabel, true, f.wash.apply(2), f.scenarioFade);
        }
        ctx.widgets.framedAction(f.modeX, f.actionTop, f.modeW, f.ctaH, f.modeLabel, true,
                f.wash.apply(3), 1f);

        // STATUS is a caption on the value, not a row of its own: the gap above it is the block's
        // only breathing room. Everything below keeps the 52 rhythm.
        float infoTop = f.rule2Top + f.bottomShift + 35f;
        float valueTop = infoTop + 26f;
        float capLabel = UiDraw.capHeight(ctx.fonts.mono);
        float capValue = UiDraw.capHeight(ctx.fonts.displaySmall);
        UiDraw.text(ctx.batch, ctx.fonts.mono, "STATUS", m, infoTop, UiTheme.MUTED, 1.2f);
        line(ctx, f.status, ctx.fonts.displaySmall, m, valueTop, UiTheme.TEXT, 0.5f);
        line(ctx, f.detail, ctx.fonts.mono, m + 300f, valueTop + (capValue - capLabel) / 2f,
                UiTheme.MUTED, 1f);
        if (f.permissionLine != null && f.permissionLine.alpha() > 0.01f) {
            boolean ok = f.permission == null || f.permission.granted();
            Color ink = ok ? UiTheme.MUTED : UiTheme.ACCENT;
            UiDraw.text(ctx.batch, ctx.fonts.mono,
                    UiDraw.fit(ctx.fonts.mono, f.permissionLine.shown(), w - 2f * m, 1f),
                    m, valueTop + 74f, withAlpha(ink, f.permissionLine.alpha()), 1f);
        }
    }

    /** One faded line drawn from a {@link TextFade}, skipping it while it is invisible. */
    private static void line(PageContext ctx, TextFade fade, com.badlogic.gdx.graphics.g2d.BitmapFont font,
                             float x, float top, Color color, float tracking) {
        if (fade == null || fade.alpha() <= 0.01f) return;
        UiDraw.text(ctx.batch, font, fade.shown(), x, top, withAlpha(color, fade.alpha()), tracking);
    }

    private static Color withAlpha(Color base, float alpha) {
        return new Color(base.r, base.g, base.b, alpha);
    }
}
