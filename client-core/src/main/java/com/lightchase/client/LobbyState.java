package com.lightchase.client;

import com.lightchase.contracts.Role;

/**
 * The lobby's own state: which role is chosen, how the beam is pointing, whether the player is ready,
 * and the one-shot pulses that go with them.
 *
 * <p>It used to be nineteen loose fields on the application class, read and written from the update
 * loop, the tap handlers, the page frame and the transition code. Grouping them here means the lobby
 * can be reasoned about - and later moved - as one thing.
 *
 * <p>Only the parts that are genuinely self-contained carry behaviour; the app still drives the
 * per-frame easing because it is the one place that knows whether the lobby is on screen.
 */
final class LobbyState {

    /** The role the player picked, or null while they have not. */
    Role selectedRole;
    /** True while the room's own role must be ignored: it outlives the pick. */
    boolean suppressRoomRole;

    /** 0..1 how much each card is chosen, eased. Also what the beam points at. */
    float selectPolice;
    float selectThief;
    /** 0..1 how far the police cap has turned towards the words. */
    float turn;
    /** 0..1 lamp brightness. */
    float beamOn;
    /** Seconds, drives the idle bob and the beam's sweep. */
    float lobbyClock;

    /** 0..1 the READY slab's reveal, and the white wash while it is pressed. */
    float readyReveal;
    float readyPress;
    /** True from the tap until the wash peaks; the label swaps at the peak. */
    boolean readyQueued;
    boolean readyReturning;
    String readyLabelAtPress = "READY";
    String readyLabelAfter = "CANCEL";

    /** LEAVE ROOM's one-shot pulse. */
    float leaveHot;
    float leaveT = -1f;
    boolean leaveQueued;

    /** The small line that appears next to a card when the room overrules the pick. */
    float roleNoticeT;
    Role roleNoticeRole;
    String roleNoticeText = "";

    /** Clears the pick, for a fresh visit to the lobby. */
    void forgetRole() {
        selectedRole = null;
        suppressRoomRole = true;
    }

    /**
     * The one word the primary action shows. Kept here so the page, the tap handler and the
     * transition code can never disagree about it.
     */
    String ctaLabel(boolean ready, boolean pending) {
        if (readyQueued) return readyLabelAtPress;
        if (readyReturning) return readyLabelAfter;
        if (pending) return "WAITING...";
        return ready ? "CANCEL" : "READY";
    }

    /** Starts the READY press: remember both words so the swap at the peak is exact. */
    void beginReadyPress(boolean wasReady) {
        readyLabelAtPress = wasReady ? "CANCEL" : "READY";
        readyLabelAfter = wasReady ? "READY" : "CANCEL";
        readyQueued = true;
        readyReturning = false;
        readyPress = 0f;
    }

    /** True once the white peak has passed and the button is on its way back down. */
    boolean readyPeaked() {
        return readyQueued && readyPress >= 1f;
    }
}
