package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.game.V1EngineFactory;
import com.lightchase.network.WireCodec;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.*;
import static org.junit.Assert.*;

/** Real WebSockets feed the real V1 engine; wrappers inject only transport failure conditions. */
public class RunningMatchTest {
    private final DataFiles data = DataFiles.bundled();
    private final WireCodec codec = new WireCodec();
    private final AtomicLong clock = new AtomicLong();
    private final List<Client> clients = new ArrayList<>();
    private final List<StepInput> steps = new ArrayList<>();
    private final Set<String> threads = new HashSet<>();
    private LobbyServer server;
    private Client police, thief;
    private String matchId;
    private boolean throwStep, throwAbort, throwViewAfterStart, hideAtThirdTick;
    private int aborts;

    private static final class Client extends WebSocketClient {
        final HealthBroadcastFilter health = new HealthBroadcastFilter();
        final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        long seq;
        String playerId;
        Client(int port) { super(URI.create("ws://127.0.0.1:" + port + "/game")); }
        public void onOpen(ServerHandshake handshake) {}
        public void onMessage(String text) { if (!health.healthOnly(text)) incoming.add(text); }
        public void onClose(int code, String reason, boolean remote) {}
        public void onError(Exception ex) {}
    }

    @Before public void start() throws Exception {
        MatchEngineFactory factory = (map, rules, init) -> {
            threads.add(Thread.currentThread().getName());
            MatchEngine real = new V1EngineFactory().create(map, rules, init);
            return new MatchEngine() {
                long tick;
                public StepResult step(StepInput input) {
                    threads.add(Thread.currentThread().getName()); steps.add(input);
                    if (throwStep) throw new IllegalStateException("TEST_ONLY step failure");
                    StepResult result = real.step(input); tick = result.tick;
                    return new StepResult(result.tick, result.visibilityChanged || hideAtThirdTick && tick == 3,
                            result.ended, result.result);
                }
                public PlayerView viewFor(String id) {
                    threads.add(Thread.currentThread().getName());
                    if (throwViewAfterStart && tick > 0) throw new IllegalStateException("TEST_ONLY projection failure");
                    PlayerView view = real.viewFor(id);
                    if (!hideAtThirdTick || tick >= 3) return view;
                    // A scripted visible→hidden transition exercises odd-tick immediate delivery.
                    return new PlayerView(view.matchId, view.tick, 0, 0, view.role, view.remainingTicks,
                            view.marks, view.deliveredCount, view.exitsOpen, view.self,
                            new VisibleOpponent(id.equals(init.policePlayerId) ? init.thiefPlayerId : init.policePlayerId,
                                    view.role == Role.POLICE ? Role.THIEF : Role.POLICE, 10, 8, 0, false),
                            view.originStatus, view.thief);
                }
                public MatchResult result() { return real.result(); }
                public MatchResult abort() {
                    threads.add(Thread.currentThread().getName()); aborts++;
                    if (throwAbort) throw new IllegalStateException("TEST_ONLY abort failure");
                    return real.abort();
                }
            };
        };
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data, clock::get, false, factory);
        server.start(); server.awaitStart(); police = connect(); thief = connect();
        control(police, "JOIN_ROOM", fields("roomId", "RUN"));
        control(thief, "JOIN_ROOM", fields("roomId", "RUN"));
        control(police, "SELECT_ROLE", fields("role", "POLICE"));
        control(thief, "SELECT_ROLE", fields("role", "THIEF"));
        control(police, "SET_READY", fields("ready", true));
        control(thief, "SET_READY", fields("ready", true));
        drain(police); drain(thief);
        clock.set(3000); pulse();
        List<WireCodec.Message> initial = drain(police); drain(thief);
        assertEquals("ROOM_STATE", initial.get(0).type);
        RoomState state = codec.body(initial.get(0), RoomState.class);
        assertEquals(RoomPhase.RUNNING, state.phase); matchId = state.matchId;
        assertEquals("SNAPSHOT", initial.get(1).type);
        assertEquals(0, codec.body(initial.get(1), PlayerView.class).tick);
    }

    @After public void stop() throws Exception {
        for (Client client : clients) client.close(); server.shutdown();
    }

    @Test public void inputsMoveOnlyOncePerStepAndViewsRunAt15HzPlusFirstStep() throws Exception {
        for (int i = 1; i <= 5; i++) input(police, i, 1, 0, false, null);
        input(thief, 1, -1, 0, false, null); drain(police); drain(thief);
        atTick(1);
        PlayerView p1 = view(drain(police)), t1 = view(drain(thief));
        assertEquals(3.6, p1.self.x, 1e-6); assertEquals(20.4, t1.self.x, 1e-6);
        assertEquals(5, p1.ackInputSeq); assertEquals(1, t1.ackInputSeq);
        assertEquals(Role.POLICE, p1.role); assertEquals(police.playerId, p1.self.playerId);
        assertNull(p1.thief); assertNull(p1.opponent); assertNotNull(t1.thief);
        assertEquals(3, t1.thief.groundMaterials.size());
        assertEquals(6, steps.get(0).commands.size());
        atTick(2); assertEquals(2, view(drain(police)).tick); drain(thief);
        atTick(3); assertTrue(drain(police).isEmpty()); assertTrue(drain(thief).isEmpty());
        atTick(4); assertEquals(4, view(drain(police)).tick); drain(thief);
        assertEquals(Collections.singleton("lightchase-lobby-logic"), threads);
    }

    @Test public void fifoKeepsReleasePressDropAndDeduplicatesOnlyTheDropPulse() throws Exception {
        input(thief, 1, 0, 0, true, 1L);
        input(thief, 2, 0, 0, false, 1L);
        input(thief, 3, 0, 0, true, 2L);
        drain(thief); atTick(1); drain(police); drain(thief);
        List<PlayerCommand> batch = steps.get(0).commands;
        assertEquals(3, batch.size()); assertTrue(batch.get(0).interactHeld);
        assertFalse(batch.get(1).interactHeld); assertNull(batch.get(1).dropId);
        assertTrue(batch.get(2).interactHeld); assertEquals(Long.valueOf(2), batch.get(2).dropId);
    }

    @Test public void catchUpKeepsDebtAndConsumesNetworkInputOnlyOnce() throws Exception {
        input(police, 1, 1, 0, false, null); drain(police);
        clock.set(3200); pulse(); assertEquals(5, steps.size());
        pulse(); assertEquals(6, steps.size());
        assertEquals(1, steps.get(0).commands.size());
        for (int i = 1; i < 6; i++) assertTrue(steps.get(i).commands.isEmpty());
        List<WireCodec.Message> messages = drain(police); drain(thief);
        PlayerView last = codec.body(messages.get(messages.size() - 1), PlayerView.class);
        assertEquals(6, last.tick); assertEquals(4.1, last.self.x, 1e-6);
    }

    @Test public void repeatedStaleInputErrorsAreThrottledWithoutChangingAcceptedMovement() throws Exception {
        input(police, 1, 1, 0, false, null); drain(police);
        input(police, 1, -1, 0, false, null); assertError(drain(police), "STALE_INPUT");
        input(police, 1, -1, 0, false, null); assertTrue(drain(police).isEmpty());
        atTick(1); assertEquals(3.6, view(drain(police)).self.x, 1e-6); drain(thief);
    }

    @Test public void actualEngineCollisionStopsTheNetworkDrivenPlayerAtTheWall() throws Exception {
        for (int tick = 1; tick <= 40; tick++) {
            input(police, tick, -1, 0, false, null); drain(police); atTick(tick);
        }
        List<WireCodec.Message> messages = drain(police); drain(thief);
        PlayerView last = codec.body(messages.get(messages.size() - 1), PlayerView.class);
        assertTrue(last.self.x >= 1.25); assertTrue(last.self.x < 1.4);
        assertEquals(40, last.tick); assertEquals(40, last.ackInputSeq);
    }

    @Test public void invalidAndStaleInputsDoNotRenewLeaseAndHeartbeatCannotKeepMoving() throws Exception {
        input(police, 1, 1, 0, false, null); drain(police); atTick(1); drain(police); drain(thief);
        clock.set(3249);
        input(police, 999, 2, 0, false, null); assertError(drain(police), "INVALID_INPUT");
        input(police, 999, 0, 0, true, null); assertError(drain(police), "INVALID_ACTION");
        input(police, 1, 0, 0, false, null); assertError(drain(police), "STALE_INPUT");
        // Heartbeats refreshed packet liveness, but leave the t=3000 input lease untouched.
        pulse(); pulse(); drain(police); drain(thief);
        atTick(8); PlayerView stopped = view(drain(police)); drain(thief);
        assertEquals(4.2, stopped.self.x, 1e-6); assertEquals(1, stopped.ackInputSeq);
        PlayerCommand release = steps.get(7).commands.get(0);
        assertEquals(1, release.inputSeq); assertEquals(0, release.moveX, 0);
        assertNull(release.dropId); assertFalse(release.interactHeld);
        atTick(10); assertEquals(4.2, view(drain(police)).self.x, 1e-6); drain(thief);
    }

    @Test public void wrongMatchAndZeroSequenceCannotPoisonCurrentInput() throws Exception {
        send(police, "INPUT", null, "old", fields("inputSeq", 500, "moveX", 1, "moveY", 0,
                "interactHeld", false, "dropId", null)); assertError(drain(police), "STALE_MATCH");
        input(police, 0, 1, 0, false, null); assertError(drain(police), "INVALID_INPUT");
        input(police, 1, 1, 0, false, null); assertTrue(drain(police).isEmpty());
        atTick(1); assertEquals(1, view(drain(police)).ackInputSeq); drain(thief);
    }

    @Test public void visibilityLossSendsAnImmediateOddTickRoleView() throws Exception {
        hideAtThirdTick = true;
        atTick(1); assertNotNull(view(drain(police)).opponent); drain(thief);
        atTick(2); assertNotNull(view(drain(police)).opponent); drain(thief);
        atTick(3); PlayerView hidden = view(drain(police)); drain(thief);
        assertEquals(3, hidden.tick); assertNull(hidden.opponent); assertNull(hidden.thief);
    }

    @Test public void quitCommitsEngineResultBeforeAckAndSocketRemoval() throws Exception {
        send(police, "LEAVE_ROOM", "leave", null, fields("reason", "QUIT"));
        assertTrue(drain(police).isEmpty()); // No early ACK or membership deletion.
        atTick(1);
        List<WireCodec.Message> messages = until(police, "ACK");
        int over = index(messages, "GAME_OVER"), ack = index(messages, "ACK");
        assertTrue(over >= 0 && ack > over);
        MatchResult result = codec.body(messages.get(over), MatchResult.class);
        assertEquals(ResultReason.QUIT, result.reason); assertEquals(Role.THIEF, result.winner);
        assertEquals(police.playerId, result.departedPlayerId);
        List<WireCodec.Message> peer = drain(thief);
        assertEquals(result.resultId, result(peer).resultId);
        assertEquals(RoomPhase.FINISHED, codec.body(peer.get(0), RoomState.class).phase);
        assertEquals(1, steps.get(0).departures.size());
        assertEquals(police.playerId, steps.get(0).departures.get(0).playerId);
        atTick(5); assertEquals(1, steps.size()); assertTrue(drain(thief).isEmpty());
    }

    @Test public void backgroundThenSocketClosePreservesBackgroundReason() throws Exception {
        send(thief, "LEAVE_ROOM", "background", null, fields("reason", "BACKGROUND")); drain(thief);
        thief.closeBlocking(); atTick(1);
        MatchResult result = result(drain(police));
        assertEquals(ResultReason.BACKGROUND, result.reason); assertEquals(Role.POLICE, result.winner);
        assertEquals(1, steps.get(0).departures.size());
    }

    @Test public void twoDeparturesInOneStepAreBothLeftAndDuplicateRequestWaitsForOneAck() throws Exception {
        send(police, "LEAVE_ROOM", "leave", null, fields("reason", "QUIT"));
        send(police, "LEAVE_ROOM", "leave", null, fields("reason", "QUIT")); drain(police);
        send(thief, "LEAVE_ROOM", "leave", null, fields("reason", "BACKGROUND")); drain(thief);
        atTick(1);
        List<WireCodec.Message> a = until(police, "ACK"), b = until(thief, "ACK");
        assertEquals(ResultReason.BOTH_LEFT, result(a).reason); assertNull(result(a).winner);
        assertEquals(result(a).resultId, result(b).resultId); assertEquals(2, steps.get(0).departures.size());
        assertEquals(1, a.stream().filter(m -> m.type.equals("ACK")).count());
    }

    @Test public void abruptDisconnectIsDeliveredToTheEngineBeforeMembershipIsRemoved() throws Exception {
        thief.closeBlocking(); atTick(1);
        MatchResult result = result(drain(police));
        assertEquals(ResultReason.DISCONNECT, result.reason); assertEquals(thief.playerId, result.departedPlayerId);
        assertEquals(Role.POLICE, result.winner);
    }

    @Test public void timeoutOfOneSocketBecomesARealEngineDepartureWhilePeerHeartbeatStaysHealthy() throws Exception {
        for (int tick = 1; tick < 150; tick++) {
            clock.set(3000 + (tick * 1000L + 29) / 30);
            if (tick % 30 == 0) drain(police); // thief sends no packet after t=3000.
            pulse();
        }
        drain(police); clock.set(8000); drain(police); pulse();
        MatchResult result = result(drain(police));
        assertEquals(ResultReason.DISCONNECT, result.reason); assertEquals(thief.playerId, result.departedPlayerId);
        assertEquals(150, result.finalTick); assertEquals(Role.POLICE, result.winner);
    }

    @Test public void accumulatedLagUsesEngineAbortAndFinishesOnlyOnce() throws Exception {
        clock.set(4001); pulse();
        MatchResult a = result(drain(police)), b = result(drain(thief));
        assertEquals(ResultReason.SERVER_ABORTED, a.reason); assertNull(a.winner);
        assertEquals(a.resultId, b.resultId); assertEquals(1, aborts); assertTrue(steps.isEmpty());
        pulse(); assertTrue(drain(police).isEmpty());
    }

    @Test public void stepFailureUsesEngineAbortAndDoesNotCrushAnotherRoom() throws Exception {
        throwStep = true; atTick(1);
        assertEquals(ResultReason.SERVER_ABORTED, result(drain(police)).reason); drain(thief);
        assertEquals(1, aborts);
        Client other = connect(); control(other, "JOIN_ROOM", fields("roomId", "OTHER"));
    }

    @Test public void projectionFailureAbortsAndConfirmedResultDoesNotDependOnTerminalSnapshot() throws Exception {
        throwViewAfterStart = true; atTick(1);
        List<WireCodec.Message> a = drain(police), b = drain(thief);
        assertEquals(ResultReason.SERVER_ABORTED, result(a).reason);
        assertEquals(result(a).resultId, result(b).resultId); assertEquals(1, result(a).finalTick);
        assertEquals(-1, index(a, "SNAPSHOT")); assertEquals(1, aborts);
    }

    @Test public void abortFailureClosesWithoutInventingGameOverAndServerStillAcceptsClients() throws Exception {
        throwStep = throwAbort = true; atTick(1);
        List<WireCodec.Message> a = until(police, "ERROR"), b = until(thief, "ERROR");
        assertError(a, "SERVER_ERROR"); assertError(b, "SERVER_ERROR");
        assertEquals(-1, index(a, "GAME_OVER")); assertEquals(-1, index(b, "GAME_OVER"));
        Client other = connect(); control(other, "JOIN_ROOM", fields("roomId", "OTHER"));
    }

    @Test public void realEngineTimeoutWinsOverBothDeparturesOnTick5400() throws Exception {
        for (int tick = 1; tick < 5400; tick++) {
            clock.set(3000 + (tick * 1000L + 29) / 30);
            if (tick % 30 == 0) { drain(police); drain(thief); }
            pulse();
        }
        drain(police); drain(thief); clock.set(183000);
        send(police, "LEAVE_ROOM", "final-leave", null, fields("reason", "QUIT")); drain(police);
        send(thief, "LEAVE_ROOM", "final-leave", null, fields("reason", "BACKGROUND")); drain(thief);
        pulse();
        MatchResult a = result(until(police, "ACK")), b = result(until(thief, "ACK"));
        assertEquals(ResultReason.TIMEOUT, a.reason); assertEquals(Role.POLICE, a.winner);
        assertEquals(5400, a.elapsedTicks); assertEquals(5400, a.finalTick);
        assertEquals(a.resultId, b.resultId); assertEquals(2, steps.get(5399).departures.size());
    }

    private Client connect() throws Exception {
        Client client = new Client(server.getPort()); clients.add(client);
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS));
        send(client, "HELLO", "hello", null, fields("clientBuild", "TEST_ONLY", "mapHash", data.mapHash,
                "configHash", data.configHash));
        client.playerId = WireCodec.string(until(client, "WELCOME").get(0).body, "playerId"); return client;
    }
    private void control(Client client, String type, Object body) throws Exception {
        send(client, type, "control-" + client.seq, null, body); until(client, "ACK");
    }
    private void input(Client client, long seq, double x, double y, boolean held, Long drop) {
        send(client, "INPUT", null, matchId, fields("inputSeq", seq, "moveX", x, "moveY", y,
                "interactHeld", held, "dropId", drop));
    }
    private void send(Client client, String type, String request, String match, Object body) {
        client.send(codec.encode(type, ++client.seq, request, match, body));
    }
    private void atTick(long tick) throws Exception { clock.set(3000 + (tick * 1000 + 29) / 30); pulse(); }
    private void pulse() throws Exception { server.checkTimeouts().get(3, TimeUnit.SECONDS); }
    private List<WireCodec.Message> drain(Client client) throws Exception {
        send(client, "HEARTBEAT", null, null, fields("nonce", clock.get()));
        List<WireCodec.Message> messages = until(client, "PONG"); messages.remove(messages.size() - 1); return messages;
    }
    private List<WireCodec.Message> until(Client client, String type) throws Exception {
        List<WireCodec.Message> messages = new ArrayList<>();
        do {
            String text = client.incoming.poll(5, TimeUnit.SECONDS); assertNotNull("Expected " + type, text);
            messages.add(codec.decodeServer(text));
        } while (!messages.get(messages.size() - 1).type.equals(type));
        return messages;
    }
    private PlayerView view(List<WireCodec.Message> messages) {
        return codec.body(messages.get(index(messages, "SNAPSHOT")), PlayerView.class);
    }
    private MatchResult result(List<WireCodec.Message> messages) {
        return codec.body(messages.get(index(messages, "GAME_OVER")), MatchResult.class);
    }
    private int index(List<WireCodec.Message> messages, String type) {
        for (int i = 0; i < messages.size(); i++) if (messages.get(i).type.equals(type)) return i;
        return -1;
    }
    private void assertError(List<WireCodec.Message> messages, String code) {
        int index = index(messages, "ERROR"); assertTrue("Expected " + code, index >= 0);
        assertEquals(code, WireCodec.string(messages.get(index).body, "code"));
    }
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result;
    }
}
