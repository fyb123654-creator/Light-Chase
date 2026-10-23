package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class RoomPlayer {
    public final String playerId;
    public final Role role;
    public final boolean ready;
    public final String connection;
    public final boolean returned;
    public RoomPlayer(String playerId, Role role, boolean ready, String connection, boolean returned) {
        this.playerId = playerId;
        this.role = role;
        this.ready = ready;
        this.connection = connection;
        this.returned = returned;
    }
}
