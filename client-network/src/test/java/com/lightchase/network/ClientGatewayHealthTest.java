package com.lightchase.network;

import com.lightchase.contracts.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ClientGatewayHealthTest {

    private MockPeerServer server;
    private final WireCodec codec = new WireCodec();
    private final List<WebSocketClientGateway> gateways = new CopyOnWriteArrayList<>();

    private static final String MAP_HASH = "map-hash-v1";
    private static final String CONFIG_HASH = "config-hash-v1";

    @Before
    public void startServer() throws Exception {
        server = new MockPeerServer();
        server.start();
        assertTrue("Server failed to start", server.started.await(5, TimeUnit.SECONDS));
    }

    @After
    public void stopServer() throws Exception {
        for (WebSocketClientGateway g : gateways) {
            try {
                g.close();
            } catch (Throwable ignored) {}
        }
        if (server != null) {
            server.stop(1000);
        }
    }

    private ConnectionOptions defaultOptions() {
        return new ConnectionOptions("127.0.0.1", server.getPort(), "TEST", "test-build", MAP_HASH, CONFIG_HASH);
    }

    private WebSocketClientGateway newGateway(WebSocketClientGateway.TimeSource timeSource,
                                              long heartbeatMs,
                                              long suspectMs,
                                              long disconnectMs,
                                              long leaseMs,
                                              long pulseMs) {
        WebSocketClientGateway gw = new WebSocketClientGateway(timeSource, heartbeatMs, suspectMs, disconnectMs, leaseMs, pulseMs);
        gateways.add(gw);
        return gw;
    }

    // =========================================================================
    // 1. 状态机完整流转测试（CONNECTING -> HANDSHAKING -> JOINING -> CONNECTED -> CLOSED）
    // =========================================================================
    @Test
    public void fullStateMachineTransitionTest() throws Exception {
        BlockingQueue<String> states = new LinkedBlockingQueue<>();
        BlockingQueue<SessionInfo> sessions = new LinkedBlockingQueue<>();
        CountDownLatch closed = new CountDownLatch(1);

        GatewayListener listener = new GatewayListener() {
            @Override public void onSession(SessionInfo session) { sessions.add(session != null ? session : new SessionInfo("NULL", "NULL")); }
            @Override public void onControl(ControlStatus status) {}
            @Override public void onConnection(ConnectionStatus status) {
                states.add(status.state);
                if ("CLOSED".equals(status.state)) closed.countDown();
            }
            @Override public void onRoom(RoomState state) {}
            @Override public void onView(PlayerView view) {}
            @Override public void onResult(MatchResult result) {}
            @Override public void onError(GatewayError error) {}
        };

        WebSocketClientGateway gateway = new WebSocketClientGateway();
        gateways.add(gateway);
        gateway.connect(defaultOptions(), listener);

        // 1. Initial state is CONNECTING, initial session is null
        assertEquals("CONNECTING", states.poll(5, TimeUnit.SECONDS));
        SessionInfo s0 = sessions.poll(5, TimeUnit.SECONDS);
        assertNotNull(s0);
        assertEquals("NULL", s0.sessionId);

        // 2. On socket open -> HANDSHAKING
        assertEquals("HANDSHAKING", states.poll(5, TimeUnit.SECONDS));

        // Client sent HELLO
        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("HELLO", hello.type);

        // Server replies WELCOME -> JOINING
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        assertEquals("JOINING", states.poll(5, TimeUnit.SECONDS));

        // SessionInfo is now published with server IDs
        SessionInfo s1 = sessions.poll(5, TimeUnit.SECONDS);
        assertNotNull(s1);
        assertEquals("sess-001", s1.sessionId);
        assertEquals("p-1", s1.playerId);

        // Client sent JOIN_ROOM
        WireCodec.Message join = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("JOIN_ROOM", join.type);

        // Server sends ROOM_STATE -> CONNECTED
        server.sendRoomState(1, RoomPhase.LOBBY, null);
        assertEquals("CONNECTED", states.poll(5, TimeUnit.SECONDS));

        // 3. Close locally -> CLOSED
        gateway.close();
        assertEquals("CLOSED", states.poll(5, TimeUnit.SECONDS));
        assertTrue(closed.await(5, TimeUnit.SECONDS));

        // Session cleared on close
        SessionInfo sClosed = sessions.poll(5, TimeUnit.SECONDS);
        assertNotNull(sClosed);
        assertEquals("NULL", sClosed.sessionId);

        // Ensure no extra unexpected states
        assertTrue(states.isEmpty());
    }

    // =========================================================================
    // 2. 心跳维持与 PONG 响应测试
    // =========================================================================
    @Test
    public void heartbeatMaintenanceAndPongVerificationTest() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        BlockingQueue<GatewayError> errors = new LinkedBlockingQueue<>();
        CountDownLatch closedLatch = new CountDownLatch(1);

        WebSocketClientGateway gateway = newGateway(now::get, 1000, 3000, 5000, 250, 0);

        gateway.connect(defaultOptions(), new TestListener() {
            @Override public void onError(GatewayError error) { errors.add(error); }
            @Override public void onConnection(ConnectionStatus s) { if ("CLOSED".equals(s.state)) closedLatch.countDown(); }
        });

        // Handshake
        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        server.pollMessage(5, TimeUnit.SECONDS); // JOIN_ROOM
        server.sendRoomState(1, RoomPhase.LOBBY, null);

        // Advance 1000ms: pulse sends HEARTBEAT
        now.addAndGet(1000);
        gateway.pulse();

        WireCodec.Message hb1 = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("HEARTBEAT", hb1.type);
        long nonce1 = WireCodec.integer(hb1.body, "nonce");
        assertEquals(now.get(), nonce1);

        // Valid PONG reply
        server.sendPong(2, nonce1, now.get());
        Thread.sleep(50);
        assertNull(errors.poll(100, TimeUnit.MILLISECONDS)); // No error, accepted!

        // Advance another 1000ms: second HEARTBEAT
        now.addAndGet(1000);
        gateway.pulse();
        WireCodec.Message hb2 = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("HEARTBEAT", hb2.type);
        long nonce2 = WireCodec.integer(hb2.body, "nonce");

        // Rogue PONG reply with mismatched nonce
        server.sendPong(3, nonce2 + 9999, now.get());

        GatewayError err = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull("Expected BAD_MESSAGE on invalid PONG nonce", err);
        assertEquals("BAD_MESSAGE", err.code);
        assertFalse(err.recoverable);
        assertTrue("Gateway should close after fatal BAD_MESSAGE", closedLatch.await(5, TimeUnit.SECONDS));
    }

    // =========================================================================
    // 3. 3秒未收到包触发 SUSPECT、收到后恢复 CONNECTED 的测试
    // =========================================================================
    @Test
    public void suspectAfter3SecondsNoPacketsAndRecoveredOnPacket() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        BlockingQueue<String> states = new LinkedBlockingQueue<>();

        WebSocketClientGateway gateway = newGateway(now::get, 1000, 3000, 5000, 250, 0);

        gateway.connect(defaultOptions(), new TestListener() {
            @Override public void onConnection(ConnectionStatus s) { states.add(s.state); }
        });

        // Complete handshake
        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        server.pollMessage(5, TimeUnit.SECONDS); // JOIN_ROOM
        server.sendRoomState(1, RoomPhase.LOBBY, null);

        // Drain states up to CONNECTED
        assertEquals("CONNECTING", states.poll(5, TimeUnit.SECONDS));
        assertEquals("HANDSHAKING", states.poll(5, TimeUnit.SECONDS));
        assertEquals("JOINING", states.poll(5, TimeUnit.SECONDS));
        assertEquals("CONNECTED", states.poll(5, TimeUnit.SECONDS));

        // Advance 2999ms -> NOT suspect yet
        now.addAndGet(2999);
        gateway.pulse();
        assertNull(states.poll(100, TimeUnit.MILLISECONDS));

        // Advance to 3000ms -> SUSPECT triggered
        now.addAndGet(1);
        gateway.pulse();
        assertEquals("SUSPECT", states.poll(5, TimeUnit.SECONDS));

        // While in SUSPECT, server sends a valid packet (e.g. ROOM_STATE)
        now.addAndGet(50);
        server.sendRoomState(2, RoomPhase.LOBBY, null);

        // Restored to CONNECTED immediately upon receiving the packet!
        assertEquals("CONNECTED", states.poll(5, TimeUnit.SECONDS));
    }

    // =========================================================================
    // 4. 5秒超时触发 CONNECTION_LOST 并 CLOSED 的测试
    // =========================================================================
    @Test
    public void connectionLostAfter5SecondsNoPackets() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        BlockingQueue<GatewayError> errors = new LinkedBlockingQueue<>();
        BlockingQueue<String> states = new LinkedBlockingQueue<>();
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);

        WebSocketClientGateway gateway = newGateway(now::get, 1000, 3000, 5000, 250, 0);

        gateway.connect(defaultOptions(), new TestListener() {
            @Override public void onRoom(RoomState room) { joined.countDown(); }
            @Override public void onError(GatewayError e) { errors.add(e); }
            @Override public void onConnection(ConnectionStatus s) {
                states.add(s.state);
                if ("CLOSED".equals(s.state)) closed.countDown();
            }
        });

        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        WireCodec.Message join = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendAck(join.requestId, "JOIN_ROOM");
        server.sendRoomState(1, RoomPhase.LOBBY, null);
        assertTrue("Receive initial room before advancing the fake timeout clock", joined.await(5, TimeUnit.SECONDS));

        // Advance to 3000ms -> SUSPECT
        now.addAndGet(3000);
        gateway.pulse();

        // Advance to 5000ms -> CONNECTION_LOST
        now.addAndGet(2000);
        gateway.pulse();

        GatewayError err = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull("Expected CONNECTION_LOST error", err);
        assertEquals("CONNECTION_LOST", err.code);

        assertTrue("Gateway must be CLOSED after 5s timeout", closed.await(5, TimeUnit.SECONDS));
        assertTrue(states.contains("CLOSED"));
    }

    // =========================================================================
    // 5. 250ms 输入租约超时发送零向量测试
    // =========================================================================
    @Test
    public void inputLease250msTimeoutSendsZeroVectorOnce() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        WebSocketClientGateway gateway = newGateway(now::get, 1000, 3000, 5000, 250, 50);

        gateway.connect(defaultOptions(), new TestListener());

        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        server.pollMessage(5, TimeUnit.SECONDS);

        // Enter RUNNING match
        server.sendRoomState(1, RoomPhase.RUNNING, "m-001");
        Thread.sleep(100);

        // 1. Submit active input frame (edge jump: interactHeld = true)
        gateway.submitInput(new InputFrame(1.0, -1.0, true, false));

        // Immediately sent due to edge jump
        WireCodec.Message f1 = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("INPUT", f1.type);
        assertEquals(1.0, f1.body.get("moveX").getAsDouble(), 1e-6);
        assertEquals(-1.0, f1.body.get("moveY").getAsDouble(), 1e-6);
        assertTrue(WireCodec.bool(f1.body, "interactHeld"));
        assertTrue(f1.body.get("dropId").isJsonNull());

        // 2. Periodic pulse at 50ms (within 250ms lease) sends regular frame
        now.addAndGet(50);
        gateway.pulse();
        WireCodec.Message f2 = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("INPUT", f2.type);
        assertEquals(1.0, f2.body.get("moveX").getAsDouble(), 1e-6);

        // 3. User does not submit any input for 250ms: advance to 250ms since last submit
        now.addAndGet(200); // 50 + 200 = 250ms
        gateway.pulse();

        // Gateway sends zero vector once!
        WireCodec.Message zeroFrame = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("INPUT", zeroFrame.type);
        assertEquals(0.0, zeroFrame.body.get("moveX").getAsDouble(), 1e-6);
        assertEquals(0.0, zeroFrame.body.get("moveY").getAsDouble(), 1e-6);
        assertFalse(WireCodec.bool(zeroFrame.body, "interactHeld"));
        assertTrue(zeroFrame.body.get("dropId").isJsonNull());

        // 4. Subsequent pulse: zero vector should NOT be continuously re-sent!
        now.addAndGet(50);
        gateway.pulse();
        WireCodec.Message none = server.pollMessage(150, TimeUnit.MILLISECONDS);
        assertNull("Zero vector must only be sent once upon lease expiry", none);

        // 5. User resumes submitting input
        now.addAndGet(50);
        gateway.submitInput(new InputFrame(0.5, 0.5, false, false));

        // Periodic sampling resumes
        now.addAndGet(50);
        gateway.pulse();
        WireCodec.Message resumed = server.pollMessage(5, TimeUnit.SECONDS);
        assertNotNull(resumed);
        assertEquals(0.5, resumed.body.get("moveX").getAsDouble(), 1e-6);
    }

    // =========================================================================
    // 6. drop 瞬时脉冲单调分配 dropId 与清空测试
    // =========================================================================
    @Test
    public void dropPulseMonotonicAllocationAndClearTest() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        WebSocketClientGateway gateway = newGateway(now::get, 1000, 3000, 5000, 250, 50);

        gateway.connect(defaultOptions(), new TestListener());
        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        server.pollMessage(5, TimeUnit.SECONDS);
        server.sendRoomState(1, RoomPhase.RUNNING, "m-001");
        Thread.sleep(50);

        // First drop pulse
        gateway.submitInput(new InputFrame(0.0, 0.0, false, true));
        WireCodec.Message d1 = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("INPUT", d1.type);
        assertEquals(1L, WireCodec.integer(d1.body, "dropId"));

        // Next periodic frame has dropId=null
        now.addAndGet(50);
        gateway.pulse();
        WireCodec.Message p1 = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("INPUT", p1.type);
        assertTrue("dropId must be null in subsequent frames", p1.body.get("dropId").isJsonNull());

        // Submit frame without drop
        gateway.submitInput(new InputFrame(0.0, 0.0, false, false));
        now.addAndGet(50);
        gateway.pulse();
        WireCodec.Message p2 = server.pollMessage(5, TimeUnit.SECONDS);
        assertTrue(p2.body.get("dropId").isJsonNull());

        // Second drop pulse: allocated dropId must be 2
        gateway.submitInput(new InputFrame(0.0, 0.0, false, true));
        WireCodec.Message d2 = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("INPUT", d2.type);
        assertEquals(2L, WireCodec.integer(d2.body, "dropId"));

        // Subsequent pulse: cleared again
        now.addAndGet(50);
        gateway.pulse();
        WireCodec.Message p3 = server.pollMessage(5, TimeUnit.SECONDS);
        assertTrue(p3.body.get("dropId").isJsonNull());
    }

    // =========================================================================
    // 7. GAME_OVER 接收时停止输入并自动回复 RESULT_ACK 测试
    // =========================================================================
    @Test
    public void gameOverStopsInputAndRepliesResultAckTest() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        WebSocketClientGateway gateway = newGateway(now::get, 1000, 3000, 5000, 250, 50);
        BlockingQueue<MatchResult> results = new LinkedBlockingQueue<>();

        gateway.connect(defaultOptions(), new TestListener() {
            @Override public void onResult(MatchResult result) { results.add(result); }
        });

        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        server.pollMessage(5, TimeUnit.SECONDS);
        server.sendRoomState(1, RoomPhase.RUNNING, "m-001");
        Thread.sleep(50);

        // Submit initial movement
        gateway.submitInput(new InputFrame(1.0, 0.0, false, false));
        now.addAndGet(50);
        gateway.pulse();
        assertNotNull(server.pollMessage(5, TimeUnit.SECONDS));

        // Server broadcasts GAME_OVER
        MatchResult mr = new MatchResult("m-001:result", "m-001", Role.POLICE, ResultReason.CAPTURED, null, 600, 3, 1, 600);
        server.sendGameOver(10, mr);

        // 1. Gateway calls listener.onResult once
        MatchResult receivedResult = results.poll(5, TimeUnit.SECONDS);
        assertNotNull(receivedResult);
        assertEquals("m-001:result", receivedResult.resultId);
        assertEquals(Role.POLICE, receivedResult.winner);

        // 2. Gateway automatically replies RESULT_ACK
        WireCodec.Message ack = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("RESULT_ACK", ack.type);
        assertEquals("m-001", ack.matchId);
        assertEquals("m-001:result", WireCodec.string(ack.body, "resultId"));

        // 3. Regular input streaming is stopped
        now.addAndGet(50);
        gateway.pulse();
        now.addAndGet(50);
        gateway.pulse();
        assertNull("Input streaming must stop after GAME_OVER", server.pollMessage(150, TimeUnit.MILLISECONDS));

        // 4. Duplicate GAME_OVER does not trigger onResult again, but re-sends RESULT_ACK
        server.sendGameOver(11, mr);
        WireCodec.Message dupAck = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("RESULT_ACK", dupAck.type);
        assertNull("Duplicate result must not be reported to listener", results.poll(150, TimeUnit.MILLISECONDS));
    }

    // =========================================================================
    // 8. 重复 connect 报错 ALREADY_CONNECTED 测试
    // =========================================================================
    @Test
    public void duplicateConnectReportsAlreadyConnected() throws Exception {
        WebSocketClientGateway gateway = new WebSocketClientGateway();
        gateways.add(gateway);

        BlockingQueue<String> listenerAEvents = new LinkedBlockingQueue<>();
        BlockingQueue<GatewayError> listenerBErrors = new LinkedBlockingQueue<>();

        gateway.connect(defaultOptions(), new TestListener() {
            @Override public void onConnection(ConnectionStatus s) { listenerAEvents.add(s.state); }
        });

        // Duplicate connect call with listenerB
        gateway.connect(defaultOptions(), new TestListener() {
            @Override public void onError(GatewayError e) { listenerBErrors.add(e); }
        });

        GatewayError err = listenerBErrors.poll(5, TimeUnit.SECONDS);
        assertNotNull("Duplicate connect must report error", err);
        assertEquals("ALREADY_CONNECTED", err.code);

        // Original connection remains intact
        assertEquals("CONNECTING", listenerAEvents.poll(5, TimeUnit.SECONDS));
    }

    // =========================================================================
    // 9. close 幂等性测试
    // =========================================================================
    @Test
    public void closeIdempotencyTest() throws Exception {
        BlockingQueue<String> states = new LinkedBlockingQueue<>();
        BlockingQueue<SessionInfo> sessions = new LinkedBlockingQueue<>();

        WebSocketClientGateway gateway = new WebSocketClientGateway();
        gateways.add(gateway);

        gateway.connect(defaultOptions(), new TestListener() {
            @Override public void onSession(SessionInfo s) { sessions.add(s != null ? s : new SessionInfo("NULL", "NULL")); }
            @Override public void onConnection(ConnectionStatus s) { states.add(s.state); }
        });

        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        server.pollMessage(5, TimeUnit.SECONDS);
        server.sendRoomState(1, RoomPhase.LOBBY, null);
        Thread.sleep(50);

        // Clear previous state events
        states.clear();
        sessions.clear();

        // First close
        gateway.close();
        assertEquals("CLOSED", states.poll(5, TimeUnit.SECONDS));
        assertEquals("NULL", sessions.poll(5, TimeUnit.SECONDS).sessionId);

        // Second and third close calls: must be completely no-op!
        gateway.close();
        gateway.close();

        assertNull("Subsequent close calls must not emit additional states", states.poll(100, TimeUnit.MILLISECONDS));
        assertNull("Subsequent close calls must not emit additional session resets", sessions.poll(100, TimeUnit.MILLISECONDS));
        assertEquals("CLOSED", gateway.getConnectionState());
        assertTrue(gateway.isClosedState());
    }

    // =========================================================================
    // 10. 异常隔离（listener 抛异常不影响网关后续运行）测试
    // =========================================================================
    @Test
    public void exceptionIsolationTest() throws Exception {
        CountDownLatch receivedRoom = new CountDownLatch(1);

        // A hostile listener that throws exceptions on every single callback
        GatewayListener crashingListener = new GatewayListener() {
            @Override public void onSession(SessionInfo session) { throw new RuntimeException("Listener error in onSession"); }
            @Override public void onControl(ControlStatus status) { throw new RuntimeException("Listener error in onControl"); }
            @Override public void onConnection(ConnectionStatus status) { throw new RuntimeException("Listener error in onConnection"); }
            @Override public void onRoom(RoomState state) {
                receivedRoom.countDown();
                throw new RuntimeException("Listener error in onRoom");
            }
            @Override public void onView(PlayerView view) { throw new RuntimeException("Listener error in onView"); }
            @Override public void onResult(MatchResult result) { throw new RuntimeException("Listener error in onResult"); }
            @Override public void onError(GatewayError error) { throw new RuntimeException("Listener error in onError"); }
        };

        WebSocketClientGateway gateway = new WebSocketClientGateway();
        gateways.add(gateway);

        // Connect with crashing listener
        gateway.connect(defaultOptions(), crashingListener);

        // Server responds to HELLO and sends ROOM_STATE
        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        WireCodec.Message join = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("JOIN_ROOM", join.type);

        server.sendRoomState(1, RoomPhase.LOBBY, null);

        // Despite exceptions in onSession, onConnection, onRoom: gateway does NOT crash and reaches room!
        assertTrue("Gateway must continue running despite listener exceptions", receivedRoom.await(5, TimeUnit.SECONDS));
        assertEquals("CONNECTED", gateway.getConnectionState());

        // Gateway can also safely close without crashing
        gateway.close();
        assertEquals("CLOSED", gateway.getConnectionState());
    }

    // =========================================================================
    // 11. background() 发送 LEAVE_ROOM(reason=BACKGROUND) 并本地安全关闭
    // =========================================================================
    @Test
    public void backgroundSendsLeaveRoomAndClosesLocally() throws Exception {
        WebSocketClientGateway gateway = newGateway(() -> 10_000, 1000, 3000, 5000, 250, 0);

        gateway.connect(defaultOptions(), new TestListener());
        WireCodec.Message hello = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendWelcome(hello.requestId, MAP_HASH, CONFIG_HASH);
        WireCodec.Message join = server.pollMessage(5, TimeUnit.SECONDS);
        server.sendAck(join.requestId, "JOIN_ROOM");
        server.sendRoomState(1, RoomPhase.RUNNING, "m-001");
        Thread.sleep(50);

        gateway.background();

        WireCodec.Message leave = server.pollMessage(5, TimeUnit.SECONDS);
        assertEquals("LEAVE_ROOM", leave.type);
        assertEquals("BACKGROUND", WireCodec.string(leave.body, "reason"));
        assertEquals("CLOSED", gateway.getConnectionState());
        assertTrue(gateway.isClosedState());
    }

    // =========================================================================
    // Helper Mock Server
    // =========================================================================
    static class MockPeerServer extends WebSocketServer {
        final BlockingQueue<WireCodec.Message> messages = new LinkedBlockingQueue<>();
        final CountDownLatch started = new CountDownLatch(1);
        final WireCodec codec = new WireCodec();
        volatile WebSocket conn;
        private long seqCounter = 0;

        MockPeerServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
        }

        @Override public void onOpen(WebSocket c, ClientHandshake h) { seqCounter = 0; this.conn = c; }
        @Override public void onClose(WebSocket c, int code, String reason, boolean remote) {}
        @Override
        public void onMessage(WebSocket c, String text) {
            this.conn = c;
            try {
                messages.add(codec.decodeClient(text));
            } catch (Exception ignored) {}
        }
        @Override public void onError(WebSocket c, Exception ex) {}
        @Override public void onStart() { started.countDown(); }

        WireCodec.Message pollMessage(long timeout, TimeUnit unit) throws InterruptedException {
            return messages.poll(timeout, unit);
        }

        void sendWelcome(String requestId, String mapHash, String configHash) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", "sess-001");
            body.put("playerId", "p-1");
            body.put("protocolVersion", 1);
            body.put("mapHash", mapHash);
            body.put("configHash", configHash);
            body.put("heartbeatMs", 1000);
            body.put("disconnectMs", 5000);
            conn.send(codec.encode("WELCOME", ++seqCounter, requestId, null, body));
        }

        void sendAck(String requestId, String action) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", action);
            conn.send(codec.encode("ACK", ++seqCounter, requestId, null, body));
        }

        void sendRoomState(long rev, RoomPhase phase, String matchId) {
            boolean active = phase != RoomPhase.LOBBY;
            List<RoomPlayer> players = new ArrayList<>();
            players.add(new RoomPlayer("p-1", Role.POLICE, active, "CONNECTED", false));
            if (active) players.add(new RoomPlayer("p-2", Role.THIEF, true, "CONNECTED", false));
            RoomState room = new RoomState("TEST", rev, phase, "test-room", MAP_HASH, CONFIG_HASH,
                    matchId, phase == RoomPhase.COUNTDOWN ? 3000 : 0, players);
            conn.send(codec.encode("ROOM_STATE", ++seqCounter, null, null, room));
        }

        void sendPong(long seq, long nonce, long serverTimeMs) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nonce", nonce);
            body.put("serverTimeMs", serverTimeMs);
            conn.send(codec.encode("PONG", ++seqCounter, null, null, body));
        }

        void sendGameOver(long seq, MatchResult result) {
            conn.send(codec.encode("GAME_OVER", ++seqCounter, null, result.matchId, result));
        }
    }

    static class TestListener implements GatewayListener {
        @Override public void onSession(SessionInfo session) {}
        @Override public void onControl(ControlStatus status) {}
        @Override public void onConnection(ConnectionStatus status) {}
        @Override public void onRoom(RoomState state) {}
        @Override public void onView(PlayerView view) {}
        @Override public void onResult(MatchResult result) {}
        @Override public void onError(GatewayError error) {}
    }
}
