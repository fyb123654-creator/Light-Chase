package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class PlayerCommand {
    public final String playerId;
    public final long inputSeq;
    public final double moveX;
    public final double moveY;
    public final boolean interactHeld;
    public final Long dropId;
    public PlayerCommand(String playerId, long inputSeq, double moveX, double moveY, boolean interactHeld, Long dropId) {
        this.playerId = playerId;
        this.inputSeq = inputSeq;
        this.moveX = moveX;
        this.moveY = moveY;
        this.interactHeld = interactHeld;
        this.dropId = dropId;
    }
}
