package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.game.V1EngineFactory;
import com.lightchase.network.WireCodec;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Stall the consumer, not the network: overload must be rejected before logic execution. */
public class LobbyInboundBackpressureTest {
    private final DataFiles data = DataFiles.bundled();
    private final WireCodec codec = new WireCodec();
    private final AtomicLong time = new AtomicLong(1000);
    private final CountDownLatch release = new CountDownLatch(1);
    private LobbyServer server;
    private ScheduledExecutorService logic;
    private final List<PlayerCommand> consumed = new CopyOnWriteArrayList<>();

    private final class Peer {
        final BlockingQueue<WireCodec.Message> messages = new LinkedBlockingQueue<>();
        final AtomicBoolean open = new AtomicBoolean(true);
        final AtomicInteger closes = new AtomicInteger();
        volatile String reason;
        final WebSocket socket;
        long seq;
        Peer() {
            socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "equals": return proxy == args[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            case "isOpen": return open.get();
                            case "send": messages.add(codec.decodeServer((String) args[0])); return null;
                            case "close":
                            case "closeConnection":
                                closes.incrementAndGet(); reason = (String) args[1];
                                if (open.getAndSet(false)) server.onClose((WebSocket) proxy, (int) args[0], reason, false);
                                return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    @Before public void start() throws Exception {
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data, time::get, false,
                (map, rules, init) -> {
                    MatchEngine engine = new V1EngineFactory().create(map, rules, init);
                    return new MatchEngine() {
                        public StepResult step(StepInput input) { consumed.addAll(input.commands); return engine.step(input); }
                        public PlayerView viewFor(String id) { return engine.viewFor(id); }
                        public MatchResult result() { return engine.result(); }
                        public MatchResult abort() { return engine.abort(); }
                    };
                });
        Field field = LobbyServer.class.getDeclaredField("logic"); field.setAccessible(true);
        logic = (ScheduledExecutorService) field.get(server);
    }

    @After public void stop() throws Exception { release.countDown(); server.shutdown(); }

    @Test public void exactly128MessagesKeepFifoAndConsumedCapacityCanBeReused() throws Exception {
        Peer peer = open();
        blockLogic();
        for (int i = 0; i < 128; i++) heartbeat(peer, i);
        assertTrue(peer.open.get());
        release.countDown(); sync();
        for (int i = 0; i < 128; i++) assertEquals(i, WireCodec.integer(take(peer, "PONG").body, "nonce"));
        for (int i = 0; i < 128; i++) { heartbeat(peer, 128 + i); sync(); }
        for (int i = 0; i < 128; i++) assertEquals(128 + i, WireCodec.integer(take(peer, "PONG").body, "nonce"));
        assertTrue(peer.open.get()); assertEquals(0, peer.closes.get());
    }

    @Test public void message129ClosesOnlyTheOverloadedConnectionBeforeConsumerResumes() throws Exception {
        Peer bad = open(), healthy = open();
        blockLogic();
        for (int i = 0; i < 1000; i++) heartbeat(bad, i);
        heartbeat(healthy, 42);
        assertFalse("Overflow must close without waiting for the logic thread", bad.open.get());
        assertEquals("INBOUND_QUEUE_FULL", bad.reason); assertEquals(1, bad.closes.get());
        assertTrue(healthy.open.get());
        release.countDown(); sync();
        assertEquals(42, WireCodec.integer(take(healthy, "PONG").body, "nonce"));
        assertNull("Overloaded connection must not consume its queued commands", bad.messages.poll());
    }

    @Test public void binaryMessagesShareTheSameIngressBound() throws Exception {
        Peer peer = open(); blockLogic();
        for (int i = 0; i < 128; i++) server.onMessage(peer.socket, ByteBuffer.wrap(new byte[]{1}));
        assertTrue(peer.open.get());
        server.onMessage(peer.socket, ByteBuffer.wrap(new byte[]{1}));
        assertFalse(peer.open.get()); assertEquals("INBOUND_QUEUE_FULL", peer.reason);
        release.countDown(); sync();
    }

    @Test public void overloadDuringMatchBecomesAnAuthoritativeDisconnectResult() throws Exception {
        Peer thief = open(), police = open();
        startMatch(thief, police);
        take(thief, "SNAPSHOT"); take(police, "SNAPSHOT");
        thief.messages.clear(); police.messages.clear();
        blockLogic();
        for (int i = 0; i < 129; i++) heartbeat(thief, i + 2);
        assertFalse(thief.open.get());
        release.countDown(); sync();
        time.addAndGet(34); server.checkTimeouts().get(3, TimeUnit.SECONDS);
        MatchResult result = codec.body(take(police, "GAME_OVER"), MatchResult.class);
        assertEquals(ResultReason.DISCONNECT, result.reason); assertEquals(Role.POLICE, result.winner);
        assertTrue(police.open.get());
        Peer replacement = open();
        send(replacement, "JOIN_ROOM", "join", Map.of("roomId", "TEST")); sync();
        WireCodec.Message busy = take(replacement, "ERROR");
        assertEquals("ROOM_BUSY", WireCodec.string(busy.body, "code"));
        assertFalse(WireCodec.bool(busy.body, "fatal")); assertTrue(replacement.open.get());
        police.messages.clear();
        server.onMessage(police.socket, codec.encode("RETURN_LOBBY", ++police.seq, "return", result.matchId,
                Map.of("resultId", result.resultId))); sync();
        take(police, "ACK");
        assertEquals(RoomPhase.LOBBY, codec.body(take(police, "ROOM_STATE"), RoomState.class).phase);
        send(replacement, "JOIN_ROOM", "join-again", Map.of("roomId", "TEST")); sync();
        take(replacement, "ACK");
        assertEquals(2, codec.body(take(replacement, "ROOM_STATE"), RoomState.class).players.size());
    }

    @Test public void queuedControlsRemainOrderedBelowCapacity() throws Exception {
        Peer peer = open(); blockLogic();
        // ACK order and final authority must both reflect the original control order.
        send(peer, "JOIN_ROOM", "join", Map.of("roomId", "FIFO"));
        send(peer, "SELECT_ROLE", "role", Map.of("role", "THIEF"));
        send(peer, "SET_READY", "press", Map.of("ready", true));
        send(peer, "SET_READY", "release", Map.of("ready", false));
        release.countDown(); sync();
        for (String id : Arrays.asList("join", "role", "press", "release")) assertEquals(id, take(peer, "ACK").requestId);
        RoomState last = null;
        for (WireCodec.Message m : peer.messages) if (m.type.equals("ROOM_STATE")) last = codec.body(m, RoomState.class);
        assertNotNull(last); assertFalse(last.players.get(0).ready);
    }

    @Test public void queuedInputPressReleaseAndDropReachEngineInOriginalOrder() throws Exception {
        Peer thief = open(), police = open(); startMatch(thief, police);
        String match = codec.body(take(thief, "SNAPSHOT"), PlayerView.class).matchId;
        take(police, "SNAPSHOT");
        blockLogic();
        for (int i = 1; i <= 4; i++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inputSeq", i); body.put("moveX", 0); body.put("moveY", 0);
            body.put("interactHeld", i == 1 || i == 4); body.put("dropId", i == 3 ? 1L : null);
            server.onMessage(thief.socket, codec.encode("INPUT", ++thief.seq, null, match, body));
        }
        release.countDown(); sync(); time.addAndGet(34);
        server.checkTimeouts().get(3, TimeUnit.SECONDS);
        assertEquals(4, consumed.size());
        for (int i = 0; i < 4; i++) assertEquals(i + 1, consumed.get(i).inputSeq);
        assertTrue(consumed.get(0).interactHeld); assertFalse(consumed.get(1).interactHeld);
        assertFalse(consumed.get(2).interactHeld); assertEquals(Long.valueOf(1), consumed.get(2).dropId);
        assertTrue(consumed.get(3).interactHeld); assertNull(consumed.get(3).dropId);
    }

    @Test public void realWebSocketFloodIsClosedWhileOtherClientRemainsUsable() throws Exception {
        server.start(); server.awaitStart();
        RawPeer bad = new RawPeer(), healthy = new RawPeer();
        try {
            assertTrue(bad.connectBlocking(3, TimeUnit.SECONDS));
            assertTrue(healthy.connectBlocking(3, TimeUnit.SECONDS));
            bad.hello(); healthy.hello();
            assertEquals("WELCOME", bad.take().type); assertEquals("WELCOME", healthy.take().type);
            blockLogic();
            for (int i = 0; i < 1000; i++) {
                try { bad.send(codec.encode("HEARTBEAT", i + 2, null, null, Map.of("nonce", i))); }
                catch (WebsocketNotConnectedException e) { break; }
            }
            assertTrue("Network ingress must terminate the flood while logic is blocked", bad.closed.await(3, TimeUnit.SECONDS));
            // A forced transport termination does not promise delivery of a WebSocket close reason.
            assertFalse(bad.isOpen()); assertTrue(healthy.isOpen());
            release.countDown(); sync();
            healthy.send(codec.encode("HEARTBEAT", 2, null, null, Map.of("nonce", 42)));
            WireCodec.Message pong = healthy.take(); assertEquals("PONG", pong.type);
            assertEquals(42, WireCodec.integer(pong.body, "nonce"));
        } finally { release.countDown(); bad.closeBlocking(); healthy.closeBlocking(); }
    }

    private final class RawPeer extends WebSocketClient {
        final BlockingQueue<WireCodec.Message> messages = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile String reason;
        RawPeer() { super(URI.create("ws://127.0.0.1:" + server.getPort() + "/game")); setConnectionLostTimeout(0); }
        public void onOpen(ServerHandshake h) {}
        public void onMessage(String text) { messages.add(codec.decodeServer(text)); }
        public void onClose(int c, String r, boolean remote) { reason = r; closed.countDown(); }
        public void onError(Exception e) {}
        void hello() { send(codec.encode("HELLO", 1, "hello", null, helloBody())); }
        WireCodec.Message take() throws Exception {
            WireCodec.Message m = messages.poll(3, TimeUnit.SECONDS); assertNotNull(m); return m;
        }
    }

    private Peer open() throws Exception {
        Peer peer = new Peer();
        ClientHandshake handshake = (ClientHandshake) Proxy.newProxyInstance(ClientHandshake.class.getClassLoader(),
                new Class<?>[]{ClientHandshake.class}, (p, m, a) -> "/game");
        server.onOpen(peer.socket, handshake);
        send(peer, "HELLO", "hello", helloBody()); sync(); take(peer, "WELCOME"); return peer;
    }

    private Map<String, Object> helloBody() {
        return Map.of("clientBuild", "TEST_ONLY", "mapHash", data.mapHash, "configHash", data.configHash);
    }

    private void prepare(Peer peer, Role role) throws Exception {
        send(peer, "JOIN_ROOM", "join", Map.of("roomId", "TEST"));
        send(peer, "SELECT_ROLE", "role", Map.of("role", role.name()));
        sync();
    }

    private void startMatch(Peer thief, Peer police) throws Exception {
        prepare(thief, Role.THIEF); prepare(police, Role.POLICE);
        send(thief, "SET_READY", "ready", Map.of("ready", true));
        send(police, "SET_READY", "ready", Map.of("ready", true)); sync();
        time.addAndGet(3000); heartbeat(thief, 1); heartbeat(police, 1); sync();
        server.checkTimeouts().get(3, TimeUnit.SECONDS);
    }

    private void send(Peer p, String type, String request, Object body) {
        server.onMessage(p.socket, codec.encode(type, ++p.seq, request, null, body));
    }

    private void heartbeat(Peer p, int nonce) { send(p, "HEARTBEAT", null, Map.of("nonce", nonce)); }
    private void sync() throws Exception { logic.submit(() -> {}).get(3, TimeUnit.SECONDS); }
    private void blockLogic() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        logic.execute(() -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Logic fence timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
    }

    private WireCodec.Message take(Peer p, String type) throws Exception {
        WireCodec.Message m;
        while ((m = p.messages.poll(3, TimeUnit.SECONDS)) != null) if (type.equals(m.type)) return m;
        throw new AssertionError("Missing " + type);
    }
}
