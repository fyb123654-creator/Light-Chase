package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class InteractionTarget {
    public final InteractionType type;
    public final String targetId;
    public InteractionTarget(InteractionType type, String targetId) {
        this.type = type;
        this.targetId = targetId;
    }
}
