package com.lightchase.network;

import com.lightchase.contracts.*;
import java.util.*;

/** Semantic checks after the codec's strict structural checks, before any UI callback. */
final class SnapshotValidator {
    private static final Set<String> MATERIALS = new HashSet<>(Arrays.asList("M1", "M2", "M3"));

    static void validate(PlayerView view, String playerId, RoomState room) {
        if (playerId == null || !Objects.equals(room.matchId, view.matchId)
                || !Objects.equals(playerId, view.self.playerId)) bad();
        RoomPlayer self = null, other = null;
        for (RoomPlayer member : room.players) {
            if (Objects.equals(playerId, member.playerId)) {
                if (self != null) bad();
                self = member;
            } else {
                if (other != null) bad();
                other = member;
            }
        }
        if (self == null || self.role == null || view.role != self.role) bad();
        if (view.opponent != null) {
            VisibleOpponent opponent = view.opponent;
            if (other == null || other.role == null || other.role == self.role
                    || !Objects.equals(other.playerId, opponent.playerId) || other.role != opponent.role
                    || (opponent.role == Role.POLICE && opponent.carrying)) bad();
        }
        if (view.remainingTicks < 0 || view.remainingTicks > 5400
                || view.marks < 0 || view.marks > 3
                || view.deliveredCount < 0 || view.deliveredCount > 3) bad();
        Set<String> origins = new HashSet<>();
        for (OriginStatus origin : view.originStatus) {
            if (!MATERIALS.contains(origin.materialId) || !origins.add(origin.materialId)) bad();
        }
        if (!origins.equals(MATERIALS)) bad();
    }

    private static void bad() { throw new IllegalArgumentException("INVALID_SNAPSHOT"); }
    private SnapshotValidator() {}
}
