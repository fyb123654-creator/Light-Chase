package com.lightchase.game;

import com.lightchase.contracts.v2.InteractionBlock;
import com.lightchase.contracts.v2.InteractionTarget;

/** Current interaction candidate/active target and hold progress for one actor. */
final class V2InteractionState {

    InteractionTarget candidate;
    InteractionTarget active;
    int progressTicks;
    boolean requiresRelease;
    InteractionBlock blockedReason = InteractionBlock.NO_TARGET;
    /** One-step diagnostic used when a locked target disappears mid-hold. */
    InteractionBlock forcedReason;

    void clearActive() {
        active = null;
        progressTicks = 0;
    }

    /** Clears every interaction field so an ended match cannot expose a stale target. */
    void reset() {
        candidate = null;
        clearActive();
        requiresRelease = false;
        blockedReason = InteractionBlock.NO_TARGET;
        forcedReason = null;
    }
}
