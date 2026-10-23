package com.lightchase.game;

import com.lightchase.contracts.Role;
import com.lightchase.contracts.SpawnPose;

/** Mutable per-player state owned by the match thread. */
final class ActorState {
    final String playerId;
    final Role role;
    double x;
    double y;
    double headingRad;
    /** Last applied movement input, kept so "no command" means "continue". */
    double moveX;
    double moveY;
    boolean held;
    long lastDropId;

    ActorState(String playerId, Role role, SpawnPose spawn) {
        this.playerId = playerId;
        this.role = role;
        this.x = spawn.x;
        this.y = spawn.y;
        this.headingRad = RuleMath.normalizeHeading(spawn.headingRad);
    }
}
