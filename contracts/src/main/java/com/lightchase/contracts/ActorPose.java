package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class ActorPose {
    public final String playerId;
    public final double x;
    public final double y;
    public final double headingRad;
    public ActorPose(String playerId, double x, double y, double headingRad) {
        this.playerId = playerId;
        this.x = x;
        this.y = y;
        this.headingRad = headingRad;
    }
}
