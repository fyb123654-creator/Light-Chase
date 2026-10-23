package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class Departure {
    public final String playerId;
    public final DepartureReason reason;
    public Departure(String playerId, DepartureReason reason) {
        this.playerId = playerId;
        this.reason = reason;
    }
}
