package com.lightchase.network;

import com.google.gson.*;
import com.lightchase.contracts.*;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Exercises the actual WebSocket receive path, including envelope/body correlation. */
public class GatewaySnapshotTest {
    private final WireCodec codec = new WireCodec();
    private final BlockingQueue<RoomState> rooms = new LinkedBlockingQueue<>();
    private final BlockingQueue<GatewayError> errors = new LinkedBlockingQueue<>();
    private final List<PlayerView> views = new CopyOnWriteArrayList<>();
    private ClientGatewayHealthTest.MockPeerServer peer;
    private WebSocketClientGateway gateway;
    private long seq = 20, revision = 1;

    @Before public void connect() throws Exception {
        peer = new ClientGatewayHealthTest.MockPeerServer();
        peer.start(); assertTrue(peer.started.await(5, TimeUnit.SECONDS));
        gateway = new WebSocketClientGateway(() -> 1000, 1000, 3000, 5000, 250, 0);
        gateway.connect(new ConnectionOptions("127.0.0.1", peer.getPort(), "TEST", "test",
                "map-hash-v1", "config-hash-v1"), new ClientGatewayHealthTest.TestListener() {
            @Override public void onRoom(RoomState room) { rooms.add(room); }
            @Override public void onView(PlayerView view) { views.add(view); }
            @Override public void onError(GatewayError error) { errors.add(error); }
        });
        WireCodec.Message hello = peer.pollMessage(5, TimeUnit.SECONDS);
        assertNotNull(hello);
        peer.sendWelcome(hello.requestId, "map-hash-v1", "config-hash-v1");
        WireCodec.Message join = peer.pollMessage(5, TimeUnit.SECONDS);
        assertNotNull(join); peer.sendAck(join.requestId, "JOIN_ROOM");
        room(RoomPhase.RUNNING, "m-001", Role.POLICE);
    }

    @After public void stop() throws Exception {
        if (gateway != null) gateway.close();
        if (peer != null) peer.stop(1000);
    }

    @Test public void higherEnvelopeSeqCannotRewindOrRepeatTick() throws Exception {
        snapshot("m-001", view(100)); snapshot("m-001", view(90));
        snapshot("m-001", view(100)); snapshot("m-001", view(101));
        room(RoomPhase.RUNNING, "m-001", Role.POLICE);
        assertTicks(100, 101);
    }

    @Test public void oldMatchCannotPolluteNextMatchAndTickZeroIsAccepted() throws Exception {
        snapshot("m-001", view(100));
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        room(RoomPhase.LOBBY, null, Role.POLICE);
        room(RoomPhase.COUNTDOWN, null, Role.THIEF);
        room(RoomPhase.RUNNING, "m-002", Role.THIEF);
        snapshot("m-001", view(200));
        JsonObject next = thiefView(0); next.addProperty("matchId", "m-002");
        snapshot("m-002", next);
        room(RoomPhase.RUNNING, "m-002", Role.THIEF);
        assertTicks(100, 0); assertEquals(Role.THIEF, views.get(1).role);
    }

    @Test public void firstSameTickTerminalViewIsAcceptedExactlyOnce() throws Exception {
        snapshot("m-001", view(100));
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        JsonObject terminal = view(100); terminal.addProperty("marks", 3);
        snapshot("m-001", terminal); snapshot("m-001", terminal);
        // FINISHED retransmission must not reopen the one terminal update allowance.
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        snapshot("m-001", terminal);
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        assertTicks(100, 100); assertEquals(3, views.get(1).marks);
    }

    @Test public void terminalViewMayAdvanceTickButCannotRewindIt() throws Exception {
        snapshot("m-001", view(100));
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        snapshot("m-001", view(99)); snapshot("m-001", view(101));
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        assertTicks(100, 101);
    }

    @Test public void resultDoesNotRequireATerminalSnapshot() throws Exception {
        snapshot("m-001", view(100));
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        peer.conn.send(codec.encode("GAME_OVER", ++seq, null, "m-001",
                new MatchResult("result-1", "m-001", Role.POLICE, ResultReason.CAPTURED, null, 100, 3, 0, 100)));
        WireCodec.Message ack = peer.pollMessage(5, TimeUnit.SECONDS);
        assertNotNull(ack); assertEquals("RESULT_ACK", ack.type);
        assertTicks(100); assertTrue(errors.isEmpty());
    }

    @Test public void completeResultStopsFurtherSnapshotsEvenWithoutFinishedRoomUpdate() throws Exception {
        snapshot("m-001", view(100));
        peer.conn.send(codec.encode("GAME_OVER", ++seq, null, "m-001",
                new MatchResult("result-1", "m-001", Role.POLICE, ResultReason.CAPTURED, null, 100, 3, 0, 100)));
        WireCodec.Message ack = peer.pollMessage(5, TimeUnit.SECONDS);
        assertNotNull(ack); assertEquals("RESULT_ACK", ack.type);
        snapshot("m-001", view(101));
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        snapshot("m-001", view(102));
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        assertTicks(100);
    }

    @Test public void envelopeAndBodyMatchMustAgree() throws Exception {
        reject(v -> v.addProperty("matchId", "other-match"));
    }
    @Test public void selfMustBeWelcomedPlayer() throws Exception {
        reject(v -> v.getAsJsonObject("self").addProperty("playerId", "p-2"));
    }
    @Test public void roleMustMatchRoomAssignment() throws Exception {
        JsonObject thief = thiefView(100); snapshot("m-001", thief); assertRejected();
    }
    @Test public void opponentMustBeOtherRoomMember() throws Exception {
        reject(v -> v.getAsJsonObject("opponent").addProperty("playerId", "stranger"));
    }
    @Test public void opponentRoleMustMatchRoomAssignment() throws Exception {
        reject(v -> v.getAsJsonObject("opponent").addProperty("role", "POLICE"));
    }
    @Test public void publicCounterRangeIsChecked() throws Exception {
        reject(v -> v.addProperty("marks", -1));
    }
    @Test public void remainingTicksRangeIsChecked() throws Exception {
        reject(v -> v.addProperty("remainingTicks", 5401));
    }
    @Test public void deliveredCountRangeIsChecked() throws Exception {
        reject(v -> v.addProperty("deliveredCount", 4));
    }
    @Test public void materialOriginsCannotDuplicateOrInventIds() throws Exception {
        reject(v -> v.getAsJsonArray("originStatus").get(1).getAsJsonObject().addProperty("materialId", "M1"));
    }
    @Test public void policeOpponentCannotCarryMaterial() throws Exception {
        room(RoomPhase.FINISHED, "m-001", Role.POLICE);
        room(RoomPhase.LOBBY, null, Role.THIEF);
        room(RoomPhase.COUNTDOWN, null, Role.THIEF);
        room(RoomPhase.RUNNING, "m-002", Role.THIEF);
        JsonObject thief = thiefView(0); thief.addProperty("matchId", "m-002");
        thief.getAsJsonObject("opponent").addProperty("carrying", true);
        snapshot("m-002", thief); assertRejected();
    }

    private void reject(Consumer<JsonObject> mutate) throws Exception {
        JsonObject body = view(100); mutate.accept(body); snapshot("m-001", body); assertRejected();
    }
    private void assertRejected() throws Exception {
        GatewayError error = errors.poll(2, TimeUnit.SECONDS);
        assertNotNull("Invalid snapshot must report a controlled protocol error", error);
        assertEquals("BAD_MESSAGE", error.code); assertTrue(views.isEmpty());
    }
    private void assertTicks(long... expected) {
        assertEquals(expected.length, views.size());
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], views.get(i).tick);
    }
    private void snapshot(String match, JsonObject body) {
        peer.conn.send(codec.encode("SNAPSHOT", ++seq, null, match, body));
    }
    private void room(RoomPhase phase, String match, Role role) throws Exception {
        Role other = role == Role.POLICE ? Role.THIEF : Role.POLICE;
        RoomState room = new RoomState("TEST", revision++, phase, "test-room", "map-hash-v1", "config-hash-v1",
                match, phase == RoomPhase.COUNTDOWN ? 3000 : 0, Arrays.asList(
                new RoomPlayer("p-1", role, phase != RoomPhase.LOBBY, "CONNECTED", false),
                new RoomPlayer("p-2", other, phase != RoomPhase.LOBBY, "CONNECTED", false)));
        peer.conn.send(codec.encode("ROOM_STATE", ++seq, null, null, room));
        assertNotNull("ROOM_STATE receive barrier", rooms.poll(2, TimeUnit.SECONDS));
    }
    private JsonObject view(long tick) throws Exception {
        JsonObject body = fixture("police-hidden.json"); body.addProperty("tick", tick);
        body.add("opponent", JsonParser.parseString("{\"playerId\":\"p-2\",\"role\":\"THIEF\",\"x\":8.5,\"y\":8.5,\"headingRad\":0.0,\"carrying\":false}"));
        return body;
    }
    private JsonObject thiefView(long tick) throws Exception {
        JsonObject body = fixture("thief-interacting.json"); body.addProperty("tick", tick);
        body.getAsJsonObject("self").addProperty("playerId", "p-1");
        body.getAsJsonObject("opponent").addProperty("playerId", "p-2");
        return body;
    }
    private JsonObject fixture(String name) throws Exception {
        try (Reader reader = new InputStreamReader(getClass().getResourceAsStream("/protocol/" + name), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("body");
        }
    }
}
