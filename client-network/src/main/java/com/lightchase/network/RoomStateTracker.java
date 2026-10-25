package com.lightchase.network;

import com.google.gson.JsonObject;
import com.lightchase.contracts.*;
import java.util.*;

/** Connection-local room authority, revision and lifecycle checks. Mutates only after validation. */
final class RoomStateTracker {
    private RoomState previous;
    private JsonObject previousBody;
    private final Set<String> finishedMatches = new HashSet<>();

    void reset() {
        previous = null;
        previousBody = null;
        finishedMatches.clear();
    }

    void finish(String matchId) {
        if (matchId != null) finishedMatches.add(matchId);
    }

    /** False means a stale revision or a byte-independent identical retransmission. */
    boolean accept(RoomState room, JsonObject body, ConnectionOptions options, String playerId) {
        if (playerId == null || !Objects.equals(options.roomId.trim().toUpperCase(Locale.ROOT), room.roomId)
                || !Objects.equals(options.mapHash, room.mapHash) || !Objects.equals(options.configHash, room.configHash)
                || room.mapId.trim().isEmpty()) bad();
        if (previous != null) {
            if (!Objects.equals(previous.mapId, room.mapId)) bad();
            if (room.revision < previous.revision) return false;
            if (room.revision == previous.revision) {
                if (!previousBody.equals(body)) bad();
                return false;
            }
        }
        validateMembersAndPhase(room, playerId);
        if (room.matchId != null && finishedMatches.contains(room.matchId)
                && (room.phase == RoomPhase.RUNNING || previous == null
                    || !Objects.equals(previous.matchId, room.matchId))) bad();
        if (previous != null) {
            long changes = room.revision - previous.revision;
            if (changes < minimumTransitions(previous, room)) bad();
            if (previous.phase == RoomPhase.COUNTDOWN) {
                // A restart needs cancellation plus both readiness changes; role selection also cancels.
                if (room.phase == RoomPhase.COUNTDOWN && changes < 3
                        && (room.countdownRemainingMs > previous.countdownRemainingMs || !sameAssignments(previous, room))) bad();
                if (room.phase == RoomPhase.RUNNING && changes < 4 && !sameAssignments(previous, room)) bad();
            }
            if (room.matchId != null && Objects.equals(previous.matchId, room.matchId)) {
                for (RoomPlayer member : room.players) {
                    RoomPlayer old = find(previous, member.playerId);
                    if (old == null || old.role != member.role || old.ready != member.ready
                            || (old.returned && !member.returned)) bad();
                }
            }
        }
        // Commit only after every check, so rejected packets cannot reset a result or input/tick counters.
        if (previous != null && previous.matchId != null && !Objects.equals(previous.matchId, room.matchId)) {
            finish(previous.matchId);
        }
        if (room.phase == RoomPhase.FINISHED) finish(room.matchId);
        previous = room;
        previousBody = body.deepCopy();
        return true;
    }

    private static void validateMembersAndPhase(RoomState room, String playerId) {
        boolean inMatch = room.phase == RoomPhase.RUNNING || room.phase == RoomPhase.FINISHED;
        if (inMatch != (room.matchId != null) || (room.matchId != null && room.matchId.trim().isEmpty())) bad();
        if (room.countdownRemainingMs < 0 || room.countdownRemainingMs > 3000
                || (room.phase != RoomPhase.COUNTDOWN && room.countdownRemainingMs != 0)) bad();
        if (room.players.isEmpty() || room.players.size() > 2) bad();
        Set<String> ids = new HashSet<>();
        Set<Role> roles = new HashSet<>();
        for (RoomPlayer member : room.players) {
            if (member.playerId.trim().isEmpty() || !ids.add(member.playerId)) bad();
            if (member.role != null && !roles.add(member.role)) bad();
            if ((member.ready && member.role == null) || (member.returned && room.phase != RoomPhase.FINISHED)) bad();
            if (inMatch && member.role == null) bad();
        }
        if (!ids.contains(playerId)) bad();
        if (room.phase == RoomPhase.COUNTDOWN) {
            if (room.players.size() != 2 || roles.size() != 2) bad();
            for (RoomPlayer member : room.players) if (!member.ready) bad();
        }
        if (room.phase == RoomPhase.RUNNING && (room.players.size() != 2 || roles.size() != 2)) bad();
    }

    /** Missing intermediate broadcasts are legal when the revision gap can contain the path. */
    private static int minimumTransitions(RoomState from, RoomState to) {
        if (from.matchId != null && to.matchId != null && !from.matchId.equals(to.matchId)) {
            return (from.phase == RoomPhase.RUNNING ? 1 : 0) + (to.phase == RoomPhase.RUNNING ? 3 : 4);
        }
        switch (from.phase) {
            case LOBBY:
                switch (to.phase) { case COUNTDOWN: return 1; case RUNNING: return 2; case FINISHED: return 3; default: return 0; }
            case COUNTDOWN:
                switch (to.phase) { case LOBBY: case RUNNING: return 1; case FINISHED: return 2; default: return 0; }
            case RUNNING:
                switch (to.phase) { case FINISHED: return 1; case LOBBY: return 2; case COUNTDOWN: return 3; default: return 0; }
            case FINISHED:
                switch (to.phase) { case LOBBY: return 1; case COUNTDOWN: return 2; case RUNNING: return 3; default: return 0; }
            default: throw new IllegalArgumentException("INVALID_ROOM_STATE");
        }
    }

    private static RoomPlayer find(RoomState room, String playerId) {
        for (RoomPlayer member : room.players) if (member.playerId.equals(playerId)) return member;
        return null;
    }
    private static boolean sameAssignments(RoomState from, RoomState to) {
        if (from.players.size() != to.players.size()) return false;
        for (int i = 0; i < from.players.size(); i++) {
            RoomPlayer a = from.players.get(i), b = to.players.get(i);
            if (!a.playerId.equals(b.playerId) || a.role != b.role) return false;
        }
        return true;
    }
    private static void bad() { throw new IllegalArgumentException("INVALID_ROOM_STATE"); }
}
