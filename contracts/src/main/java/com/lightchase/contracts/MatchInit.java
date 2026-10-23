package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class MatchInit {
    public final String matchId;
    public final String policePlayerId;
    public final String thiefPlayerId;
    public MatchInit(String matchId, String policePlayerId, String thiefPlayerId) {
        this.matchId = matchId;
        this.policePlayerId = policePlayerId;
        this.thiefPlayerId = thiefPlayerId;
    }
}
