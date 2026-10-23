package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class MatchResult {
    public final String resultId;
    public final String matchId;
    public final Role winner;
    public final ResultReason reason;
    public final String departedPlayerId;
    public final int elapsedTicks;
    public final int marks;
    public final int deliveredCount;
    public final long finalTick;
    public MatchResult(String resultId, String matchId, Role winner, ResultReason reason, String departedPlayerId, int elapsedTicks, int marks, int deliveredCount, long finalTick) {
        this.resultId = resultId;
        this.matchId = matchId;
        this.winner = winner;
        this.reason = reason;
        this.departedPlayerId = departedPlayerId;
        this.elapsedTicks = elapsedTicks;
        this.marks = marks;
        this.deliveredCount = deliveredCount;
        this.finalTick = finalTick;
    }
}
