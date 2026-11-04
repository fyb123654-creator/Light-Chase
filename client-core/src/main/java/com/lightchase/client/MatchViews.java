package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.Role;

import java.util.Locale;

/**
 * The countdown page and the in-match HUD.
 *
 * <p>Both used to be private methods of the application class, surrounded by the room lifecycle and
 * the input routing. They are pure: given a view and the faded readouts they draw, they emit the
 * same pixels wherever they are called from.
 */
final class MatchViews {

    private MatchViews() {
    }

    /** One number, dead centre, on white; it cross-fades with the second before it. */
    static void drawCountdown(PageContext ctx, TextFade count) {
        if (count.alpha() <= 0.01f) return;
        String text = count.shown();
        float w = UiDraw.width(ctx.fonts.huge, text, 4f);
        float cap = UiDraw.capHeight(ctx.fonts.huge);
        UiDraw.text(ctx.batch, ctx.fonts.huge, text, ctx.worldWidth / 2f - w / 2f,
                UiTheme.H / 2f - cap / 2f + 26f,
                new Color(0.07f, 0.07f, 0.08f, count.alpha()), 4f);
    }

    /**
     * The in-match overlay: the clock, the three readouts under the minimap, the caption under the
     * action button and the quit control.
     */
    static void drawHud(PageContext ctx, PlayerView view, Role selfRole, ActionButton button,
                        TouchController touch, TextFade marks, TextFade delivered, TextFade exits,
                        MatchQuit quit, float ticksPerSecond,
                        float quitX, float quitTop, float quitW, float quitH) {
        float w = ctx.worldWidth;
        String time = view == null ? "--" : String.format(Locale.ROOT, "%.1f",
                view.remainingTicks / ticksPerSecond);
        ctx.widgets.centered(ctx.fonts.displayMid, time, w / 2f, 24f, UiTheme.TEXT, 1f);

        ctx.widgets.readout("MARKS", marks, 206f);
        ctx.widgets.readout("DELIVERED", delivered, 234f);
        ctx.widgets.readout("EXITS", exits, 262f);

        if (selfRole == Role.THIEF) {
            float capTop = UiTheme.H - (touch.actionY() - TouchController.HOLD_R) + 12f;
            button.drawCaption(ctx.batch, ctx.fonts.mono, touch.actionX(), capTop, UiTheme.MUTED);
        }

        String quitLabel = quit.active() ? "LEAVING..." : "QUIT MATCH";
        float labelW = UiDraw.width(ctx.fonts.mono, quitLabel, 1.5f);
        float baseline = quitTop + (quitH - UiDraw.capHeight(ctx.fonts.mono)) / 2f;
        UiDraw.text(ctx.batch, ctx.fonts.mono, quitLabel, quitX + (quitW - labelW) / 2f,
                baseline, Color.WHITE, 1.5f);
    }
}
