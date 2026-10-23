package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class StepResult {
    public final long tick;
    public final boolean visibilityChanged;
    public final boolean ended;
    public final MatchResult result;
    public StepResult(long tick, boolean visibilityChanged, boolean ended, MatchResult result) {
        this.tick = tick;
        this.visibilityChanged = visibilityChanged;
        this.ended = ended;
        this.result = result;
    }
}
