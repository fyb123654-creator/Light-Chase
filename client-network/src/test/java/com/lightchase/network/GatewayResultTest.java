package com.lightchase.network;

import com.google.gson.*;
import com.lightchase.contracts.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Complete-result idempotency and conflict tests through a deliberately faulty WebSocket peer. */
public class GatewayResultTest {
    private final WireCodec codec = new WireCodec();
    private final List<MatchResult> results = new CopyOnWriteArrayList<>();
    private final BlockingQueue<GatewayError> errors = new LinkedBlockingQueue<>();
    private final BlockingQueue<RoomState> rooms = new LinkedBlockingQueue<>();
    private final BlockingQueue<ControlStatus> controls = new LinkedBlockingQueue<>();
    private final GatewayListener listener = new ClientGatewayHealthTest.TestListener() {
        @Override public void onResult(MatchResult result) { results.add(result); }
        @Override public void onError(GatewayError error) { errors.add(error); }
        @Override public void onRoom(RoomState room) { rooms.add(room); }
        @Override public void onControl(ControlStatus status) {
            if (status.state == ControlStatus.State.SUCCEEDED) controls.add(status);
        }
    };
    private ClientGatewayHealthTest.MockPeerServer peer;
    private WebSocketClientGateway gateway;
    private long seq = 20, revision = 1;

    @Before public void connect() throws Exception {
        peer = new ClientGatewayHealthTest.MockPeerServer(); peer.start();
        assertTrue(peer.started.await(3, TimeUnit.SECONDS));
        gateway = new WebSocketClientGateway(() -> 1000, 1000, 3000, 5000, 250, 0);
        handshake(); room(RoomPhase.RUNNING, "m-1");
    }
    @After public void stop() throws Exception {
        if (gateway != null) gateway.close();
        if (peer != null) peer.stop(1000);
    }

    @Test public void identicalCompleteResultsAreAcknowledgedButOnlyDeliveredOnce() throws Exception {
        JsonObject body = body("m-1");
        send("m-1", body); send("m-1", body.deepCopy()); send("m-1", body.deepCopy());
        List<WireCodec.Message> acks = settle(); assertEquals(3, acks.size());
        for (WireCodec.Message ack : acks) assertAck(ack, "m-1", "result-first");
        assertFirst(body); assertTrue(errors.isEmpty());
    }
    @Test public void differentResultIdCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("resultId", "result-other")); }
    @Test public void differentBodyMatchIdCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("matchId", "other-match")); }
    @Test public void differentWinnerCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("winner", "THIEF")); }
    @Test public void differentReasonCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("reason", "BACKGROUND")); }
    @Test public void differentDepartedPlayerCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("departedPlayerId", "p-1")); }
    @Test public void differentElapsedTicksCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("elapsedTicks", 101)); }
    @Test public void differentMarksCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("marks", 2)); }
    @Test public void differentDeliveredCountCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("deliveredCount", 2)); }
    @Test public void differentFinalTickCannotReplaceFirst() throws Exception { conflict(v -> v.addProperty("finalTick", 101)); }

    @Test public void repeatedConflictsHaveBoundedDiagnosticsAndNeverAckDisputedResult() throws Exception {
        JsonObject first = body("m-1"); send("m-1", first); assertEquals(1, settle().size());
        for (int i = 0; i < 20; i++) {
            JsonObject changed = first.deepCopy(); changed.addProperty("resultId", "disputed-" + i); send("m-1", changed);
        }
        assertTrue("No disputed result may be acknowledged", settle().isEmpty());
        assertEquals(1, errors.size()); assertFirst(first);
        send("m-1", first); List<WireCodec.Message> acks = settle(); assertEquals(1, acks.size());
        assertAck(acks.get(0), "m-1", "result-first"); assertFirst(first);
    }
    @Test public void finishedRoomUpdateDoesNotClearTheResultLock() throws Exception {
        JsonObject first = body("m-1"); send("m-1", first); settle();
        room(RoomPhase.FINISHED, "m-1");
        JsonObject changed = first.deepCopy(); changed.addProperty("resultId", "result-other");
        send("m-1", changed); assertTrue(settle().isEmpty()); assertFirst(first); assertReturn("m-1", "result-first");
    }
    @Test public void nextMatchHasItsOwnResultLockEvenWithSameResultId() throws Exception {
        JsonObject first = body("m-1"); send("m-1", first); settle();
        room(RoomPhase.FINISHED, "m-1"); room(RoomPhase.LOBBY, null);
        room(RoomPhase.COUNTDOWN, null); room(RoomPhase.RUNNING, "m-2");
        send("m-1", first); assertTrue("Old envelope ignored in new match", settle().isEmpty());
        assertEquals(1, results.size());
        JsonObject second = body("m-2"); send("m-2", second);
        List<WireCodec.Message> acks = settle(); assertEquals(1, acks.size()); assertAck(acks.get(0), "m-2", "result-first");
        assertEquals(2, results.size()); assertEquals("m-2", results.get(1).matchId);
        JsonObject conflict = second.deepCopy(); conflict.addProperty("winner", "THIEF"); send("m-2", conflict);
        assertTrue(settle().isEmpty()); assertEquals(2, results.size()); assertReturn("m-2", "result-first");
    }
    @Test public void oldResultAfterLobbyDoesNotRestoreTheOldReturnReference() throws Exception {
        JsonObject first = body("m-1"); send("m-1", first); settle();
        room(RoomPhase.FINISHED, "m-1"); room(RoomPhase.LOBBY, null);
        send("m-1", first); assertTrue(settle().isEmpty()); assertEquals(1, results.size());
        gateway.returnToLobby(); GatewayError error = errors.poll(1, TimeUnit.SECONDS);
        assertNotNull(error); assertEquals("INVALID_PHASE", error.code); assertTrue(peer.messages.isEmpty());
    }
    @Test public void reconnectClearsThePreviousConnectionResultLock() throws Exception {
        JsonObject first = body("m-1"); send("m-1", first); settle();
        gateway.close(); revision = 1; handshake(); room(RoomPhase.RUNNING, "m-1");
        JsonObject newConnection = body("m-1"); newConnection.addProperty("winner", "THIEF");
        newConnection.addProperty("departedPlayerId", "p-1"); send("m-1", newConnection);
        assertEquals(1, settle().size()); assertEquals(2, results.size()); assertEquals(Role.THIEF, results.get(1).winner);
        assertTrue(errors.isEmpty());
    }
    @Test public void firstBodyMatchIdMustAgreeWithCurrentEnvelope() throws Exception { rejectFirst(v -> v.addProperty("matchId", "other-match")); }
    @Test public void firstResultMustContainEveryRequiredField() throws Exception { rejectFirst(v -> v.remove("marks")); }
    @Test public void firstResultCannotHaveAnEmptyId() throws Exception { rejectFirst(v -> v.addProperty("resultId", "")); }
    @Test public void firstResultElapsedRangeIsChecked() throws Exception { rejectFirst(v -> v.addProperty("elapsedTicks", -1)); }
    @Test public void firstResultMarksRangeIsChecked() throws Exception { rejectFirst(v -> v.addProperty("marks", 4)); }
    @Test public void firstResultDeliveredRangeIsChecked() throws Exception { rejectFirst(v -> v.addProperty("deliveredCount", -1)); }

    private void conflict(Consumer<JsonObject> mutate) throws Exception {
        JsonObject first = body("m-1"); send("m-1", first);
        List<WireCodec.Message> firstAcks = settle(); assertEquals(1, firstAcks.size()); assertAck(firstAcks.get(0), "m-1", "result-first");
        JsonObject changed = first.deepCopy(); mutate.accept(changed); send("m-1", changed);
        assertTrue("Conflicting contents must not be acknowledged", settle().isEmpty());
        assertFirst(first);
        GatewayError error = errors.poll(1, TimeUnit.SECONDS); assertNotNull("Controlled conflict diagnostic", error);
        assertEquals("RESULT_MISMATCH", error.code); assertTrue(error.recoverable);
        assertEquals("CONNECTED", gateway.getConnectionState()); assertReturn("m-1", "result-first");
    }
    private void rejectFirst(Consumer<JsonObject> mutate) throws Exception {
        JsonObject invalid = body("m-1"); mutate.accept(invalid); send("m-1", invalid);
        GatewayError error = errors.poll(1, TimeUnit.SECONDS); assertNotNull("Invalid first result", error);
        assertEquals("BAD_MESSAGE", error.code); assertTrue(results.isEmpty()); assertTrue(peer.messages.isEmpty());
    }
    private void assertFirst(JsonObject expected) {
        assertEquals(1, results.size());
        assertEquals(expected, codec.decode(codec.encode("GAME_OVER", 1, null, "m-1", results.get(0))).body);
    }
    private void assertReturn(String match, String resultId) throws Exception {
        gateway.returnToLobby(); WireCodec.Message request = peer.pollMessage(3, TimeUnit.SECONDS);
        assertNotNull(request); assertEquals("RETURN_LOBBY", request.type); assertEquals(match, request.matchId);
        assertEquals(resultId, WireCodec.string(request.body, "resultId"));
    }
    private void assertAck(WireCodec.Message ack, String match, String resultId) {
        assertEquals("RESULT_ACK", ack.type); assertEquals(match, ack.matchId);
        assertEquals(resultId, WireCodec.string(ack.body, "resultId"));
    }
    /** First inbound ACK settles server packets; the second outbound barrier drains resulting ACKs. */
    private List<WireCodec.Message> settle() throws Exception {
        List<WireCodec.Message> acks = new ArrayList<>(); barrier(acks); barrier(acks); return acks;
    }
    private void barrier(List<WireCodec.Message> acks) throws Exception {
        gateway.setReady(false); WireCodec.Message request;
        for (;;) {
            request = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull("Control receive barrier", request);
            if ("SET_READY".equals(request.type)) break;
            assertEquals("Unexpected outgoing message", "RESULT_ACK", request.type); acks.add(request);
        }
        peer.conn.send(codec.encode("ACK", ++seq, request.requestId, null, ProtocolContractTest.fields("action", "SET_READY")));
        for (;;) {
            ControlStatus status = controls.poll(3, TimeUnit.SECONDS); assertNotNull("Inbound ACK barrier", status);
            if (request.requestId.equals(status.requestId)) return;
        }
    }
    private void handshake() throws Exception {
        gateway.connect(new ConnectionOptions("127.0.0.1", peer.getPort(), "TEST", "test", "map-hash-v1", "config-hash-v1"), listener);
        WireCodec.Message hello = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull(hello);
        peer.sendWelcome(hello.requestId, "map-hash-v1", "config-hash-v1");
        WireCodec.Message join = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull(join);
        peer.conn.send(codec.encode("ACK", ++seq, join.requestId, null, ProtocolContractTest.fields("action", "JOIN_ROOM")));
    }
    private void room(RoomPhase phase, String match) throws Exception {
        boolean ready = phase != RoomPhase.LOBBY;
        RoomState state = new RoomState("TEST", revision++, phase, "test-room", "map-hash-v1", "config-hash-v1", match,
                phase == RoomPhase.COUNTDOWN ? 3000 : 0, Arrays.asList(
                new RoomPlayer("p-1", Role.POLICE, ready, "CONNECTED", false),
                new RoomPlayer("p-2", Role.THIEF, ready, "CONNECTED", false)));
        peer.conn.send(codec.encode("ROOM_STATE", ++seq, null, null, state));
        assertNotNull("ROOM_STATE receive barrier", rooms.poll(3, TimeUnit.SECONDS));
    }
    private void send(String match, JsonObject body) { peer.conn.send(codec.encode("GAME_OVER", ++seq, null, match, body)); }
    private JsonObject body(String match) {
        MatchResult result = new MatchResult("result-first", match, Role.POLICE, ResultReason.QUIT, "p-2", 100, 1, 1, 100);
        return codec.decode(codec.encode("GAME_OVER", 1, null, match, result)).body;
    }
}
