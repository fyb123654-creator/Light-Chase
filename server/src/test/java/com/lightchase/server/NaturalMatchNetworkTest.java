package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.network.WireCodec;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.Test;
import static org.junit.Assert.*;

/** B-only end-to-end coverage: bundled resources, real sockets and unmodified production V1 engine. */
public class NaturalMatchNetworkTest {
    private final WireCodec codec = new WireCodec();
    private final AtomicLong now = new AtomicLong(1000);
    private final DataFiles data = DataFiles.bundled();
    private LobbyServer server;
    private Peer police, thief;
    private long elapsed;

    private final class Peer extends WebSocketClient {
        final BlockingQueue<WireCodec.Message> responses = new LinkedBlockingQueue<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile RoomState room;
        volatile PlayerView view;
        volatile MatchResult result;
        String id, match;
        long seq, inputSeq, dropId;
        double x, y;
        boolean held;
        Peer() { super(URI.create("ws://127.0.0.1:" + server.getPort() + "/game")); }
        public void onOpen(ServerHandshake h) {}
        public void onClose(int code, String reason, boolean remote) {}
        public void onError(Exception e) { errors.add(e.toString()); }
        public void onMessage(String raw) {
            WireCodec.Message m = codec.decodeServer(raw);
            if (m.type.equals("ROOM_STATE")) room = codec.body(m, RoomState.class);
            else if (m.type.equals("SNAPSHOT")) view = codec.body(m, PlayerView.class);
            else if (m.type.equals("GAME_OVER")) {
                result = codec.body(m, MatchResult.class);
                sendMessage("RESULT_ACK", null, result.matchId, Map.of("resultId", result.resultId));
            } else {
                if (m.type.equals("ERROR")) errors.add(WireCodec.string(m.body, "code"));
                responses.add(m);
            }
        }
        synchronized void sendMessage(String type, String request, String matchId, Object body) {
            send(codec.encode(type, ++seq, request, matchId, body));
        }
        void control(String type, Object body) throws Exception {
            sendMessage(type, "request-" + (seq + 1), null, body); take("ACK");
        }
        WireCodec.Message take(String type) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < end) {
                WireCodec.Message m = responses.poll(50, TimeUnit.MILLISECONDS);
                if (m != null && m.type.equals(type)) return m;
                assertTrue("Protocol errors: " + errors, errors.isEmpty());
            }
            throw new AssertionError("Missing " + type + "; " + errors);
        }
        void beat() throws Exception {
            sendMessage("HEARTBEAT", null, null, Map.of("nonce", seq + 1)); take("PONG");
        }
        void input(boolean drop) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inputSeq", ++inputSeq); body.put("moveX", x); body.put("moveY", y);
            body.put("interactHeld", held); body.put("dropId", drop ? ++dropId : null);
            sendMessage("INPUT", null, match, body);
        }
    }

    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(1);
        assertTrue("Timed out; police=" + police.errors + ", thief=" + thief.errors, condition.getAsBoolean());
    }
    private void join(Peer p, Role role) throws Exception {
        assertTrue(p.connectBlocking(3, TimeUnit.SECONDS));
        p.sendMessage("HELLO", "hello", null, Map.of("clientBuild", "B_NATURAL_MATCH_TEST", "mapHash", data.mapHash, "configHash", data.configHash));
        p.id = WireCodec.string(p.take("WELCOME").body, "playerId");
        p.control("JOIN_ROOM", Map.of("roomId", "NATURAL")); p.control("SELECT_ROLE", Map.of("role", role.name()));
    }
    private String startRound() throws Exception {
        police.view = thief.view = null; police.result = thief.result = null;
        police.inputSeq = thief.inputSeq = police.dropId = thief.dropId = 0;
        police.x = police.y = thief.x = thief.y = 0; police.held = thief.held = false;
        police.control("SET_READY", Map.of("ready", true)); thief.control("SET_READY", Map.of("ready", true));
        await(() -> thief.room.phase == RoomPhase.COUNTDOWN);
        now.addAndGet(3000); server.checkTimeouts().get(3, TimeUnit.SECONDS);
        await(() -> police.view != null && thief.view != null);
        police.match = police.view.matchId; thief.match = thief.view.matchId; elapsed = 0;
        assertEquals(police.match, thief.match); assertEquals(0, thief.view.tick);
        assertEquals(0, thief.view.marks); assertEquals(0, thief.view.deliveredCount);
        assertEquals(5400, thief.view.remainingTicks); assertNull(thief.view.thief.carriedMaterialId);
        assertEquals(3, thief.view.thief.groundMaterials.size()); assertEquals(Protection.NORMAL, thief.view.thief.protection);
        assertFalse(thief.view.exitsOpen); assertNull(police.view.thief);
        assertEquals(data.map.spawns.get(Role.THIEF).x, thief.view.self.x, 0);
        assertEquals(data.map.spawns.get(Role.POLICE).x, police.view.self.x, 0);
        for (OriginStatus origin : thief.view.originStatus) assertFalse(origin.everPicked);
        return police.match;
    }
    private void advance(boolean drop) throws Exception {
        police.input(false); thief.input(drop);
        // A heartbeat response is a socket/logic-thread barrier for the preceding input.
        police.beat(); thief.beat();
        now.addAndGet(100); elapsed += 100; server.checkTimeouts().get(3, TimeUnit.SECONDS);
        long expectedViewTick = elapsed * 30 / 1000 / 2 * 2;
        await(() -> (police.result != null || police.view.tick >= expectedViewTick)
                && (thief.result != null || thief.view.tick >= expectedViewTick));
        assertTrue(police.errors.toString(), police.errors.isEmpty());
        assertTrue(thief.errors.toString(), thief.errors.isEmpty());
        assertNull(police.view.thief);
    }
    private void advance() throws Exception { advance(false); }
    private void travel(Peer p, double tx, double ty) throws Exception {
        for (int i = 0; i < 160; i++) {
            assertNull("Unexpected match ending", thief.result);
            double dx = tx - p.view.self.x, dy = ty - p.view.self.y, distance = Math.hypot(dx, dy);
            if (distance < .2) { p.x = p.y = 0; advance(); return; }
            double magnitude = Math.min(1, distance / .6);
            p.x = dx / distance * magnitude; p.y = dy / distance * magnitude; advance();
        }
        fail("Cannot reach " + tx + "," + ty + " from " + p.view.self.x + "," + p.view.self.y);
    }
    private void holdUntil(BooleanSupplier condition) throws Exception {
        thief.held = true;
        for (int i = 0; i < 20 && !condition.getAsBoolean(); i++) advance();
        assertTrue("Interaction did not finish", condition.getAsBoolean());
        thief.held = false; if (thief.result == null) advance();
    }
    private void route(double[][] points) throws Exception { for (double[] point : points) travel(thief, point[0], point[1]); }
    private void sameResult(ResultReason reason, Role winner) throws Exception {
        await(() -> police.result != null && thief.result != null);
        MatchResult a = police.result, b = thief.result;
        assertEquals(reason, a.reason); assertEquals(winner, a.winner);
        assertEquals(codec.encode("GAME_OVER", 1, null, a.matchId, a), codec.encode("GAME_OVER", 1, null, b.matchId, b));
    }
    private void returnBoth() throws Exception {
        for (Peer p : List.of(police, thief)) {
            p.sendMessage("RETURN_LOBBY", "return-" + p.seq, p.match, Map.of("resultId", p.result.resultId)); p.take("ACK");
        }
        await(() -> police.room.phase == RoomPhase.LOBBY && thief.room.phase == RoomPhase.LOBBY);
        for (RoomPlayer p : police.room.players) { assertFalse(p.ready); assertFalse(p.returned); }
    }

    @Test public void bundledV1CompletesBothNaturalWinsAndResetsAcrossThreeRounds() throws Exception {
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data, now::get, false);
        server.start(); server.awaitStart(); police = new Peer(); thief = new Peer();
        try {
            join(police, Role.POLICE); join(thief, Role.THIEF);
            String first = startRound();
            travel(police, 17.2, 8.5);
            for (int i = 0; i < 120 && thief.result == null; i++) advance();
            sameResult(ResultReason.CAPTURED, Role.POLICE); assertEquals(3, thief.result.marks);
            returnBoth();
            String second = startRound(); assertNotEquals(first, second);
            travel(police, 17.2, 8.5);
            for (int i = 0; i < 100 && thief.view.marks < 2; i++) advance();
            assertEquals(2, thief.view.marks);
            // Move past the thief during the existing protection window and face the outer wall.
            // This avoids crossing the stationary police beam later; it does not alter A's rules.
            travel(police, 21.8, 8.5);
            travel(thief, 18.5, 4.5);
            while (thief.view.thief.protection != Protection.NORMAL) advance();
            holdUntil(() -> "M3".equals(thief.view.thief.carriedMaterialId));
            advance(true); assertNull(thief.view.thief.carriedMaterialId);
            holdUntil(() -> "M3".equals(thief.view.thief.carriedMaterialId));
            route(new double[][]{{14,8.5},{9,8.5},{9,11.5},{5.5,11.5}});
            holdUntil(() -> thief.view.deliveredCount == 1);
            travel(thief,7.5,13.5); holdUntil(() -> "M2".equals(thief.view.thief.carriedMaterialId));
            route(new double[][]{{9,8.5},{14,8.5},{17.5,8.5},{17.5,6.5}});
            holdUntil(() -> thief.view.deliveredCount == 2);
            route(new double[][]{{14,8.5},{9,8.5},{9,3.5},{4.5,3.5}});
            holdUntil(() -> "M1".equals(thief.view.thief.carriedMaterialId));
            route(new double[][]{{9,3.5},{9,8.5},{14,8.5},{19.5,13.5}});
            holdUntil(() -> thief.view.deliveredCount == 3); assertTrue(thief.view.exitsOpen);
            route(new double[][]{{14,8.5},{9,8.5},{9,15.5},{2.5,15.5}});
            holdUntil(() -> thief.result != null);
            sameResult(ResultReason.ESCAPED, Role.THIEF);
            assertEquals(2, thief.result.marks); assertEquals(3, thief.result.deliveredCount);
            assertTrue(thief.result.finalTick < 5400);
            returnBoth();
            String third = startRound(); assertNotEquals(first, third); assertNotEquals(second, third);
            thief.sendMessage("LEAVE_ROOM", "background", null, Map.of("reason", "BACKGROUND"));
            thief.beat(); police.beat(); now.addAndGet(100); server.checkTimeouts().get(3, TimeUnit.SECONDS);
            sameResult(ResultReason.BACKGROUND, Role.POLICE);
        } finally { police.closeBlocking(); thief.closeBlocking(); server.shutdown(); }
    }
}
