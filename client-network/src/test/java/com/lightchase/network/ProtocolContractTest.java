package com.lightchase.network;

import com.google.gson.*;
import com.lightchase.contracts.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Protocol-only fake peers: no socket, Android or game engine is needed for B1. */
public class ProtocolContractTest {
    private final WireCodec codec = new WireCodec();

    static String fixture(String name) throws Exception {
        try (InputStream in = ProtocolContractTest.class.getResourceAsStream(name)) {
            assertNotNull("Missing fixture " + name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> clientMessages() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("HELLO", codec.encode("HELLO", 1, "r1", null,
                fields("clientBuild", "TEST_ONLY", "mapHash", "test-map", "configHash", "test-rules")));
        samples.put("JOIN_ROOM", codec.encode("JOIN_ROOM", 2, "r2", null, fields("roomId", "TEST")));
        samples.put("SELECT_ROLE", codec.encode("SELECT_ROLE", 3, "r3", null, fields("role", "POLICE")));
        samples.put("SET_READY", codec.encode("SET_READY", 4, "r4", null, fields("ready", true)));
        samples.put("INPUT", codec.encode("INPUT", 5, null, "m1",
                fields("inputSeq", 1, "moveX", 1.0, "moveY", 0.0, "interactHeld", false, "dropId", null)));
        samples.put("HEARTBEAT", codec.encode("HEARTBEAT", 6, null, null, fields("nonce", 7)));
        samples.put("LEAVE_ROOM", codec.encode("LEAVE_ROOM", 7, "r7", null, fields("reason", "BACKGROUND")));
        samples.put("RETURN_LOBBY", codec.encode("RETURN_LOBBY", 8, "r8", "m1", fields("resultId", "m1:result")));
        samples.put("RESULT_ACK", codec.encode("RESULT_ACK", 9, null, "m1", fields("resultId", "m1:result")));
        return samples;
    }

    private Map<String, String> serverMessages() throws Exception {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("WELCOME", codec.encode("WELCOME", 1, "r1", null,
                fields("sessionId", "s1", "playerId", "p1", "protocolVersion", 1,
                        "mapHash", "test-map", "configHash", "test-rules", "heartbeatMs", 1000, "disconnectMs", 5000)));
        samples.put("ACK", codec.encode("ACK", 2, "r2", null, fields("action", "JOIN_ROOM")));
        samples.put("ERROR", codec.encode("ERROR", 3, null, null,
                fields("code", "BAD_MESSAGE", "message", "Invalid field", "fatal", false)));
        RoomState room = new RoomState("TEST", 1, RoomPhase.LOBBY, "test-room", "test-map", "test-rules",
                null, 0, Arrays.asList(new RoomPlayer("p1", Role.POLICE, false, "CONNECTED", false)));
        samples.put("ROOM_STATE", codec.encode("ROOM_STATE", 4, null, null, room));
        samples.put("SNAPSHOT", fixture("/protocol/police-hidden.json"));
        samples.put("GAME_OVER", fixture("/protocol/game-over.json"));
        samples.put("PONG", codec.encode("PONG", 7, null, null, fields("nonce", 7, "serverTimeMs", 1000)));
        return samples;
    }

    @Test public void allNineClientMessagesHaveExactBodiesAndDirection() {
        for (Map.Entry<String, String> sample : clientMessages().entrySet()) {
            assertEquals(sample.getKey(), codec.decodeClient(sample.getValue()).type);
            assertThrows(sample.getKey(), IllegalArgumentException.class, () -> codec.decodeServer(sample.getValue()));
        }
    }

    @Test public void allSevenServerMessagesHaveExactBodiesAndDirection() throws Exception {
        for (Map.Entry<String, String> sample : serverMessages().entrySet()) {
            assertEquals(sample.getKey(), codec.decodeServer(sample.getValue()).type);
            assertThrows(sample.getKey(), IllegalArgumentException.class, () -> codec.decodeClient(sample.getValue()));
        }
    }

    @Test public void everyMissingOrAdditionalBodyFieldIsRejectedInBothDirections() throws Exception {
        Map<String, String> all = new LinkedHashMap<>(clientMessages());
        all.putAll(serverMessages());
        for (String source : all.values()) {
            JsonObject original = JsonParser.parseString(source).getAsJsonObject();
            for (String field : new ArrayList<>(original.getAsJsonObject("body").keySet())) {
                JsonObject missing = original.deepCopy();
                missing.getAsJsonObject("body").remove(field);
                assertThrows(field, IllegalArgumentException.class, () -> validateEither(missing.toString()));
            }
            JsonObject extra = original.deepCopy();
            extra.getAsJsonObject("body").addProperty("MatchState", "must never be sent");
            assertThrows(IllegalArgumentException.class, () -> validateEither(extra.toString()));
        }
    }

    @Test public void clientCannotSubmitIdentityCoordinatesOrVictories() {
        for (String field : Arrays.asList("playerId", "x", "winner", "deliveredCount")) {
            JsonObject input = JsonParser.parseString(clientMessages().get("INPUT")).getAsJsonObject();
            input.getAsJsonObject("body").addProperty(field, "forged");
            assertThrows(IllegalArgumentException.class, () -> codec.decodeClient(input.toString()));
        }
    }

    @Test public void nullableDropIsRequiredAndItsValueMustBeASafeInteger() {
        JsonObject input = JsonParser.parseString(clientMessages().get("INPUT")).getAsJsonObject();
        for (JsonElement invalid : Arrays.asList(new JsonPrimitive("1"), new JsonPrimitive(-1),
                new JsonPrimitive(1.5), new JsonPrimitive(9007199254740992L))) {
            input.getAsJsonObject("body").add("dropId", invalid);
            assertThrows(IllegalArgumentException.class, () -> codec.decodeClient(input.toString()));
        }
        input.getAsJsonObject("body").addProperty("dropId", WireCodec.MAX_SAFE_INTEGER);
        codec.decodeClient(input.toString());
    }

    @Test public void stringBooleansUnknownEnumsAndNonFiniteNumbersAreRejected() {
        String ready = clientMessages().get("SET_READY").replace("true", "\"true\"");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeClient(ready));
        String role = clientMessages().get("SELECT_ROLE").replace("POLICE", "HACKER");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeClient(role));
        String leave = clientMessages().get("LEAVE_ROOM").replace("BACKGROUND", "DISCONNECT");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeClient(leave));
        String input = clientMessages().get("INPUT").replace("\"moveX\":1.0", "\"moveX\":1e400");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeClient(input));
    }

    @Test public void requestAndMatchCorrelationCannotBeSilentlyDropped() {
        for (String source : clientMessages().values()) {
            JsonObject original = JsonParser.parseString(source).getAsJsonObject();
            for (String field : Arrays.asList("requestId", "matchId")) {
                JsonObject changed = original.deepCopy();
                if (changed.get(field).isJsonNull()) changed.addProperty(field, "unexpected");
                else changed.add(field, JsonNull.INSTANCE);
                assertThrows(IllegalArgumentException.class, () -> codec.decodeClient(changed.toString()));
            }
        }
    }

    @Test public void wrongRequestActionAndResultAcknowledgementShapeAreRejected() {
        String unknown = codec.encode("ACK", 1, "r1", null, fields("action", "ATTACK"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeServer(unknown));
        String missingMatch = codec.encode("ACK", 1, "r1", null, fields("action", "RETURN_LOBBY"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeServer(missingMatch));
        codec.decodeServer(codec.encode("ACK", 1, "r1", "m1", fields("action", "RETURN_LOBBY")));
    }

    @Test public void realPlayerViewDtosRoundTripWithExplicitPrivateNulls() throws Exception {
        for (String name : Arrays.asList("police-hidden", "thief-interacting")) {
            WireCodec.Message m = codec.decodeServer(fixture("/protocol/" + name + ".json"));
            PlayerView view = codec.body(m, PlayerView.class);
            String encoded = codec.encode("SNAPSHOT", m.seq, null, m.matchId, view);
            JsonObject roundTrip = codec.decodeServer(encoded).body;
            assertEquals(m.body, roundTrip);
            if (view.role == Role.POLICE) {
                assertNull(view.opponent); assertNull(view.thief);
                assertTrue(roundTrip.get("thief").isJsonNull());
                assertFalse(roundTrip.has("groundMaterials"));
            } else assertEquals(12, view.thief.interaction.progressTicks);
        }
    }

    @Test public void policeCannotReceivePrivateThiefDataAndThiefRequiresIt() throws Exception {
        JsonObject police = JsonParser.parseString(fixture("/protocol/police-hidden.json")).getAsJsonObject();
        JsonObject thief = JsonParser.parseString(fixture("/protocol/thief-interacting.json")).getAsJsonObject();
        police.getAsJsonObject("body").add("thief", thief.getAsJsonObject("body").get("thief"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeServer(police.toString()));
        thief.getAsJsonObject("body").add("thief", JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> codec.decodeServer(thief.toString()));
    }

    @Test public void nestedPrivateTyposAndInvalidArrayElementsAreRejected() throws Exception {
        JsonObject thief = JsonParser.parseString(fixture("/protocol/thief-interacting.json")).getAsJsonObject();
        thief.getAsJsonObject("body").getAsJsonObject("thief").getAsJsonArray("groundMaterials").add(JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> codec.decodeServer(thief.toString()));
        JsonObject police = JsonParser.parseString(fixture("/protocol/police-hidden.json")).getAsJsonObject();
        police.getAsJsonObject("body").getAsJsonObject("self").addProperty("hiddenX", 10);
        assertThrows(IllegalArgumentException.class, () -> codec.decodeServer(police.toString()));
    }

    @Test public void otherProtocolVersionIsLeftForReceiverToReportMismatch() {
        WireCodec.Message m = codec.decodeClient("{\"v\":2,\"type\":\"FUTURE\",\"seq\":1,\"requestId\":null,\"matchId\":null,\"body\":{\"future\":true}}");
        assertEquals(2, m.v);
    }

    private void validateEither(String source) {
        String type = JsonParser.parseString(source).getAsJsonObject().get("type").getAsString();
        if (clientMessages().containsKey(type)) codec.decodeClient(source); else codec.decodeServer(source);
    }

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
