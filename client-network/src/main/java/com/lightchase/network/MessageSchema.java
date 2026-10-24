package com.lightchase.network;

import com.google.gson.JsonObject;
import com.lightchase.contracts.*;
import java.util.*;

/** v1 structural checks only. Session, phase, authority and gameplay remain with receivers. */
final class MessageSchema {
    private static final Set<String> CLIENT = names("HELLO", "JOIN_ROOM", "SELECT_ROLE", "SET_READY",
            "INPUT", "HEARTBEAT", "LEAVE_ROOM", "RETURN_LOBBY", "RESULT_ACK");
    private static final Set<String> SERVER = names("WELCOME", "ACK", "ERROR", "ROOM_STATE",
            "SNAPSHOT", "GAME_OVER", "PONG");
    private static final Set<String> CONTROLS = names("HELLO", "JOIN_ROOM", "SELECT_ROLE", "SET_READY",
            "LEAVE_ROOM", "RETURN_LOBBY");
    private static final Set<String> MATCH_ACTIONS = names("INPUT", "RETURN_LOBBY", "RESULT_ACK");

    static void client(WireCodec.Message m) {
        if (!CLIENT.contains(m.type)) bad();
        correlation(m, CONTROLS.contains(m.type),
                MATCH_ACTIONS.contains(m.type));
        JsonObject b = m.body;
        switch (m.type) {
            case "HELLO":
                fields(b, "clientBuild", "mapHash", "configHash");
                strings(b, "clientBuild", "mapHash", "configHash"); break;
            case "JOIN_ROOM": fields(b, "roomId"); strings(b, "roomId"); break;
            case "SELECT_ROLE":
                fields(b, "role"); oneOf(b, "role", "POLICE", "THIEF"); break;
            case "SET_READY": fields(b, "ready"); WireCodec.bool(b, "ready"); break;
            case "INPUT":
                fields(b, "inputSeq", "moveX", "moveY", "interactHeld", "dropId");
                unsigned(b, "inputSeq"); number(b, "moveX"); number(b, "moveY");
                WireCodec.bool(b, "interactHeld");
                if (!b.get("dropId").isJsonNull()) unsigned(b, "dropId");
                break;
            case "HEARTBEAT": fields(b, "nonce"); unsigned(b, "nonce"); break;
            case "LEAVE_ROOM":
                fields(b, "reason"); oneOf(b, "reason", "QUIT", "BACKGROUND"); break;
            case "RETURN_LOBBY":
            case "RESULT_ACK": fields(b, "resultId"); strings(b, "resultId"); break;
            default: bad();
        }
    }

    static void server(WireCodec.Message m) {
        if (!SERVER.contains(m.type)) bad();
        JsonObject b = m.body;
        switch (m.type) {
            case "WELCOME":
                correlation(m, true, false);
                fields(b, "sessionId", "playerId", "protocolVersion", "mapHash", "configHash",
                        "heartbeatMs", "disconnectMs");
                strings(b, "sessionId", "playerId", "mapHash", "configHash");
                integer(b, "protocolVersion"); integer(b, "heartbeatMs"); integer(b, "disconnectMs");
                break;
            case "ACK":
                fields(b, "action");
                String action = WireCodec.string(b, "action");
                if (!CONTROLS.contains(action) || "HELLO".equals(action)) bad();
                correlation(m, true, "RETURN_LOBBY".equals(action)); break;
            case "ERROR":
                // Errors echo the available request/match IDs; either can be null.
                fields(b, "code", "message", "fatal");
                strings(b, "code", "message"); WireCodec.bool(b, "fatal"); break;
            case "ROOM_STATE":
                correlation(m, false, false); BodyValidator.validate(b, RoomState.class); break;
            case "SNAPSHOT":
                correlation(m, false, true); BodyValidator.validate(b, PlayerView.class); break;
            case "GAME_OVER":
                correlation(m, false, true); BodyValidator.validate(b, MatchResult.class); break;
            case "PONG":
                correlation(m, false, false); fields(b, "nonce", "serverTimeMs");
                unsigned(b, "nonce"); unsigned(b, "serverTimeMs"); break;
            default: bad();
        }
    }

    private static void correlation(WireCodec.Message m, boolean request, boolean match) {
        if ((m.requestId != null) != request || (m.matchId != null) != match) bad();
    }
    private static void fields(JsonObject b, String... names) { WireCodec.exactFields(b, names); }
    private static void strings(JsonObject b, String... names) {
        for (String name : names) WireCodec.string(b, name);
    }
    private static void unsigned(JsonObject b, String name) {
        long n = WireCodec.integer(b, name);
        if (n < 0 || n > WireCodec.MAX_SAFE_INTEGER) bad();
    }
    private static void integer(JsonObject b, String name) {
        long n = WireCodec.integer(b, name);
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) bad();
    }
    private static void number(JsonObject b, String name) { BodyValidator.validate(b.get(name), double.class); }
    private static void oneOf(JsonObject b, String name, String... values) {
        String value = WireCodec.string(b, name);
        for (String allowed : values) if (allowed.equals(value)) return;
        bad();
    }
    private static void bad() { throw new IllegalArgumentException("BAD_MESSAGE"); }
    private static Set<String> names(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
