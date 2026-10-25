package com.lightchase.network;

import com.lightchase.contracts.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.*;
import static org.junit.Assert.*;

/** Real transport responses exercise correlation before any identity or room is published. */
public class GatewayHandshakeContractTest {
    private final WireCodec codec = new WireCodec();
    private final AtomicLong time = new AtomicLong(1000);
    private final BlockingQueue<WireCodec.Message> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> states = new LinkedBlockingQueue<>();
    private final BlockingQueue<GatewayError> errors = new LinkedBlockingQueue<>();
    private final List<ControlStatus> controls = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile WebSocket connection;
    private WebSocketServer peer;
    private WebSocketClientGateway gateway;
    private WireCodec.Message hello;
    private final GatewayListener listener = new GatewayListener() {
        public void onSession(SessionInfo s) { events.add(s == null ? "CLEAR" : "SESSION"); }
        public void onControl(ControlStatus s) { controls.add(s); }
        public void onConnection(ConnectionStatus s) { states.add(s.state); }
        public void onRoom(RoomState s) { events.add("ROOM"); }
        public void onView(PlayerView v) {}
        public void onResult(MatchResult r) {}
        public void onError(GatewayError e) { errors.add(e); }
    };

    @Before public void start() throws Exception {
        peer = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            public void onStart() { started.countDown(); }
            public void onOpen(WebSocket c, ClientHandshake h) { connection = c; }
            public void onClose(WebSocket c, int code, String reason, boolean remote) {}
            public void onError(WebSocket c, Exception e) { throw new AssertionError(e); }
            public void onMessage(WebSocket c, String raw) { received.add(codec.decodeClient(raw)); }
        };
        peer.setConnectionLostTimeout(0);
        peer.start(); assertTrue(started.await(3, TimeUnit.SECONDS));
        gateway = new WebSocketClientGateway(time::get, 1000, 3000, 5000, 250, 0);
        connect();
    }

    @After public void stop() throws Exception { gateway.close(); peer.stop(1000); }

    @Test public void matchingWelcomePublishesIdentityBeforeRoomAndCompletesHelloOnce() throws Exception {
        join(1, 2, 3);
        assertEquals(Arrays.asList("CLEAR", "SESSION", "ROOM"), events);
        assertEquals(1, controls.stream().filter(s -> s.action.equals("HELLO")
                && s.state == ControlStatus.State.SUCCEEDED).count());
        assertNull(errors.poll());
    }

    @Test public void unrelatedWelcomeCannotPublishIdentityOrSendJoin() throws Exception {
        welcome(1, "not-the-hello-id");
        rejectedBeforeIdentity();
    }

    @Test public void welcomeCannotCompleteAnotherPendingControl() throws Exception {
        gateway.selectRole(Role.POLICE);
        WireCodec.Message role = take("SELECT_ROLE");
        welcome(1, role.requestId);
        rejectedBeforeIdentity();
    }

    @Test public void welcomeAfterHelloDeadlineCannotPublishIdentity() throws Exception {
        time.addAndGet(5000);
        welcome(1, hello.requestId);
        state("CLOSED");
        assertEquals(Collections.singletonList("CLEAR"), events.subList(0, 1));
        assertFalse(events.contains("SESSION"));
        assertNull(received.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(1, controls.stream().filter(s -> s.action.equals("HELLO")
                && s.state == ControlStatus.State.TIMED_OUT).count());
    }

    @Test public void duplicateWelcomeCannotReplacePublishedIdentity() throws Exception {
        join(1, 2, 3);
        welcome(4, hello.requestId);
        GatewayError error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull(error); assertEquals("BAD_MESSAGE", error.code);
        state("CLOSED");
        assertEquals(1, events.stream().filter("SESSION"::equals).count());
        assertEquals(1, controls.stream().filter(s -> s.action.equals("HELLO")
                && s.state == ControlStatus.State.SUCCEEDED).count());
    }

    @Test public void firstServerSequenceMustBeOne() throws Exception {
        welcome(7, hello.requestId);
        rejectedBeforeIdentity();
    }

    @Test public void laterSequencesMayHaveGaps() throws Exception {
        join(1, 7, 12);
        assertNull(errors.poll());
    }

    @Test public void reconnectResetsFirstServerSequenceAndRequestTracking() throws Exception {
        join(1, 2, 3);
        gateway.close(); state("CLOSED");
        states.clear(); received.clear(); controls.clear(); events.clear();
        connect(); join(1, 2, 3);
        assertEquals(Arrays.asList("CLEAR", "SESSION", "ROOM"), events);
        assertNull(errors.poll());
    }

    @Test public void returnAfterCloseProducesPendingThenNotConnected() throws Exception {
        join(1, 2, 3);
        gateway.close(); controls.clear(); errors.clear();
        gateway.returnToLobby();
        assertEquals(2, controls.size());
        assertEquals(ControlStatus.State.PENDING, controls.get(0).state);
        assertEquals(ControlStatus.State.FAILED, controls.get(1).state);
        assertEquals("NOT_CONNECTED", controls.get(1).errorCode);
        GatewayError error = errors.poll(); assertNotNull(error);
        assertEquals("NOT_CONNECTED", error.code); assertTrue(error.recoverable);
    }

    @Test public void returnInConnectedLobbyRemainsRecoverableInvalidPhase() throws Exception {
        join(1, 2, 3); controls.clear();
        gateway.returnToLobby();
        assertEquals(2, controls.size());
        assertEquals(ControlStatus.State.PENDING, controls.get(0).state);
        assertEquals(ControlStatus.State.FAILED, controls.get(1).state);
        assertEquals("INVALID_PHASE", controls.get(1).errorCode);
        GatewayError error = errors.poll(); assertNotNull(error); assertTrue(error.recoverable);
        assertEquals("CONNECTED", gateway.getConnectionState());
    }

    private void connect() throws Exception {
        gateway.connect(new ConnectionOptions("127.0.0.1", peer.getPort(), "TEST", "TEST_ONLY", "map", "config"), listener);
        hello = take("HELLO");
    }

    private void welcome(long seq, String requestId) {
        connection.send(codec.encode("WELCOME", seq, requestId, null,
                Map.of("sessionId", "session", "playerId", "me", "protocolVersion", 1,
                        "mapHash", "map", "configHash", "config", "heartbeatMs", 1000, "disconnectMs", 5000)));
    }

    private void join(long welcomeSeq, long ackSeq, long roomSeq) throws Exception {
        welcome(welcomeSeq, hello.requestId);
        WireCodec.Message join = take("JOIN_ROOM");
        assertTrue("Identity must exist before automatic JOIN_ROOM", events.contains("SESSION"));
        connection.send(codec.encode("ACK", ackSeq, join.requestId, null, Map.of("action", "JOIN_ROOM")));
        connection.send(codec.encode("ROOM_STATE", roomSeq, null, null,
                new RoomState("TEST", 1, RoomPhase.LOBBY, "test-room", "map", "config", null, 0,
                        Collections.singletonList(new RoomPlayer("me", null, false, "CONNECTED", false)))));
        state("CONNECTED");
        // onRoom follows the connection callback under the gateway lock.
        synchronized (gateway) { assertTrue(events.contains("ROOM")); }
    }

    private void rejectedBeforeIdentity() throws Exception {
        GatewayError error = errors.poll(3, TimeUnit.SECONDS);
        assertNotNull("Expected malformed handshake rejection", error);
        assertEquals("BAD_MESSAGE", error.code); assertFalse(error.recoverable);
        state("CLOSED");
        assertFalse(events.contains("SESSION")); assertFalse(events.contains("ROOM"));
        assertNull("Invalid handshake must never send JOIN_ROOM", received.poll(100, TimeUnit.MILLISECONDS));
        assertEquals(1, controls.stream().filter(s -> s.action.equals("HELLO")
                && s.state == ControlStatus.State.CANCELLED).count());
    }

    private WireCodec.Message take(String type) throws Exception {
        WireCodec.Message m = received.poll(3, TimeUnit.SECONDS);
        assertNotNull("Missing " + type, m); assertEquals(type, m.type); return m;
    }

    private void state(String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            String state = states.poll(100, TimeUnit.MILLISECONDS);
            if (expected.equals(state)) return;
        }
        fail("Missing connection state " + expected);
    }
}
