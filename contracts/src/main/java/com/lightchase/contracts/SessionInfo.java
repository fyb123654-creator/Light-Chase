package com.lightchase.contracts;
/** Local callback data from a validated WELCOME; not a new wire message. */
public final class SessionInfo {
    public final String sessionId, playerId;
    public SessionInfo(String sessionId, String playerId) {
        this.sessionId = java.util.Objects.requireNonNull(sessionId);
        this.playerId = java.util.Objects.requireNonNull(playerId);
    }
}
