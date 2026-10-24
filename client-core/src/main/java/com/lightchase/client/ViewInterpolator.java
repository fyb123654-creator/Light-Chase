package com.lightchase.client;

import com.lightchase.contracts.PlayerView;

/**
 * Interpolates render positions between the two most recent role-filtered snapshots.
 *
 * <p>Rules that matter and are easy to get wrong:
 * <ul>
 *   <li>the first snapshot of a match is used as-is (nothing to interpolate from);</li>
 *   <li>a hidden opponent is <b>not</b> interpolated — knowing it vanished must be immediate;</li>
 *   <li>a new {@code matchId} resets the history so a previous match can never leak in.</li>
 * </ul>
 *
 * <p>Pure arithmetic over contract DTOs: no libGDX, no clock of its own (the caller passes "now"),
 * so all of the above is unit-testable.
 */
public final class ViewInterpolator {

    private final long windowMs;
    private PlayerView previous;
    private PlayerView current;
    private long currentAtMs;

    private double selfX;
    private double selfY;
    private double opponentX;
    private double opponentY;
    private boolean opponentVisible;
    private boolean opponentCarrying;

    public ViewInterpolator(long windowMs) {
        this.windowMs = Math.max(1, windowMs);
    }

    /** Accept a snapshot that already passed the monotonic-tick check. */
    public void accept(PlayerView view, long nowMs) {
        if (view == null) { clear(); return; }
        if (current == null || current.matchId == null || !current.matchId.equals(view.matchId)) {
            previous = null;
        } else {
            previous = current;
        }
        current = view;
        currentAtMs = nowMs;
    }

    public void clear() {
        previous = null;
        current = null;
        currentAtMs = 0;
        opponentVisible = false;
        opponentCarrying = false;
        selfX = selfY = opponentX = opponentY = 0;
    }

    /** Compute the positions to draw at {@code nowMs}. Call once per frame before rendering. */
    public void sample(long nowMs) {
        opponentVisible = false;
        opponentCarrying = false;
        if (current == null) return;

        double alpha = 1.0;
        if (previous != null) {
            alpha = (nowMs - currentAtMs) / (double) windowMs;
            if (alpha < 0) alpha = 0;
            if (alpha > 1) alpha = 1;
        }

        double px = previous == null ? current.self.x : previous.self.x;
        double py = previous == null ? current.self.y : previous.self.y;
        selfX = px + (current.self.x - px) * alpha;
        selfY = py + (current.self.y - py) * alpha;

        if (current.opponent != null) {
            opponentVisible = true;
            boolean hadOpponent = previous != null && previous.opponent != null;
            double ox = hadOpponent ? previous.opponent.x : current.opponent.x;
            double oy = hadOpponent ? previous.opponent.y : current.opponent.y;
            opponentX = ox + (current.opponent.x - ox) * alpha;
            opponentY = oy + (current.opponent.y - oy) * alpha;
            // A boolean cannot be interpolated: always follow the newest snapshot.
            opponentCarrying = current.opponent.carrying;
        }
    }

    public boolean hasCurrent() { return current != null; }
    public boolean opponentVisible() { return opponentVisible; }
    public boolean opponentCarrying() { return opponentCarrying; }
    public double selfX() { return selfX; }
    public double selfY() { return selfY; }
    public double opponentX() { return opponentX; }
    public double opponentY() { return opponentY; }
}
