package com.lightchase.game;

import com.lightchase.contracts.v2.Position;

/** One active, publicly visible portal trip. */
final class V2TransitState {

    final String transitId;
    final String portalId;
    final String playerId;
    final Position from;
    final Position to;
    final long startTick;
    final long endTick;

    V2TransitState(String transitId, String portalId, String playerId, Position from, Position to,
                   long startTick, long endTick) {
        this.transitId = transitId;
        this.portalId = portalId;
        this.playerId = playerId;
        this.from = from;
        this.to = to;
        this.startTick = startTick;
        this.endTick = endTick;
    }

    Position positionAt(long tick) {
        return V2RuleMath.interpolate(from, to,
                V2RuleMath.progress(tick, startTick, endTick));
    }
}
