package com.lightchase.game;

import com.lightchase.contracts.InteractionTarget;

/** Current interaction candidate/active target and hold progress. */
final class InteractionState {
    InteractionTarget candidate;
    InteractionTarget active;
    int progressTicks;
    boolean requiresRelease;
    String blockedReason = "NO_TARGET";

    void clearActive() {
        active = null;
        progressTicks = 0;
    }

    /** Clears every interaction field so an ended match cannot expose a stale target. */
    void reset() {
        candidate = null;
        clearActive();
        requiresRelease = false;
        blockedReason = "NO_TARGET";
    }
}
