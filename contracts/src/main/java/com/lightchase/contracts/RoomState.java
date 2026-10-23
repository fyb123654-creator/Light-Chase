package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class RoomState {
    public final String roomId;
    public final long revision;
    public final RoomPhase phase;
    public final String mapId;
    public final String mapHash;
    public final String configHash;
    public final String matchId;
    public final int countdownRemainingMs;
    public final java.util.List<RoomPlayer> players;
    public RoomState(String roomId, long revision, RoomPhase phase, String mapId, String mapHash, String configHash, String matchId, int countdownRemainingMs, java.util.List<RoomPlayer> players) {
        this.roomId = roomId;
        this.revision = revision;
        this.phase = phase;
        this.mapId = mapId;
        this.mapHash = mapHash;
        this.configHash = configHash;
        this.matchId = matchId;
        this.countdownRemainingMs = countdownRemainingMs;
        this.players = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(players));
    }
}
