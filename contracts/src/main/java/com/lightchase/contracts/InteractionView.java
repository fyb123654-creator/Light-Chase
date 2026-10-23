package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class InteractionView {
    public final InteractionTarget candidate;
    public final InteractionTarget active;
    public final int progressTicks;
    public final boolean requiresRelease;
    public final String blockedReason;
    public InteractionView(InteractionTarget candidate, InteractionTarget active, int progressTicks, boolean requiresRelease, String blockedReason) {
        this.candidate = candidate;
        this.active = active;
        this.progressTicks = progressTicks;
        this.requiresRelease = requiresRelease;
        this.blockedReason = blockedReason;
    }
}
