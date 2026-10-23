package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class VisibleOpponent {
    public final String playerId;
    public final Role role;
    public final double x;
    public final double y;
    public final double headingRad;
    public final boolean carrying;
    public VisibleOpponent(String playerId, Role role, double x, double y, double headingRad, boolean carrying) {
        this.playerId = playerId;
        this.role = role;
        this.x = x;
        this.y = y;
        this.headingRad = headingRad;
        this.carrying = carrying;
    }
}
