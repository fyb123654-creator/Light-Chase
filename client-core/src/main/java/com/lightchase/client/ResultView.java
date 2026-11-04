package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.Role;
import com.lightchase.data.DataFiles;

import java.util.Locale;

/**
 * The result page: who won, why, and the one action that leaves it.
 *
 * <p>Same contract as {@link ConnectView}: the app owns the result and the wash amounts, this class
 * owns the layout.
 */
final class ResultView {

    private ResultView() {
    }

    static final class Frame {
        MatchResult result;
        Role selfRole;
        DataFiles data;
        float ticksPerSecond;
        float bottomShift;
        boolean canLeave;
        float leaveWash;
        float margin;
        float boxH;
        float gap;
        float actionTop;
        float actionH;
        float actionW;
        float kickerTop;
    }

    static void draw(PageContext ctx, Frame f) {
        float w = ctx.worldWidth;
        float m = f.margin;
        float bs = f.bottomShift;

        UiDraw.text(ctx.batch, ctx.fonts.display, "LIGHT \u00b7 CHASE", m, 64f, UiTheme.TEXT, 1.5f);
        UiDraw.text(ctx.batch, ctx.fonts.monoBig, "RESULT", m + 2f, f.kickerTop,
                UiTheme.ACCENT, 2.5f);

        MatchResult result = f.result;
        if (result == null) {
            UiDraw.text(ctx.batch, ctx.fonts.mono, "WAITING FOR THE RESULT MESSAGE", m, 220f,
                    UiTheme.MUTED, 1.5f);
            return;
        }

        boolean noWinner = result.winner == null;
        boolean iWon = !noWinner && result.winner == f.selfRole;

        // The outcome, at masthead size: this is the one line that must be read first.
        String headline = noWinner ? "DRAW" : result.winner.name() + " WINS";
        UiDraw.text(ctx.batch, ctx.fonts.display, headline, m, 210f,
                noWinner ? UiTheme.MUTED : UiTheme.TEXT, 1.5f);
        UiDraw.text(ctx.batch, ctx.fonts.monoBig,
                noWinner ? "NO DECISION" : (iWon ? "YOU WIN" : "YOU LOSE"),
                m + 2f, 262f, noWinner ? UiTheme.MUTED : UiTheme.ACCENT, 2.5f);

        ctx.widgets.chip(m, 300f, w - 2 * m, f.boxH, "WINNER",
                result.winner == null ? "NONE" : result.winner.name(), 0f);

        // Facts, evenly spaced.
        float col4 = (w - 2 * m - 3 * f.gap) / 4f;
        fact(ctx, m, 402f, "REASON", String.valueOf(result.reason));
        fact(ctx, m + col4 + f.gap, 402f, "MARKS",
                result.marks + "/" + f.data.rules.maxMarks);
        fact(ctx, m + 2f * (col4 + f.gap), 402f, "DELIVERED",
                result.deliveredCount + "/" + f.data.map.materials.size());
        fact(ctx, m + 3f * (col4 + f.gap), 402f, "ELAPSED",
                String.format(Locale.ROOT, "%.1fs", result.elapsedTicks / f.ticksPerSecond));

        ctx.widgets.primaryButton(m, f.actionTop + bs, f.actionW, f.actionH, "BACK TO LOBBY",
                f.canLeave, f.leaveWash);

        UiDraw.text(ctx.batch, ctx.fonts.mono,
                "RESULT " + result.resultId + "   MATCH " + result.matchId,
                m, 652f + bs, UiTheme.MUTED, 1f);
    }

    /** One grey label followed by a white value on the same line. */
    private static void fact(PageContext ctx, float x, float top, String label, String value) {
        UiDraw.text(ctx.batch, ctx.fonts.mono, label, x, top, UiTheme.MUTED, 1.2f);
        UiDraw.text(ctx.batch, ctx.fonts.mono, value,
                x + UiDraw.width(ctx.fonts.mono, label, 1.2f) + 22f, top, UiTheme.TEXT, 0.6f);
    }
}
