package com.lightchase.client;

import com.lightchase.contracts.ControlStatus;
import com.lightchase.contracts.MatchResult;

/**
 * Tracks the "player asked to leave a running match" state.
 *
 * <p>A quit is not a local disconnect: the gateway answers {@code leaveRoom()} with a PENDING event
 * immediately, the server queues a Departure, and the result only arrives once the next step has
 * been committed. Two rules follow, and both are easy to get wrong:
 *
 * <ul>
 *   <li>only a <b>terminal</b> control state ends the wait - clearing on PENDING cancels it before
 *       the request has even reached the server;</li>
 *   <li>the result must be remembered as a sentence, because the server closes the session in the
 *       same frame and the client clears every match artifact on disconnect.</li>
 * </ul>
 *
 * <p>Pure state, no libGDX and no clock of its own, so all of the above is unit-testable.
 */
final class MatchQuit {

    static final long TIMEOUT_MS = 5000;

    private boolean active;
    private long startedAtMs;
    private String summary;

    /** @return true when this call started a wait (false when one is already running). */
    boolean request(long nowMs) {
        if (active) return false;
        active = true;
        startedAtMs = nowMs;
        return true;
    }

    /** Feed every control status here; only LEAVE_ROOM and only terminal states matter. */
    void onControl(String action, ControlStatus.State state) {
        if (!"LEAVE_ROOM".equals(action) || state == null) return;
        if (state == ControlStatus.State.PENDING || state == ControlStatus.State.WAITING) return;
        active = false;
    }

    /** Remember, in plain words, how the match the player walked out of ended. */
    void onResult(MatchResult result) {
        if (!active || result == null) return;
        summary = "You left the match (" + result.reason + "); the win went to "
                + (result.winner == null ? "nobody" : result.winner);
        active = false;
    }

    /** @return true when the wait has just timed out, so the caller can tell the player. */
    boolean tick(long nowMs) {
        if (!active) return false;
        if (nowMs - startedAtMs <= TIMEOUT_MS) return false;
        active = false;
        return true;
    }

    /** @return the pending summary once, then forget it. */
    String takeSummary() {
        String s = summary;
        summary = null;
        return s;
    }

    boolean active() { return active; }

    /**
     * Ends the wait because the session is gone. The summary is deliberately kept: the server drops
     * the connection in the same frame it sends the result, so clearing here would throw away the
     * only thing the player gets to read. {@link #takeSummary()} is what retires it.
     */
    void reset() {
        active = false;
    }
}
