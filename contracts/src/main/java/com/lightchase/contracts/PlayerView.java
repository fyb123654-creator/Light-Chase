package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class PlayerView {
    public final String matchId;
    public final long tick;
    public final long serverTimeMs;
    public final long ackInputSeq;
    public final Role role;
    public final int remainingTicks;
    public final int marks;
    public final int deliveredCount;
    public final boolean exitsOpen;
    public final ActorPose self;
    public final VisibleOpponent opponent;
    public final java.util.List<OriginStatus> originStatus;
    public final ThiefPrivate thief;
    public PlayerView(String matchId, long tick, long serverTimeMs, long ackInputSeq, Role role, int remainingTicks, int marks, int deliveredCount, boolean exitsOpen, ActorPose self, VisibleOpponent opponent, java.util.List<OriginStatus> originStatus, ThiefPrivate thief) {
        this.matchId = matchId;
        this.tick = tick;
        this.serverTimeMs = serverTimeMs;
        this.ackInputSeq = ackInputSeq;
        this.role = role;
        this.remainingTicks = remainingTicks;
        this.marks = marks;
        this.deliveredCount = deliveredCount;
        this.exitsOpen = exitsOpen;
        this.self = self;
        this.opponent = opponent;
        this.originStatus = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(originStatus));
        this.thief = thief;
    }
}
