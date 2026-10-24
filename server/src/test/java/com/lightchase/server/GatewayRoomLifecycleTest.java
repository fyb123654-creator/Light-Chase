package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.game.V1EngineFactory;
import com.lightchase.network.WebSocketClientGateway;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.Test;
import static org.junit.Assert.*;

/** The production server and gateways must agree on room revisions across two complete rounds. */
public class GatewayRoomLifecycleTest {
    private static final class Listener implements GatewayListener {
        final BlockingQueue<RoomState> rooms = new LinkedBlockingQueue<>();
        final BlockingQueue<MatchResult> results = new LinkedBlockingQueue<>();
        final BlockingQueue<ControlStatus> controls = new LinkedBlockingQueue<>();
        final List<GatewayError> errors = new CopyOnWriteArrayList<>();
        final List<PlayerView> views = new CopyOnWriteArrayList<>();
        volatile SessionInfo session;
        public void onSession(SessionInfo s) { if (s != null) session = s; }
        public void onConnection(ConnectionStatus s) {}
        public void onRoom(RoomState s) { rooms.add(s); }
        public void onView(PlayerView s) { views.add(s); }
        public void onResult(MatchResult s) { results.add(s); }
        public void onError(GatewayError e) { errors.add(e); }
        public void onControl(ControlStatus s) { if (s.state == ControlStatus.State.SUCCEEDED) controls.add(s); }
    }

    @Test public void realGatewaysAcceptFinishReturnAndRematchWithoutRevisionOrIdentityConflicts() throws Exception {
        DataFiles data = DataFiles.bundled();
        AtomicLong now = new AtomicLong();
        MatchEngineFactory factory = (map, rules, init) -> {
            MatchEngine real = new V1EngineFactory().create(map, rules, init);
            return new MatchEngine() {
                // Trigger the existing server-abort path to finish each round quickly, without changing A rules.
                public StepResult step(StepInput input) { throw new IllegalStateException("TEST_ONLY stop round"); }
                public PlayerView viewFor(String id) { return real.viewFor(id); }
                public MatchResult result() { return real.result(); }
                public MatchResult abort() { return real.abort(); }
            };
        };
        LobbyServer server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data, now::get, false, factory);
        WebSocketClientGateway a = new WebSocketClientGateway(), b = new WebSocketClientGateway();
        Listener la = new Listener(), lb = new Listener();
        try {
            server.start(); server.awaitStart();
            ConnectionOptions options = new ConnectionOptions("127.0.0.1", server.getPort(), "test", "junit", data.mapHash, data.configHash);
            a.connect(options, la); awaitRoom(la, r -> r.players.size() == 1);
            b.connect(options, lb); awaitRoom(la, r -> r.players.size() == 2); awaitRoom(lb, r -> r.players.size() == 2);
            a.selectRole(Role.POLICE); awaitControl(la, "SELECT_ROLE");
            b.selectRole(Role.THIEF); awaitControl(lb, "SELECT_ROLE");
            String firstMatch = null;
            for (int round = 0; round < 2; round++) {
                a.setReady(true); awaitControl(la, "SET_READY");
                b.setReady(true); awaitControl(lb, "SET_READY");
                awaitRoom(la, r -> r.phase == RoomPhase.COUNTDOWN); awaitRoom(lb, r -> r.phase == RoomPhase.COUNTDOWN);
                now.addAndGet(3000); server.checkTimeouts().get(3, TimeUnit.SECONDS);
                RoomState running = awaitRoom(la, r -> r.phase == RoomPhase.RUNNING);
                awaitRoom(lb, r -> r.phase == RoomPhase.RUNNING);
                if (round == 0) firstMatch = running.matchId; else assertNotEquals(firstMatch, running.matchId);
                now.addAndGet(34); server.checkTimeouts().get(3, TimeUnit.SECONDS);
                awaitRoom(la, r -> r.phase == RoomPhase.FINISHED); awaitRoom(lb, r -> r.phase == RoomPhase.FINISHED);
                MatchResult ra = la.results.poll(3, TimeUnit.SECONDS), rb = lb.results.poll(3, TimeUnit.SECONDS);
                assertNotNull(ra); assertNotNull(rb); assertEquals(running.matchId, ra.matchId);
                assertEquals(ra.resultId, rb.resultId); assertEquals(ResultReason.SERVER_ABORTED, ra.reason);
                assertTrue("Initial and same-tick terminal views delivered", la.views.size() >= (round + 1) * 2);
                a.returnToLobby(); awaitControl(la, "RETURN_LOBBY");
                awaitRoom(la, r -> r.phase == RoomPhase.FINISHED && r.players.stream()
                        .anyMatch(p -> p.playerId.equals(la.session.playerId) && p.returned));
                b.returnToLobby(); awaitControl(lb, "RETURN_LOBBY");
                RoomState lobby = awaitRoom(la, r -> r.phase == RoomPhase.LOBBY);
                awaitRoom(lb, r -> r.phase == RoomPhase.LOBBY);
                for (RoomPlayer member : lobby.players) { assertFalse(member.ready); assertFalse(member.returned); }
            }
            assertTrue("Gateway A errors: " + codes(la), la.errors.isEmpty());
            assertTrue("Gateway B errors: " + codes(lb), lb.errors.isEmpty());
        } finally { a.close(); b.close(); server.shutdown(); }
    }

    private static RoomState awaitRoom(Listener l, Predicate<RoomState> predicate) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            RoomState state = l.rooms.poll(100, TimeUnit.MILLISECONDS);
            if (state != null && predicate.test(state)) return state;
        }
        fail("Expected room; errors=" + codes(l)); return null;
    }
    private static void awaitControl(Listener l, String action) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            ControlStatus status = l.controls.poll(100, TimeUnit.MILLISECONDS);
            if (status != null && action.equals(status.action)) return;
        }
        fail("Expected " + action + " ACK; errors=" + codes(l));
    }
    private static List<String> codes(Listener listener) {
        List<String> codes = new ArrayList<>();
        for (GatewayError e : listener.errors) codes.add(e.code);
        return codes;
    }
}
