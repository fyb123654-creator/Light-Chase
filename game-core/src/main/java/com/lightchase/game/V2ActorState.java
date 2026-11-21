package com.lightchase.game;

import com.lightchase.contracts.Role;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.v2.ActionResult;
import com.lightchase.contracts.v2.CardInstance;
import com.lightchase.contracts.v2.ItemInstance;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable per-player state owned by the match thread. */
final class V2ActorState {

    /** A bounded duplicate-action cache; the contract only requires a finite, deterministic window. */
    static final int ACTION_CACHE_LIMIT = 128;

    final String playerId;
    final Role role;
    double x;
    double y;
    double headingRad;
    /** Police only; null for the thief. Always in [0, 2*pi). */
    Double aimRad;
    /** Last applied movement input, kept so "no command" means "continue". */
    double moveX;
    double moveY;
    boolean held;
    long lastDropId;
    long lastInputSeq;

    final ItemInstance[] inventory = new ItemInstance[2];
    /** Thief only; the single independent card slot. */
    CardInstance card;
    long speedUntilTick;
    long lightUntilTick;

    final V2InteractionState interaction = new V2InteractionState();
    long highestActionId;
    private final Map<Long, ActionResult> actionCache = new LinkedHashMap<>();

    V2ActorState(String playerId, Role role, SpawnPose spawn) {
        this.playerId = playerId;
        this.role = role;
        this.x = spawn.x;
        this.y = spawn.y;
        this.headingRad = V2RuleMath.angle(spawn.headingRad);
        this.aimRad = role == Role.POLICE ? this.headingRad : null;
    }

    boolean speedActive(long tick) {
        return tick < speedUntilTick;
    }

    boolean lightActive(long tick) {
        return tick < lightUntilTick;
    }

    int emptySlot() {
        for (int i = 0; i < inventory.length; i++) {
            if (inventory[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /** Cached result for a duplicate action, or null when the id is not cached. */
    ActionResult cachedAction(long actionId) {
        return actionCache.get(actionId);
    }

    void rememberAction(long actionId, ActionResult result) {
        highestActionId = Math.max(highestActionId, actionId);
        actionCache.put(actionId, result);
        while (actionCache.size() > ACTION_CACHE_LIMIT) {
            Iterator<Long> it = actionCache.keySet().iterator();
            it.next();
            it.remove();
        }
    }
}
