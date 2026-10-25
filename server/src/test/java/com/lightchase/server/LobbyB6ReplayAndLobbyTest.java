package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.network.WireCodec;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * B6 Phase Verification Tests:
 * 1. GAME_OVER periodic retransmission until RESULT_ACK, then stop.
 * 2. RESULT_ACK mismatch validation (RESULT_MISMATCH).
 * 3. RETURN_LOBBY flow (single and both players), idempotency, and returned flag in ROOM_STATE.
 * 4. Transition to RoomPhase.LOBBY after all players return (or 1 left returned), reset ready/returned, keep role.
 * 5. Rematch with SET_READY(true) generating brand new unique matchId.
 * 6. Stale match packets (INPUT, RETURN_LOBBY, RESULT_ACK) rejected with STALE_MATCH during new match.
 * 7. Outbound snapshot conflation (drop old, keep latest) during slow transport.
 * 8. Outbound control queue protection (max 64) closing slow client.
 * 9. Input (60/s) and control (10/s) rate limiting with recoverable=true and fatal=true after 3s sustained violation.
 */
public class LobbyB6ReplayAndLobbyTest {
    private final DataFiles data = DataFiles.bundled();
    private final WireCodec codec = new WireCodec();
    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger engineCreations = new AtomicInteger();
    private LobbyServer server;
    private TestEngine activeEngine;

    private static final class TestEngine implements MatchEngine {
        final MatchInit init;
        long tick;
        StepResult nextStep;
        MatchResult result;

        TestEngine(MatchInit init) {
            this.init = init;
        }

        @Override
        public StepResult step(StepInput input) {
            tick = input.tick;
            if (nextStep != null) {
                StepResult s = nextStep;
                nextStep = null;
                if (s.ended) result = s.result;
                return s;
            }
            return new StepResult(tick, tick == 1, false, null);
        }

        @Override
        public MatchResult result() {
            return result;
        }

        @Override
        public MatchResult abort() {
            if (result == null) {
                result = new MatchResult(init.matchId + ":result", init.matchId, null,
                        ResultReason.SERVER_ABORTED, null, (int) tick, 0, 0, tick);
            }
            return result;
        }

        @Override
        public PlayerView viewFor(String playerId) {
            boolean isPolice = init.policePlayerId.equals(playerId);
            return new PlayerView(
                    init.matchId,
                    tick,
                    0,
                    0,
                    isPolice ? Role.POLICE : Role.THIEF,
                    5400 - (int) tick,
                    0,
                    0,
                    false,
                    new ActorPose(playerId, isPolice ? 7.5 : 17.5, 4.5, 0),
                    null,
                    Arrays.asList(new OriginStatus("M1", false), new OriginStatus("M2", false), new OriginStatus("M3", false)),
                    isPolice ? null : new ThiefPrivate(null, Protection.NORMAL, 0,
                            Collections.emptyList(), Collections.emptyList(),
                            new InteractionView(null, null, 0, false, "NO_TARGET"))
            );
        }
    }

    interface TestOutboundProbe {
        int outboundQueueSize();
    }

    private static final class Peer {
        final HealthBroadcastFilter health = new HealthBroadcastFilter();
        final List<String> incoming = new ArrayList<>();
        final WebSocket socket;
        boolean open = true;
        boolean buffered = false;
        int queueSize = 0;
        long seq;

        Peer() {
            socket = (WebSocket) Proxy.newProxyInstance(
                    WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class, TestOutboundProbe.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isOpen": return open;
                            case "send":
                                if (!health.healthOnly((String) args[0])) incoming.add((String) args[0]);
                                return null;
                            case "close":
                                open = false;
                                return null;
                            case "hasBufferedData":
                                return buffered;
                            case "outboundQueueSize":
                                return queueSize;
                            case "equals": return proxy == args[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            case "toString": return "TEST_B6_PEER";
                            default:
                                try {
                                    return method.getDefaultValue();
                                } catch (Throwable t) {
                                    return null;
                                }
                        }
                    }
            );
        }
    }

    @Before
    public void setup() {
        clock.set(0);
        engineCreations.set(0);
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data, clock::get, false,
                (map, rules, init) -> {
                    engineCreations.incrementAndGet();
                    activeEngine = new TestEngine(init);
                    return activeEngine;
                });
    }

    private Peer open() throws Exception {
        Peer peer = new Peer();
        ClientHandshake handshake = (ClientHandshake) Proxy.newProxyInstance(
                ClientHandshake.class.getClassLoader(),
                new Class<?>[]{ClientHandshake.class},
                (proxy, method, args) -> "getResourceDescriptor".equals(method.getName()) ? "/game" : null
        );
        server.onOpen(peer.socket, handshake);
        sync();
        return peer;
    }

    private WireCodec.Message welcome(Peer peer) throws Exception {
        send(peer, "HELLO", "hello", null, fields(
                "clientBuild", "TEST_ONLY",
                "mapHash", data.mapHash,
                "configHash", data.configHash
        ));
        return take(peer, "WELCOME");
    }

    private RoomState join(Peer peer, String roomId) throws Exception {
        send(peer, "JOIN_ROOM", "join", null, fields("roomId", roomId));
        take(peer, "ACK");
        return room(peer);
    }

    private void sync() throws Exception {
        server.checkTimeouts().get();
    }

    private void send(Peer peer, String type, String requestId, String matchId, Object body) throws Exception {
        server.onMessage(peer.socket, codec.encode(type, ++peer.seq, requestId, matchId, body));
        sync();
    }

    private WireCodec.Message take(Peer peer, String type) {
        for (int i = 0; i < peer.incoming.size(); i++) {
            WireCodec.Message m = codec.decodeServer(peer.incoming.get(i));
            if (type.equals(m.type)) {
                peer.incoming.remove(i);
                return m;
            }
        }
        fail("Expected message type: " + type + ", but incoming was: " + peer.incoming);
        return null;
    }

    private WireCodec.Message error(Peer peer, String code, boolean fatal) {
        WireCodec.Message m = take(peer, "ERROR");
        assertEquals(code, WireCodec.string(m.body, "code"));
        assertEquals(fatal, WireCodec.bool(m.body, "fatal"));
        return m;
    }

    private RoomState room(Peer peer) {
        return codec.body(take(peer, "ROOM_STATE"), RoomState.class);
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Helper to setup two players in room "R1", start match, and step until FINISHED.
     */
    private String[] startAndFinishMatch(Peer a, Peer b) throws Exception {
        welcome(a);
        welcome(b);
        join(a, "R1");
        join(b, "R1");
        room(a); // peer b's join update

        send(a, "SELECT_ROLE", "sel-a", null, fields("role", "POLICE"));
        take(a, "ACK"); room(a); room(b);

        send(b, "SELECT_ROLE", "sel-b", null, fields("role", "THIEF"));
        take(b, "ACK"); room(a); room(b);

        send(a, "SET_READY", "ready-a", null, fields("ready", true));
        take(a, "ACK"); room(a); room(b);

        send(b, "SET_READY", "ready-b", null, fields("ready", true));
        take(b, "ACK");
        RoomState countdownState = room(a);
        assertEquals(RoomPhase.COUNTDOWN, countdownState.phase);
        room(b);

        // Advance past countdown
        clock.set(3000);
        send(b, "HEARTBEAT", null, null, fields("nonce", 1L));
        take(b, "PONG");
        sync();

        RoomState runningA = room(a);
        assertEquals(RoomPhase.RUNNING, runningA.phase);
        assertNotNull(runningA.matchId);
        String matchId = runningA.matchId;
        room(b);

        take(a, "SNAPSHOT"); // tick 0 view
        take(b, "SNAPSHOT");

        // End match by rule
        String resultId = matchId + ":result";
        MatchResult matchResult = new MatchResult(
                resultId, matchId, Role.POLICE, ResultReason.CAPTURED, null, 1, 3, 0, 1
        );
        activeEngine.nextStep = new StepResult(1, true, false, matchResult);

        // Advance 100ms to trigger step
        clock.set(3100);
        sync();

        RoomState finishedA = room(a);
        assertEquals(RoomPhase.FINISHED, finishedA.phase);
        room(b);

        take(a, "SNAPSHOT"); // optional terminal snapshot
        take(b, "SNAPSHOT");
        take(a, "GAME_OVER");
        take(b, "GAME_OVER");

        return new String[]{matchId, resultId};
    }

    // =========================================================================
    // 1. GAME_OVER 确认重发与 RESULT_ACK 消费
    // =========================================================================

    @Test
    public void gameOverPeriodicResendUntilResultAckReceived() throws Exception {
        Peer a = open();
        Peer b = open();
        String[] ids = startAndFinishMatch(a, b);
        String matchId = ids[0];
        String resultId = ids[1];

        // A sends RESULT_ACK
        send(a, "RESULT_ACK", null, matchId, fields("resultId", resultId));

        // Advance clock by 1000ms
        clock.addAndGet(1000);
        sync();

        // A should NOT receive GAME_OVER, B should receive retransmitted GAME_OVER
        assertTrue("A should not receive repeated GAME_OVER after RESULT_ACK", a.incoming.isEmpty());
        WireCodec.Message retransmittedB = take(b, "GAME_OVER");
        assertEquals(resultId, WireCodec.string(retransmittedB.body, "resultId"));

        // Advance clock by another 1000ms
        clock.addAndGet(1000);
        sync();

        assertTrue(a.incoming.isEmpty());
        WireCodec.Message retransmittedB2 = take(b, "GAME_OVER");
        assertEquals(resultId, WireCodec.string(retransmittedB2.body, "resultId"));

        // Now B sends RESULT_ACK
        send(b, "RESULT_ACK", null, matchId, fields("resultId", resultId));

        // Advance clock by another 1000ms
        clock.addAndGet(1000);
        sync();

        // Neither player receives GAME_OVER anymore
        assertTrue("A should have no pending incoming messages", a.incoming.isEmpty());
        assertTrue("B should have no pending incoming messages after RESULT_ACK", b.incoming.isEmpty());
    }

    @Test
    public void resultAckMismatchYieldsError() throws Exception {
        Peer a = open();
        Peer b = open();
        String[] ids = startAndFinishMatch(a, b);
        String matchId = ids[0];
        String resultId = ids[1];

        // Wrong resultId
        send(a, "RESULT_ACK", null, matchId, fields("resultId", "wrong-result-id"));
        error(a, "RESULT_MISMATCH", false);

        // Wrong matchId
        send(a, "RESULT_ACK", null, "wrong-match-id", fields("resultId", resultId));
        error(a, "RESULT_MISMATCH", false);

        // Valid RESULT_ACK succeeds without error
        send(a, "RESULT_ACK", null, matchId, fields("resultId", resultId));
        assertTrue(a.incoming.isEmpty());
    }

    // =========================================================================
    // 2. 返回大厅（RETURN_LOBBY）与新局重开（新 matchId）
    // =========================================================================

    @Test
    public void returnLobbySingleAndBothFlowAndIdempotency() throws Exception {
        Peer a = open();
        Peer b = open();
        String[] ids = startAndFinishMatch(a, b);
        String matchId = ids[0];
        String resultId = ids[1];

        // 1. Peer A calls RETURN_LOBBY
        send(a, "RETURN_LOBBY", "ret-a", matchId, fields("resultId", resultId));
        take(a, "ACK");

        // Both players receive updated ROOM_STATE (FINISHED): A returned=true, B returned=false
        RoomState updateA = room(a);
        assertEquals(RoomPhase.FINISHED, updateA.phase);
        assertTrue("Player A should be returned", updateA.players.get(0).returned);
        assertFalse("Player B should not be returned yet", updateA.players.get(1).returned);

        RoomState updateB = room(b);
        assertEquals(RoomPhase.FINISHED, updateB.phase);
        assertEquals(updateA.revision, updateB.revision);

        // 2. Duplicate RETURN_LOBBY from A is idempotent
        long revBeforeDup = updateA.revision;
        send(a, "RETURN_LOBBY", "ret-a-dup", matchId, fields("resultId", resultId));
        take(a, "ACK");
        RoomState dupStateA = room(a);
        assertEquals(revBeforeDup, dupStateA.revision); // No revision bump on duplicate
        assertTrue(dupStateA.players.get(0).returned);
        assertTrue("B should have received no broadcast on duplicate", b.incoming.isEmpty());

        // 3. Peer B calls RETURN_LOBBY -> all players returned -> transitions to LOBBY!
        send(b, "RETURN_LOBBY", "ret-b", matchId, fields("resultId", resultId));
        take(b, "ACK");

        RoomState lobbyA = room(a);
        assertEquals(RoomPhase.LOBBY, lobbyA.phase);
        assertNull("matchId must be cleared to null", lobbyA.matchId);
        for (RoomPlayer p : lobbyA.players) {
            assertFalse("ready must be reset to false", p.ready);
            assertFalse("returned must be reset to false", p.returned);
            assertNotNull("role must be preserved", p.role);
        }
        assertEquals(Role.POLICE, lobbyA.players.get(0).role);
        assertEquals(Role.THIEF, lobbyA.players.get(1).role);

        RoomState lobbyB = room(b);
        assertEquals(RoomPhase.LOBBY, lobbyB.phase);
        assertEquals(lobbyA.revision, lobbyB.revision);
    }

    @Test
    public void singleRemainingPlayerReturnedTransitionsToLobby() throws Exception {
        Peer a = open();
        Peer b = open();
        String[] ids = startAndFinishMatch(a, b);
        String matchId = ids[0];
        String resultId = ids[1];

        // B leaves the room during FINISHED
        send(b, "LEAVE_ROOM", "leave-b", null, fields("reason", "QUIT"));
        take(b, "ACK");
        assertFalse(b.open);

        // A receives updated membership (1 player remaining, FINISHED)
        RoomState remainingA = room(a);
        assertEquals(RoomPhase.FINISHED, remainingA.phase);
        assertEquals(1, remainingA.players.size());
        assertFalse(remainingA.players.get(0).returned);

        // A returns to lobby -> since A is the only active player, transitions to LOBBY!
        send(a, "RETURN_LOBBY", "ret-a", matchId, fields("resultId", resultId));
        take(a, "ACK");

        RoomState lobbyA = room(a);
        assertEquals(RoomPhase.LOBBY, lobbyA.phase);
        assertNull(lobbyA.matchId);
        assertFalse(lobbyA.players.get(0).returned);
        assertFalse(lobbyA.players.get(0).ready);
        assertEquals(Role.POLICE, lobbyA.players.get(0).role);
    }

    @Test
    public void playerReturnedBeforePeerLeavesTransitionsToLobby() throws Exception {
        Peer a = open();
        Peer b = open();
        String[] ids = startAndFinishMatch(a, b);
        String matchId = ids[0];
        String resultId = ids[1];

        // A returns first
        send(a, "RETURN_LOBBY", "ret-a", matchId, fields("resultId", resultId));
        take(a, "ACK");
        room(a); room(b);

        // B closes / leaves
        send(b, "LEAVE_ROOM", "leave-b", null, fields("reason", "QUIT"));
        take(b, "ACK");

        // A is already returned, so room transitions immediately to LOBBY!
        RoomState lobbyA = room(a);
        assertEquals(RoomPhase.LOBBY, lobbyA.phase);
        assertNull(lobbyA.matchId);
        assertFalse(lobbyA.players.get(0).returned);
        assertFalse(lobbyA.players.get(0).ready);
    }

    @Test
    public void rematchGeneratesNewUniqueMatchIdAndRetainsRoles() throws Exception {
        Peer a = open();
        Peer b = open();
        String[] ids = startAndFinishMatch(a, b);
        String oldMatchId = ids[0];
        String resultId = ids[1];

        // Both return to lobby
        send(a, "RETURN_LOBBY", "ret-a", oldMatchId, fields("resultId", resultId));
        take(a, "ACK"); room(a); room(b);

        send(b, "RETURN_LOBBY", "ret-b", oldMatchId, fields("resultId", resultId));
        take(b, "ACK"); room(a); room(b);

        // Both ready up for rematch without re-selecting roles
        clock.addAndGet(100);
        send(a, "SET_READY", "ready-a2", null, fields("ready", true));
        take(a, "ACK"); room(a); room(b);

        clock.addAndGet(100);
        send(b, "SET_READY", "ready-b2", null, fields("ready", true));
        take(b, "ACK");
        RoomState countdownState = room(a);
        assertEquals(RoomPhase.COUNTDOWN, countdownState.phase);
        room(b);

        // Advance 3000ms for countdown
        clock.addAndGet(3000);
        sync();

        RoomState runningA = room(a);
        assertEquals(RoomPhase.RUNNING, runningA.phase);
        String newMatchId = runningA.matchId;
        assertNotNull(newMatchId);
        assertNotEquals("New match must have a unique matchId", oldMatchId, newMatchId);
        room(b);

        WireCodec.Message viewA = take(a, "SNAPSHOT");
        assertEquals(newMatchId, viewA.matchId);
        take(b, "SNAPSHOT");
    }

    @Test
    public void staleMatchPacketsRejectedDuringNewMatch() throws Exception {
        Peer a = open();
        Peer b = open();
        String[] ids = startAndFinishMatch(a, b);
        String oldMatchId = ids[0];
        String resultId = ids[1];

        // Both return
        send(a, "RETURN_LOBBY", "ret-a", oldMatchId, fields("resultId", resultId));
        take(a, "ACK"); room(a); room(b);
        send(b, "RETURN_LOBBY", "ret-b", oldMatchId, fields("resultId", resultId));
        take(b, "ACK"); room(a); room(b);

        // Rematch
        clock.addAndGet(100);
        send(a, "SET_READY", "ready-a2", null, fields("ready", true));
        take(a, "ACK"); room(a); room(b);
        clock.addAndGet(100);
        send(b, "SET_READY", "ready-b2", null, fields("ready", true));
        take(b, "ACK"); room(a); room(b);

        clock.addAndGet(3000);
        sync();
        RoomState runningA = room(a);
        String newMatchId = runningA.matchId;
        room(b);
        take(a, "SNAPSHOT"); take(b, "SNAPSHOT");

        // 1. INPUT with old matchId -> STALE_MATCH
        send(a, "INPUT", null, oldMatchId, fields(
                "inputSeq", 1, "moveX", 0.5, "moveY", 0.0, "interactHeld", false, "dropId", null
        ));
        error(a, "STALE_MATCH", false);

        // 2. RETURN_LOBBY with old matchId -> STALE_MATCH
        send(a, "RETURN_LOBBY", "stale-ret", oldMatchId, fields("resultId", resultId));
        error(a, "STALE_MATCH", false);

        // 3. RESULT_ACK with old matchId -> STALE_MATCH
        send(a, "RESULT_ACK", null, oldMatchId, fields("resultId", resultId));
        error(a, "STALE_MATCH", false);

        // Valid input for current matchId succeeds
        send(a, "INPUT", null, newMatchId, fields(
                "inputSeq", 1, "moveX", 0.0, "moveY", 0.0, "interactHeld", false, "dropId", null
        ));
        assertTrue(a.incoming.isEmpty());
    }

    @Test
    public void returnLobbyIllegalPhaseOrMismatch() throws Exception {
        Peer a = open();
        welcome(a);
        join(a, "R1");

        // In LOBBY phase -> INVALID_PHASE
        send(a, "RETURN_LOBBY", "ret-lobby", "any-match", fields("resultId", "any-result"));
        error(a, "INVALID_PHASE", false);
    }

    // =========================================================================
    // 3. 背压（Backpressure）、快照丢旧保新与限流（Rate Limiting）
    // =========================================================================

    @Test
    public void outboundSnapshotConflationDropsOldFrames() throws Exception {
        Peer a = open();
        Peer b = open();
        welcome(a);
        welcome(b);
        join(a, "R1");
        join(b, "R1");
        room(a);

        send(a, "SELECT_ROLE", "sel-a", null, fields("role", "POLICE"));
        take(a, "ACK"); room(a); room(b);
        send(b, "SELECT_ROLE", "sel-b", null, fields("role", "THIEF"));
        take(b, "ACK"); room(a); room(b);
        send(a, "SET_READY", "ready-a", null, fields("ready", true));
        take(a, "ACK"); room(a); room(b);
        send(b, "SET_READY", "ready-b", null, fields("ready", true));
        take(b, "ACK"); room(a); room(b);

        clock.set(3000);
        sync();
        RoomState runningA = room(a);
        room(b);
        take(a, "SNAPSHOT"); take(b, "SNAPSHOT"); // tick 0

        // Simulate transport congestion on Peer A
        a.buffered = true;

        // Step engine multiple times (ticks 2, 4, 6 produce snapshots)
        clock.set(3066); sync(); // tick 2
        clock.set(3133); sync(); // tick 4
        clock.set(3200); sync(); // tick 6

        // While buffered, no new snapshots were transmitted to peer A's incoming queue
        assertTrue("No new snapshots should be queued directly to slow socket", a.incoming.isEmpty());

        // Congestion clears
        a.buffered = false;
        sync(); // Triggers drainOutbound

        // Peer A receives ONLY ONE snapshot, which must be the latest (tick 6)!
        assertEquals("Peer A must receive exactly one conflated snapshot", 1, a.incoming.size());
        WireCodec.Message snap = take(a, "SNAPSHOT");
        PlayerView view = codec.body(snap, PlayerView.class);
        assertEquals("Conflated snapshot must be the latest tick", 6, view.tick);
    }

    @Test
    public void outboundControlQueueBoundedAt64AndClosesSlowClient() throws Exception {
        Peer a = open();
        welcome(a);
        join(a, "R1");

        // Simulate slow client with 64 queued buffers
        a.queueSize = 64;

        // Sending one more control message overflows the queue
        send(a, "HEARTBEAT", null, null, fields("nonce", 12345L));

        assertFalse("Slow client connection must be closed when outbound queue overflows", a.open);
    }

    @Test
    public void inputRateLimitExceededRejectsRateLimitedAndClosesAfter3Seconds() throws Exception {
        Peer a = open();
        Peer b = open();
        String[] ids = startAndFinishMatch(a, b);
        String oldMatchId = ids[0];
        String resultId = ids[1];

        // Return and start new match
        send(a, "RETURN_LOBBY", "ret-a", oldMatchId, fields("resultId", resultId));
        take(a, "ACK"); room(a); room(b);
        send(b, "RETURN_LOBBY", "ret-b", oldMatchId, fields("resultId", resultId));
        take(b, "ACK"); room(a); room(b);

        clock.addAndGet(100);
        send(a, "SET_READY", "ready-a2", null, fields("ready", true));
        take(a, "ACK"); room(a); room(b);
        clock.addAndGet(100);
        send(b, "SET_READY", "ready-b2", null, fields("ready", true));
        take(b, "ACK"); room(a); room(b);

        clock.addAndGet(3000);
        sync();
        RoomState runningA = room(a);
        String matchId = runningA.matchId;
        room(b);
        take(a, "SNAPSHOT"); take(b, "SNAPSHOT");

        // Send 60 inputs within 1 second -> all succeed
        for (int i = 1; i <= 60; i++) {
            send(a, "INPUT", null, matchId, fields(
                    "inputSeq", i, "moveX", 0.0, "moveY", 0.0, "interactHeld", false, "dropId", null
            ));
        }
        assertTrue("First 60 inputs must be accepted", a.incoming.isEmpty());

        // 61st input within the same second -> rejected with RATE_LIMITED (recoverable=true, fatal=false)
        send(a, "INPUT", null, matchId, fields(
                "inputSeq", 61, "moveX", 0.0, "moveY", 0.0, "interactHeld", false, "dropId", null
        ));
        error(a, "RATE_LIMITED", false);
        assertTrue("Connection remains open on first violation", a.open);

        // Advance clock by 1000ms each second and spam > 60 inputs to sustain violation
        int seq = 62;
        for (int sec = 1; sec <= 3; sec++) {
            clock.addAndGet(1000);
            for (int i = 1; i <= 60; i++) {
                send(a, "INPUT", null, matchId, fields(
                        "inputSeq", seq++, "moveX", 0.0, "moveY", 0.0, "interactHeld", false, "dropId", null
                ));
            }
            send(a, "INPUT", null, matchId, fields(
                    "inputSeq", seq++, "moveX", 0.0, "moveY", 0.0, "interactHeld", false, "dropId", null
            ));
            if (sec < 3) {
                error(a, "RATE_LIMITED", false);
                assertTrue(a.open);
            } else {
                error(a, "RATE_LIMITED", true);
                assertFalse("Connection must be closed after 3s sustained rate limit violation", a.open);
            }
        }
    }

    @Test
    public void controlRateLimitExceededRejectsRateLimitedAndRecovers() throws Exception {
        Peer a = open();
        welcome(a);
        join(a, "R1");

        // Advance by 1000ms so we are in a fresh 1-second window after hello and join
        clock.addAndGet(1000);

        // Send 10 control requests (SELECT_ROLE) within 1 second -> all succeed
        for (int i = 1; i <= 10; i++) {
            send(a, "SELECT_ROLE", "sel-" + i, null, fields("role", "POLICE"));
            take(a, "ACK");
            room(a);
        }

        // 11th control request in same second -> RATE_LIMITED (fatal=false)
        send(a, "SELECT_ROLE", "sel-11", null, fields("role", "POLICE"));
        error(a, "RATE_LIMITED", false);
        assertTrue(a.open);

        // Advance clock by 1000ms (next second window)
        clock.addAndGet(1000);

        // Requests under the limit now succeed again
        send(a, "SELECT_ROLE", "sel-12", null, fields("role", "POLICE"));
        take(a, "ACK");
        room(a);
    }
}
