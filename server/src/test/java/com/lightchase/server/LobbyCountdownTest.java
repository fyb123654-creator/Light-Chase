package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.network.WireCodec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.*;
import static org.junit.Assert.*;

/** Two real WebSockets, a monotonic test clock, and a TEST_ONLY engine with no rules. */
public class LobbyCountdownTest {
    private final DataFiles data = DataFiles.bundled();
    private final WireCodec codec = new WireCodec();
    private final AtomicLong clock = new AtomicLong();
    private final List<Client> clients = new ArrayList<>();
    private final FakeFactory factory = new FakeFactory();
    private LobbyServer server;
    private Client a, b;

    private static final class Client extends WebSocketClient {
        final HealthBroadcastFilter health = new HealthBroadcastFilter();
        final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        final List<Long> countdownTimes = Collections.synchronizedList(new ArrayList<>());
        final WireCodec codec = new WireCodec();
        volatile boolean automaticHeartbeat;
        long seq;
        String playerId;
        Client(URI uri) { super(uri); }
        public void onOpen(ServerHandshake handshake) {}
        public void onMessage(String message) {
            if (health.healthOnly(message)) return;
            WireCodec.Message decoded = codec.decodeServer(message);
            if (automaticHeartbeat && "PONG".equals(decoded.type)) return;
            if ("ROOM_STATE".equals(decoded.type)
                    && "COUNTDOWN".equals(WireCodec.string(decoded.body, "phase"))) {
                countdownTimes.add(System.nanoTime());
            }
            incoming.add(message);
        }
        public void onClose(int code, String reason, boolean remote) {}
        public void onError(Exception error) {}
    }

    private static final class FakeFactory implements MatchEngineFactory {
        final List<MatchInit> starts = new ArrayList<>();
        final List<String> views = new ArrayList<>();
        final Set<String> threads = new HashSet<>();
        boolean failCreate, failView;
        public MatchEngine create(MapDefinition map, RuleConfig rules, MatchInit init) {
            starts.add(init); threads.add(Thread.currentThread().getName());
            if (failCreate) throw new IllegalStateException("TEST_ONLY failure");
            return new MatchEngine() {
                long tick;
                public StepResult step(StepInput input) { tick = input.tick; return new StepResult(tick, tick == 1, false, null); }
                public MatchResult result() { return null; }
                public MatchResult abort() { throw new AssertionError("B3 must not fabricate a result"); }
                public PlayerView viewFor(String playerId) {
                    if (failView) throw new IllegalStateException("TEST_ONLY projection failure");
                    views.add(playerId); threads.add(Thread.currentThread().getName());
                    boolean police = init.policePlayerId.equals(playerId);
                    assertTrue(police || init.thiefPlayerId.equals(playerId));
                    return new PlayerView(init.matchId, tick, 0, 0, police ? Role.POLICE : Role.THIEF,
                            5400, 0, 0, false, new ActorPose(playerId, police ? 7.5 : 17.5, 4.5, 0),
                            null, Arrays.asList(new OriginStatus("M1", false), new OriginStatus("M2", false),
                            new OriginStatus("M3", false)), police ? null : new ThiefPrivate(null,
                            Protection.NORMAL, 0, Collections.emptyList(), Collections.emptyList(),
                            new InteractionView(null, null, 0, false, "NO_TARGET")));
                }
            };
        }
    }

    @Before public void start() throws Exception {
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data, clock::get, false, factory);
        server.start(); server.awaitStart();
        a = connect(); b = connect();
        join(a, "TEST"); join(b, "TEST"); room(a);
    }

    @After public void stop() throws Exception {
        for (Client client : clients) client.close();
        server.shutdown();
    }

    @Test public void readyRequiresRoleAndSinglePlayerCannotStart() throws Exception {
        send(a, "SET_READY", "no-role", fields("ready", true)); error(a, "ROLE_REQUIRED");
        role(a, Role.POLICE);
        RoomState ready = ready(a, true);
        assertEquals(RoomPhase.LOBBY, ready.phase); assertTrue(ready.players.get(0).ready);
        clock.set(3000); sweep(); assertTrue(factory.starts.isEmpty());
    }

    @Test public void simultaneousRoleRequestsAreExclusive() throws Exception {
        send(a, "SELECT_ROLE", "a-role", fields("role", "POLICE"));
        send(b, "SELECT_ROLE", "b-role", fields("role", "POLICE"));
        WireCodec.Message firstA = receive(a), firstB = receive(b);
        boolean aWon = "ACK".equals(firstA.type);
        if (aWon) {
            assertEquals("ROOM_STATE", firstB.type); room(a); error(b, "ROLE_TAKEN");
        } else {
            assertEquals("ROOM_STATE", firstA.type); assertEquals("ACK", firstB.type);
            error(a, "ROLE_TAKEN"); room(b);
        }
        // The winner's role is the only occupied slot.
        send(a, "JOIN_ROOM", "query", fields("roomId", "TEST")); take(a, "ACK");
        RoomState state = room(a);
        assertEquals(1, state.players.stream().filter(p -> p.role == Role.POLICE).count());
    }

    @Test public void roleChangeClearsBothPreparationFlags() throws Exception {
        role(a, Role.POLICE); ready(a, true);
        RoomState changed = role(a, Role.THIEF);
        assertLobby(changed); assertEquals(Role.THIEF, changed.players.get(0).role);
        role(b, Role.POLICE); assertEquals(RoomPhase.LOBBY, ready(b, true).phase);
        assertTrue(factory.starts.isEmpty());
    }

    @Test public void repeatedRoleReadyAndCachedRequestsDoNotChangeRevision() throws Exception {
        RoomState selected = role(a, Role.POLICE);
        assertEquals(selected.revision, role(a, Role.POLICE).revision);
        RoomState prepared = ready(a, true);
        assertEquals(prepared.revision, ready(a, true).revision);
        send(a, "SET_READY", "replayed", fields("ready", true)); take(a, "ACK");
        assertEquals(prepared.revision, room(a).revision);
        send(a, "SET_READY", "replayed", fields("ready", true)); take(a, "ACK");
        // Cached ACK has no new room mutation or peer broadcast.
        barrier(a); assertTrue(a.incoming.isEmpty()); barrier(b); assertTrue(b.incoming.isEmpty());
    }

    @Test public void countdownBroadcastsEvery100msWithMonotonicRevisions() throws Exception {
        RoomState start = countdown();
        assertEquals(3000, start.countdownRemainingMs); assertNull(start.matchId);
        clock.set(99); sweep(); barrier(a); assertTrue(a.incoming.isEmpty());
        long revision = start.revision;
        for (int ms = 100; ms < 3000; ms += 100) {
            clock.set(ms); sweep(); RoomState current = room(a), peer = room(b);
            assertEquals(RoomPhase.COUNTDOWN, current.phase);
            assertEquals(3000 - ms, current.countdownRemainingMs);
            assertEquals(++revision, current.revision); assertEquals(revision, peer.revision);
        }
        assertTrue(factory.starts.isEmpty());
        clock.set(2999); sweep(); barrier(a); assertTrue(a.incoming.isEmpty());
        clock.set(3000); sweep(); assertStarted(a, Role.POLICE); assertStarted(b, Role.THIEF);
    }

    @Test public void countdownCadenceDoesNotDriftWhenConsecutiveTimerIntervalsAre99ms() throws Exception {
        clock.set(37); // Countdown need not align with the process timer's origin.
        RoomState start = countdown();
        for (int slot = 1; slot < 30; slot++) {
            // Actual wake times alternate +1ms and on-time: 101, 200, 301, 400, ...
            // Resetting the baseline to 101 incorrectly skips the update at 200.
            long elapsed = slot * 100L + slot % 2;
            clock.set(37 + elapsed); sweep();
            RoomState current = room(a), peer = room(b);
            assertEquals(start.revision + slot, current.revision);
            assertEquals(current.revision, peer.revision);
            assertEquals(RoomPhase.COUNTDOWN, current.phase);
            assertEquals((90 - elapsed * 30 / 1000) * 1000 / 30, current.countdownRemainingMs);
            // Extra scans in the same slot cannot add revisions or duplicate broadcasts.
            sweep(); barrier(a); barrier(b);
            assertTrue(a.incoming.isEmpty()); assertTrue(b.incoming.isEmpty());
        }
        clock.set(3037); sweep(); assertStarted(a, Role.POLICE); assertStarted(b, Role.THIEF);
        assertEquals(1, factory.starts.size());
    }

    @Test public void delayedBroadcastCoalescesMissedSlotsWithoutMovingTheNextDeadline() throws Exception {
        RoomState start = countdown();
        clock.set(101); sweep(); room(a); room(b);
        clock.set(415); sweep();
        assertEquals(start.revision + 2, room(a).revision); room(b);
        clock.set(499); sweep(); barrier(a); barrier(b);
        assertTrue(a.incoming.isEmpty()); assertTrue(b.incoming.isEmpty());
        clock.set(500); sweep();
        RoomState next = room(a); room(b);
        assertEquals(start.revision + 3, next.revision); assertEquals(2500, next.countdownRemainingMs);
        assertTrue(factory.starts.isEmpty());
    }

    @Test(timeout = 15000) public void automaticTimerBroadcastsAt100msCadenceWithFakeEngine() throws Exception {
        a.closeBlocking(); b.closeBlocking(); server.shutdown();
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data,
                () -> System.nanoTime() / 1_000_000, true, factory);
        server.start(); server.awaitStart();
        a = connect(); b = connect(); join(a, "TEST"); join(b, "TEST"); room(a);
        countdown();
        ScheduledExecutorService heartbeatTimer = Executors.newSingleThreadScheduledExecutor();
        a.automaticHeartbeat = b.automaticHeartbeat = true;
        heartbeatTimer.scheduleAtFixedRate(() -> {
            for (Client client : Arrays.asList(a, b)) send(client, "HEARTBEAT", null, fields("nonce", System.nanoTime() / 1_000_000));
        }, 500, 500, TimeUnit.MILLISECONDS);
        try {
        for (Client client : Arrays.asList(a, b)) {
            long revision = -1;
            RoomState state;
            do {
                state = room(client);
                if (revision >= 0) assertEquals(revision + 1, state.revision);
                revision = state.revision;
            } while (state.phase == RoomPhase.COUNTDOWN);
            assertEquals(RoomPhase.RUNNING, state.phase);
            PlayerView view = codec.body(take(client, "SNAPSHOT"), PlayerView.class);
            assertEquals(state.matchId, view.matchId); assertEquals(client.playerId, view.self.playerId);
            List<Long> times = new ArrayList<>(client.countdownTimes);
            assertTrue("Too few automatic broadcasts: " + times.size(), times.size() >= 29);
            assertTrue(times.size() <= 30);
            for (int i = 1; i < times.size(); i++) {
                long gap = TimeUnit.NANOSECONDS.toMillis(times.get(i) - times.get(i - 1));
                assertTrue("Missed automatic broadcast period: " + gap + "ms", gap <= 175);
            }
        }
        } finally {
            heartbeatTimer.shutdownNow();
            a.automaticHeartbeat = b.automaticHeartbeat = false;
        }
        assertEquals(1, factory.starts.size());
    }

    @Test public void startCreatesExactlyOneEngineAndSendsRunningBeforePrivateViews() throws Exception {
        countdown(); clock.set(3000); sweep();
        RoomState running = assertStarted(a, Role.POLICE); RoomState peer = assertStarted(b, Role.THIEF);
        assertEquals(running.matchId, peer.matchId); assertEquals(1, factory.starts.size());
        MatchInit init = factory.starts.get(0);
        assertEquals(running.matchId, init.matchId); assertEquals(a.playerId, init.policePlayerId);
        assertEquals(b.playerId, init.thiefPlayerId);
        assertEquals(Arrays.asList(a.playerId, b.playerId), factory.views);
        assertEquals(Collections.singleton("lightchase-lobby-logic"), factory.threads);
        for (int ms = 3100; ms <= 4000; ms += 100) { clock.set(ms); sweep(); }
        assertEquals(1, factory.starts.size()); barrier(a);
        while (!a.incoming.isEmpty()) take(a, "SNAPSHOT"); // B4 now advances the existing match.
        barrier(b); while (!b.incoming.isEmpty()) take(b, "SNAPSHOT");
        send(a, "SET_READY", "too-late", fields("ready", false)); error(a, "INVALID_PHASE");
        send(b, "SELECT_ROLE", "too-late", fields("role", "POLICE")); error(b, "INVALID_PHASE");
    }

    @Test public void cancellationAtDeadlineWinsIfProcessedBeforeSweepAndOldDeadlineCannotStart() throws Exception {
        countdown(); clock.set(3000); assertLobby(ready(a, false)); sweep();
        assertTrue(factory.starts.isEmpty());
        ready(a, true); RoomState next = ready(b, true);
        assertEquals(3000, next.countdownRemainingMs);
        clock.set(5999); sweep(); room(a); room(b); assertTrue(factory.starts.isEmpty());
        clock.set(6000); sweep(); assertStarted(a, Role.POLICE); assertStarted(b, Role.THIEF);
        assertEquals(1, factory.starts.size());
    }

    @Test public void deadlineWinsIfSweepWasProcessedBeforeCancellation() throws Exception {
        countdown(); clock.set(3000); sweep(); assertStarted(a, Role.POLICE); assertStarted(b, Role.THIEF);
        send(a, "SET_READY", "cancel-after", fields("ready", false)); error(a, "INVALID_PHASE");
        assertEquals(1, factory.starts.size());
    }

    @Test public void selectingOccupiedRoleDuringCountdownDoesNotCancelOrOverwriteIt() throws Exception {
        RoomState start = countdown(); clock.set(2999); heartbeat(a); heartbeat(b); clock.set(3000);
        send(a, "SELECT_ROLE", "conflict", fields("role", "THIEF")); error(a, "ROLE_TAKEN");
        // Selecting one's current role is also a no-op at the boundary.
        assertEquals(start.revision, role(a, Role.POLICE).revision);
        sweep(); assertStarted(a, Role.POLICE); assertStarted(b, Role.THIEF);
    }

    @Test public void quitOnLastTickClearsPeerAndCannotStart() throws Exception {
        countdown(); clock.set(3000);
        send(a, "LEAVE_ROOM", "leave", fields("reason", "QUIT")); take(a, "ACK");
        RoomState remaining = room(b); assertLobby(remaining); assertEquals(1, remaining.players.size());
        sweep(); assertTrue(factory.starts.isEmpty());
    }

    @Test public void backgroundOnLastTickCancelsWithoutAResult() throws Exception {
        countdown(); clock.set(3000);
        send(b, "LEAVE_ROOM", "background", fields("reason", "BACKGROUND")); take(b, "ACK");
        assertLobby(room(a)); sweep(); assertTrue(factory.starts.isEmpty());
        barrier(a); assertTrue(a.incoming.isEmpty());
    }

    @Test public void abruptDisconnectOnLastTickCancelsBeforeEngineCreation() throws Exception {
        countdown(); clock.set(3000); b.closeBlocking();
        assertLobby(room(a)); sweep(); assertTrue(factory.starts.isEmpty());
    }

    @Test public void timedOutPeerIsRemovedBeforeCountdownDeadlineEvenIfOtherPeerIsHealthy() throws Exception {
        role(a, Role.POLICE); role(b, Role.THIEF); ready(a, true);
        // Do not use ready()'s heartbeat delivery barrier: a must remain silent from t=0.
        clock.set(2000);
        send(b, "SET_READY", "prepare", fields("ready", true)); take(b, "ACK"); room(b); room(a);
        clock.set(5000); heartbeat(b); sweep();
        assertLobby(room(b)); assertTrue(factory.starts.isEmpty());
    }

    @Test public void emptyRoomRecreatedBeforeOldDeadlineHasAFreshCountdownAndMatchId() throws Exception {
        countdown(); clock.set(2999);
        send(a, "LEAVE_ROOM", "leave-a", fields("reason", "QUIT")); take(a, "ACK"); room(b);
        send(b, "LEAVE_ROOM", "leave-b", fields("reason", "QUIT")); take(b, "ACK");
        a = connect(); b = connect(); join(a, "TEST"); join(b, "TEST"); room(a);
        countdown(); clock.set(3000); sweep(); assertTrue(factory.starts.isEmpty());
        clock.set(5999); sweep(); assertStarted(a, Role.POLICE); assertStarted(b, Role.THIEF);
    }

    @Test public void resourceMismatchCannotJoinOrContributePreparation() throws Exception {
        Client mismatch = socket();
        send(mismatch, "HELLO", "wrong", fields("clientBuild", "TEST_ONLY", "mapHash", data.mapHash,
                "configHash", "wrong")); error(mismatch, "CONFIG_MISMATCH");
        role(a, Role.POLICE); assertEquals(RoomPhase.LOBBY, ready(a, true).phase);
        clock.set(3000); sweep(); assertTrue(factory.starts.isEmpty());
    }

    @Test public void engineCreationFailureClosesOnlyAffectedRoomAndDoesNotRetry() throws Exception {
        factory.failCreate = true; countdown(); clock.set(3000); sweep();
        error(a, "SERVER_ERROR"); error(b, "SERVER_ERROR"); sweep();
        assertEquals(1, factory.starts.size()); assertTrue(factory.views.isEmpty());
        Client healthy = connect(); assertEquals(RoomPhase.LOBBY, join(healthy, "OTHER").phase);
    }

    @Test public void projectionFailureDoesNotFabricateSnapshotOrResult() throws Exception {
        factory.failView = true; countdown(); clock.set(3000); sweep();
        assertEquals(RoomPhase.RUNNING, room(a).phase); assertEquals(RoomPhase.RUNNING, room(b).phase);
        error(a, "SERVER_ERROR");
        // Removing a first peer may publish a RUNNING membership update before the second error.
        WireCodec.Message message = receive(b);
        if ("ROOM_STATE".equals(message.type)) message = receive(b);
        assertEquals("ERROR", message.type); assertEquals("SERVER_ERROR", WireCodec.string(message.body, "code"));
        sweep(); assertEquals(1, factory.starts.size());
    }

    @Test public void replayingSuccessfulPreparationAfterCancellationCannotRearmCountdown() throws Exception {
        countdown();
        send(a, "SET_READY", "remembered-ready", fields("ready", true)); take(a, "ACK"); room(a);
        clock.set(2999); RoomState cancelled = ready(b, false); assertLobby(cancelled);
        send(a, "SET_READY", "remembered-ready", fields("ready", true)); take(a, "ACK");
        send(a, "JOIN_ROOM", "inspect", fields("roomId", "TEST")); take(a, "ACK");
        RoomState inspected = room(a); assertLobby(inspected); assertEquals(cancelled.revision, inspected.revision);
        assertEquals(cancelled.revision, ready(a, false).revision);
        clock.set(3000); sweep(); assertTrue(factory.starts.isEmpty());
    }

    @Test public void separateRoomsCreateDistinctMatchIdsWithoutSharingPlayers() throws Exception {
        countdown();
        Client c = connect(), d = connect(); join(c, "OTHER"); join(d, "OTHER"); room(c);
        send(c, "SELECT_ROLE", "police", fields("role", "POLICE")); take(c, "ACK"); room(c); room(d);
        send(d, "SELECT_ROLE", "thief", fields("role", "THIEF")); take(d, "ACK"); room(c); room(d);
        send(c, "SET_READY", "prepare", fields("ready", true)); take(c, "ACK"); room(c); room(d);
        send(d, "SET_READY", "prepare", fields("ready", true)); take(d, "ACK"); room(c); room(d);
        clock.set(3000); sweep();
        RoomState first = assertStarted(a, Role.POLICE); assertStarted(b, Role.THIEF);
        RoomState second = assertStarted(c, Role.POLICE); assertStarted(d, Role.THIEF);
        assertNotEquals(first.matchId, second.matchId); assertEquals(2, factory.starts.size());
    }

    private RoomState countdown() throws Exception {
        role(a, Role.POLICE); role(b, Role.THIEF); ready(a, true);
        RoomState state = ready(b, true); assertEquals(RoomPhase.COUNTDOWN, state.phase); return state;
    }

    private RoomState role(Client client, Role role) throws Exception {
        send(client, "SELECT_ROLE", "role-" + client.seq, fields("role", role.name())); take(client, "ACK");
        RoomState state = room(client);
        // Changed role broadcasts; same role only responds to its caller.
        Client peer = client == a ? b : a;
        barrier(peer);
        while (!peer.incoming.isEmpty()) room(peer);
        return state;
    }

    private RoomState ready(Client client, boolean value) throws Exception {
        send(client, "SET_READY", "ready-" + client.seq, fields("ready", value)); take(client, "ACK");
        RoomState state = room(client);
        Client peer = client == a ? b : a;
        barrier(peer);
        while (!peer.incoming.isEmpty()) room(peer);
        return state;
    }

    private RoomState assertStarted(Client client, Role expectedRole) throws Exception {
        // Extra health controls may briefly defer the conflated first snapshot; drain controls then flush it.
        barrier(client);
        sweep();
        RoomState state = room(client); assertEquals(RoomPhase.RUNNING, state.phase);
        assertNotNull(state.matchId); assertEquals(0, state.countdownRemainingMs);
        WireCodec.Message message = take(client, "SNAPSHOT");
        PlayerView view = codec.body(message, PlayerView.class);
        assertEquals(state.matchId, message.matchId); assertEquals(state.matchId, view.matchId);
        assertEquals(expectedRole, view.role); assertEquals(client.playerId, view.self.playerId);
        assertEquals(0, view.tick); assertEquals(5400, view.remainingTicks);
        assertEquals(clock.get(), view.serverTimeMs); assertEquals(0, view.ackInputSeq);
        assertEquals(expectedRole == Role.POLICE ? 7.5 : 17.5, view.self.x, 0);
        assertNull(view.opponent);
        if (expectedRole == Role.POLICE) assertTrue(message.body.get("thief").isJsonNull());
        else assertNotNull(view.thief);
        return state;
    }

    private void assertLobby(RoomState state) {
        assertEquals(RoomPhase.LOBBY, state.phase); assertNull(state.matchId); assertEquals(0, state.countdownRemainingMs);
        for (RoomPlayer player : state.players) assertFalse(player.ready);
    }

    private Client socket() throws Exception {
        Client client = new Client(URI.create("ws://127.0.0.1:" + server.getPort() + "/game"));
        clients.add(client); assertTrue(client.connectBlocking(5, TimeUnit.SECONDS)); return client;
    }

    private Client connect() throws Exception {
        Client client = socket();
        send(client, "HELLO", "hello", fields("clientBuild", "TEST_ONLY", "mapHash", data.mapHash,
                "configHash", data.configHash));
        client.playerId = WireCodec.string(take(client, "WELCOME").body, "playerId"); return client;
    }

    private RoomState join(Client client, String id) throws Exception {
        send(client, "JOIN_ROOM", "join", fields("roomId", id)); take(client, "ACK"); return room(client);
    }

    private void send(Client client, String type, String requestId, Object body) {
        client.send(codec.encode(type, ++client.seq, requestId, null, body));
    }

    private void sweep() throws Exception { server.checkTimeouts().get(3, TimeUnit.SECONDS); }
    private void heartbeat(Client client) throws Exception {
        send(client, "HEARTBEAT", null, fields("nonce", clock.get())); take(client, "PONG");
    }
    // PONG is a delivery barrier on this real ordered socket; keep earlier broadcasts queued.
    private void barrier(Client client) throws Exception {
        send(client, "HEARTBEAT", null, fields("nonce", clock.get()));
        List<String> before = new ArrayList<>();
        for (;;) {
            String text = client.incoming.poll(5, TimeUnit.SECONDS); assertNotNull(text);
            if ("PONG".equals(codec.decodeServer(text).type)) break;
            before.add(text);
        }
        client.incoming.addAll(before);
    }
    private RoomState room(Client client) throws Exception { return codec.body(take(client, "ROOM_STATE"), RoomState.class); }
    private WireCodec.Message error(Client client, String code) throws Exception {
        WireCodec.Message error = take(client, "ERROR"); assertEquals(code, WireCodec.string(error.body, "code")); return error;
    }
    private WireCodec.Message take(Client client, String type) throws Exception {
        WireCodec.Message message = receive(client); assertEquals(type, message.type); return message;
    }
    private WireCodec.Message receive(Client client) throws Exception {
        String text = client.incoming.poll(5, TimeUnit.SECONDS); assertNotNull("Expected message", text);
        return codec.decodeServer(text);
    }
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result;
    }
}
