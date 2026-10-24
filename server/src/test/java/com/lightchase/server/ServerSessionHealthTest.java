package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.game.V1EngineFactory;
import com.lightchase.network.WireCodec;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.*;
import static org.junit.Assert.*;

/** Real WebSocket peers + controlled server time; A remains the actual V1 engine. */
public class ServerSessionHealthTest {
    private final DataFiles data = DataFiles.bundled();
    private final WireCodec codec = new WireCodec();
    private final AtomicLong clock = new AtomicLong();
    private final List<StepInput> steps = new CopyOnWriteArrayList<>();
    private final AtomicReference<MatchResult> result = new AtomicReference<>();
    private LobbyServer server;
    private Peer a, b;
    private int duration = 5400;
    private String match;

    private final class Peer extends WebSocketClient {
        final BlockingQueue<WireCodec.Message> incoming = new LinkedBlockingQueue<>();
        final List<RoomState> history = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile RoomState room;
        String playerId, closeReason;
        long seq, request;
        Peer() { super(URI.create("ws://127.0.0.1:" + server.getPort() + "/game")); }
        public void onOpen(ServerHandshake h) {}
        public void onMessage(String text) {
            WireCodec.Message message = codec.decodeServer(text);
            if ("ROOM_STATE".equals(message.type)) { room = codec.body(message, RoomState.class); history.add(room); }
            incoming.add(message);
        }
        public void onClose(int code, String reason, boolean remote) { closeReason = reason; closed.countDown(); }
        public void onError(Exception ex) {}
    }

    @Before public void start() throws Exception {
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data, clock::get, false, (map, rules, init) -> {
            RuleConfig testRules = new RuleConfig(rules.speed, rules.viewHeight, rules.viewAspect, rules.beamRange,
                    rules.beamAngleDeg, rules.markTicks, rules.invisibleTicks, rules.immuneTicks, rules.interactRange,
                    rules.interactTicks, rules.maxMarks, duration, rules.tickRate, rules.countdownTicks, rules.radius);
            MatchEngine real = new V1EngineFactory().create(map, testRules, init);
            return new MatchEngine() {
                public StepResult step(StepInput input) { steps.add(input); StepResult step = real.step(input); if (step.ended) result.set(step.result); return step; }
                public PlayerView viewFor(String id) { return real.viewFor(id); }
                public MatchResult result() { return real.result(); }
                public MatchResult abort() { MatchResult value = real.abort(); result.set(value); return value; }
            };
        });
        server.start(); server.awaitStart(); a = connect(); b = connect();
        control(a, "JOIN_ROOM", "join", null, fields("roomId", "TEST"));
        control(b, "JOIN_ROOM", "join", null, fields("roomId", "TEST")); beat(a); beat(b);
    }
    @After public void stop() throws Exception { a.close(); b.close(); server.shutdown(); }

    @Test public void exactSuspectBoundaryAndRecoveryBroadcastOncePerTransition() throws Exception {
        long initial = a.room.revision;
        clock.set(2999); sweep(); beat(a); assertHealth("CONNECTED"); assertEquals(initial, a.room.revision);
        clock.set(3000); sweep(); beat(a); assertHealth("SUSPECT"); assertEquals(initial + 1, a.room.revision);
        clock.set(3500); sweep(); sweep(); beat(a); assertEquals(initial + 1, a.room.revision);
        beat(b); beat(a); assertHealth("CONNECTED"); assertEquals(initial + 2, a.room.revision);
        sweep(); beat(a); assertEquals(initial + 2, a.room.revision);
    }
    @Test public void exactDisconnectBoundaryIsNotExtendedByHealthScanning() throws Exception {
        clock.set(4999); sweep(); beat(a); assertTrue(b.isOpen());
        clock.set(5000); sweep(); beat(a);
        assertTrue(b.closed.await(3, TimeUnit.SECONDS)); assertEquals("DISCONNECT", b.closeReason);
        assertEquals(1, a.room.players.size());
    }
    @Test public void staleEnvelopeDoesNotRefreshDeadline() throws Exception {
        clock.set(4000); b.send(codec.encode("HEARTBEAT", b.seq, null, null, fields("nonce", 44)));
        error(b, "STALE_MESSAGE"); assertExpiresAt5000();
    }
    @Test public void businessRejectedRoomSwitchDoesNotRefreshDeadline() throws Exception {
        clock.set(4000); send(b, "JOIN_ROOM", "switch", null, fields("roomId", "OTHER"));
        error(b, "INVALID_PHASE"); assertExpiresAt5000();
    }
    @Test public void conflictingRequestIdDoesNotRefreshDeadline() throws Exception {
        clock.set(4000); send(b, "JOIN_ROOM", "join", null, fields("roomId", "OTHER"));
        error(b, "REQUEST_ID_REUSED"); assertExpiresAt5000();
    }
    @Test public void cachedBusinessErrorDoesNotRefreshDeadline() throws Exception {
        send(b, "JOIN_ROOM", "bad", null, fields("roomId", "OTHER")); error(b, "INVALID_PHASE");
        clock.set(4000); send(b, "JOIN_ROOM", "bad", null, fields("roomId", "OTHER"));
        error(b, "INVALID_PHASE"); assertExpiresAt5000();
    }
    @Test public void malformedBodyDoesNotRefreshDeadline() throws Exception {
        clock.set(4000); send(b, "JOIN_ROOM", "bad", null, fields()); error(b, "BAD_MESSAGE"); assertExpiresAt5000();
    }
    @Test public void cachedSuccessfulJoinRestoresHealthAndDeadline() throws Exception {
        clock.set(3000); sweep(); beat(a); assertHealth("SUSPECT");
        long before = a.room.revision;
        clock.set(4000); control(b, "JOIN_ROOM", "join", null, fields("roomId", "TEST")); beat(a);
        assertHealth("CONNECTED"); assertEquals(before + 1, a.room.revision);
        clock.set(8999); sweep(); beat(a); assertTrue(b.isOpen());
        clock.set(9000); sweep(); assertTrue(b.closed.await(3, TimeUnit.SECONDS));
    }
    @Test public void wrongMatchInputDoesNotRefreshDeadlineOrRecoverRunningMember() throws Exception {
        running(); advance(7000, true);
        send(b, "INPUT", null, "wrong-match", input(1, 0, null)); error(b, "STALE_MATCH");
        advance(8000, true); assertDeparture(ResultReason.DISCONNECT, b.playerId);
    }
    @Test public void oldInputSeqDoesNotRefreshDeadline() throws Exception {
        running(); acceptedInput(1, 0, null); advance(7000, true);
        send(b, "INPUT", null, match, input(1, -1, null)); error(b, "STALE_INPUT");
        advance(8000, true); assertDeparture(ResultReason.DISCONNECT, b.playerId);
    }
    @Test public void semanticallyInvalidInputDoesNotRefreshDeadline() throws Exception {
        running(); advance(7000, true);
        send(b, "INPUT", null, match, input(1, 2, null)); error(b, "INVALID_INPUT");
        advance(8000, true); assertDeparture(ResultReason.DISCONNECT, b.playerId);
    }
    @Test public void newLegalFrameWithRepeatedDropRecoversAndKeepsContinuousInput() throws Exception {
        running(); acceptedInput(1, 0, 1L); advance(7000, true);
        acceptedInput(2, -1, 1L); beat(a); assertHealth("CONNECTED"); advance(8000, true);
        assertTrue(b.isOpen());
        List<PlayerCommand> commands = new ArrayList<>();
        for (StepInput step : steps) for (PlayerCommand command : step.commands) if (command.playerId.equals(b.playerId)) commands.add(command);
        assertEquals(1, commands.stream().filter(c -> c.dropId != null).count());
        assertTrue(commands.stream().anyMatch(c -> c.inputSeq == 2 && c.moveX == -1 && c.dropId == null));
    }
    @Test public void finishedResultAckCanRestoreHealthWithoutChangingPhase() throws Exception {
        duration = 1; running(); advance(3034, true); assertNotNull(result.get()); beat(a);
        clock.set(6000); sweep(); beat(a); assertHealth("SUSPECT");
        long before = a.room.revision;
        send(b, "RESULT_ACK", null, match, fields("resultId", result.get().resultId));
        // An invalid control is a barrier without independently restoring liveness.
        send(b, "SET_READY", "ack-barrier", null, fields("ready", false)); error(b, "INVALID_PHASE"); beat(a);
        assertHealth("CONNECTED"); assertEquals(RoomPhase.FINISHED, a.room.phase); assertEquals(before + 1, a.room.revision);
    }
    @Test public void countdownHealthChangeDoesNotPreventNormalStart() throws Exception {
        prepare(); clock.set(2999); beat(a); sweep();
        clock.set(3000); sweep(); beat(a);
        assertEquals(RoomPhase.RUNNING, a.room.phase);
        assertTrue(a.history.stream().anyMatch(r -> r.phase == RoomPhase.COUNTDOWN && health(r, b.playerId).equals("SUSPECT")));
    }
    @Test public void simultaneousTimeoutsAreOneBothLeftStep() throws Exception {
        running(); advance(8000, false);
        assertNotNull(result.get()); assertEquals(ResultReason.BOTH_LEFT, result.get().reason); assertNull(result.get().winner);
        StepInput terminal = steps.get(steps.size() - 1); assertEquals(2, terminal.departures.size());
    }
    @Test public void normalTimeoutWinHasPriorityOverBothDisconnectsInSameStep() throws Exception {
        duration = 150; running(); advance(8000, false);
        assertNotNull(result.get()); assertEquals(ResultReason.TIMEOUT, result.get().reason);
        assertEquals(Role.POLICE, result.get().winner); assertEquals(150, result.get().finalTick);
        assertEquals(2, steps.get(steps.size() - 1).departures.size());
    }

    private Peer connect() throws Exception {
        Peer peer = new Peer(); assertTrue(peer.connectBlocking(3, TimeUnit.SECONDS));
        send(peer, "HELLO", "hello", null, fields("clientBuild", "TEST_ONLY", "mapHash", data.mapHash, "configHash", data.configHash));
        peer.playerId = WireCodec.string(take(peer, "WELCOME").body, "playerId"); return peer;
    }
    private void prepare() throws Exception {
        control(a, "SELECT_ROLE", null, null, fields("role", "POLICE"));
        control(b, "SELECT_ROLE", null, null, fields("role", "THIEF"));
        control(a, "SET_READY", null, null, fields("ready", true));
        control(b, "SET_READY", null, null, fields("ready", true)); beat(a);
    }
    private void running() throws Exception {
        prepare(); clock.set(3000); sweep(); beat(a); beat(b); beat(a);
        assertEquals(RoomPhase.RUNNING, a.room.phase); match = a.room.matchId;
    }
    private void advance(long target, boolean keepA) throws Exception {
        while (clock.get() < target) {
            clock.set(Math.min(target, clock.get() + 33));
            if (keepA && clock.get() % 1000 < 33) beat(a);
            sweep();
        }
        if (keepA) beat(a);
    }
    private void acceptedInput(long inputSeq, double move, Long drop) throws Exception {
        send(b, "INPUT", null, match, input(inputSeq, move, drop));
        send(b, "SET_READY", "input-barrier-" + b.seq, null, fields("ready", false)); error(b, "INVALID_PHASE");
    }
    private Map<String, Object> input(long inputSeq, double move, Long drop) { return fields("inputSeq", inputSeq, "moveX", move, "moveY", 0, "interactHeld", false, "dropId", drop); }
    private void assertExpiresAt5000() throws Exception {
        beat(a); clock.set(4999); sweep(); beat(a); assertTrue(b.isOpen());
        clock.set(5000); sweep(); assertTrue("Invalid packets must not postpone expiry", b.closed.await(1, TimeUnit.SECONDS));
    }
    private void assertDeparture(ResultReason reason, String id) throws Exception {
        assertTrue("Expired transport", b.closed.await(1, TimeUnit.SECONDS));
        assertNotNull(result.get()); assertEquals(reason, result.get().reason); assertEquals(id, result.get().departedPlayerId);
    }
    private void assertHealth(String expected) { assertEquals(expected, health(a.room, b.playerId)); }
    private static String health(RoomState room, String id) { return room.players.stream().filter(p -> p.playerId.equals(id)).findFirst().get().connection; }
    private void sweep() throws Exception { server.checkTimeouts().get(3, TimeUnit.SECONDS); }
    private void beat(Peer peer) throws Exception { send(peer, "HEARTBEAT", null, null, fields("nonce", peer.seq + 1)); take(peer, "PONG"); }
    private void control(Peer peer, String type, String request, String matchId, Object body) throws Exception {
        send(peer, type, request == null ? "r-" + (++peer.request) : request, matchId, body); take(peer, "ACK");
    }
    private void send(Peer peer, String type, String request, String matchId, Object body) { peer.send(codec.encode(type, ++peer.seq, request, matchId, body)); }
    private WireCodec.Message take(Peer peer, String type) throws Exception {
        for (;;) { WireCodec.Message message = peer.incoming.poll(3, TimeUnit.SECONDS); assertNotNull("Expected " + type, message); if (type.equals(message.type)) return message; }
    }
    private void error(Peer peer, String code) throws Exception { assertEquals(code, WireCodec.string(take(peer, "ERROR").body, "code")); }
    private static Map<String, Object> fields(Object... pairs) { Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) map.put((String)pairs[i], pairs[i+1]); return map; }
}
