package com.lightchase.server;

import com.lightchase.data.DataFiles;
import com.lightchase.network.WireCodec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.*;
import static org.junit.Assert.*;

/** Raw fake clients exercise the real receiver. No game engine is instantiated. */
public class LobbyProtocolBoundaryTest {
    private final WireCodec codec = new WireCodec();
    private final DataFiles data = DataFiles.bundled();
    private final List<RawClient> clients = new ArrayList<>();
    private LobbyServer server;

    private static final class RawClient extends WebSocketClient {
        final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        RawClient(URI uri) { super(uri); }
        public void onOpen(ServerHandshake h) {}
        public void onMessage(String text) { incoming.add(text); }
        public void onClose(int code, String reason, boolean remote) { closed.countDown(); }
        public void onError(Exception ex) {}
    }

    @Before public void start() throws Exception {
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data);
        server.start(); server.awaitStart();
    }

    @After public void stop() throws Exception {
        for (RawClient client : clients) client.close();
        server.shutdown();
    }

    @Test public void duplicateHandshakeFieldsAreRejectedBeforeWelcome() throws Exception {
        RawClient client = connect();
        client.send(hello().replace("\"clientBuild\":\"TEST_ONLY\"", "\"clientBuild\":\"forged\",\"clientBuild\":\"TEST_ONLY\""));
        WireCodec.Message error = receive(client);
        assertEquals("ERROR", error.type); assertEquals("BAD_MESSAGE", WireCodec.string(error.body, "code"));
    }

    @Test public void forgedIdentityInRoleRequestIsRejectedWithRequestCorrelation() throws Exception {
        RawClient client = connect(); client.send(hello()); assertEquals("WELCOME", receive(client).type);
        client.send(codec.encode("SELECT_ROLE", 2, "r2", null, fields("role", "POLICE", "playerId", "another-player")));
        WireCodec.Message error = receive(client);
        assertEquals("r2", error.requestId); assertEquals("BAD_MESSAGE", WireCodec.string(error.body, "code"));
    }

    @Test public void validButWrongDirectionPacketIsNotAcceptedAsHeartbeat() throws Exception {
        RawClient client = connect(); client.send(hello()); receive(client);
        client.send(codec.encode("PONG", 2, null, null, fields("nonce", 1, "serverTimeMs", 1)));
        assertEquals("BAD_MESSAGE", WireCodec.string(receive(client).body, "code"));
    }

    @Test public void futureVersionStillGetsAnExplicitMismatchError() throws Exception {
        RawClient client = connect();
        client.send("{\"v\":2,\"type\":\"FUTURE\",\"seq\":1,\"requestId\":null,\"matchId\":null,\"body\":{}}");
        assertEquals("VERSION_MISMATCH", WireCodec.string(receive(client).body, "code"));
    }

    @Test public void joinBeforeHelloGetsACorrelatedFatalError() throws Exception {
        RawClient client = connect();
        client.send(codec.encode("JOIN_ROOM", 1, "join-first", null, fields("roomId", "TEST")));
        WireCodec.Message error = receive(client);
        assertEquals("HANDSHAKE_REQUIRED", WireCodec.string(error.body, "code"));
        assertEquals("join-first", error.requestId); assertTrue(WireCodec.bool(error.body, "fatal"));
        assertTrue(client.closed.await(5, TimeUnit.SECONDS));
    }

    @Test public void malformedRecoveryAndThirdFatalErrorAreDeliveredOverRealWebSocket() throws Exception {
        RawClient client = connect(); client.send(hello()); receive(client);
        for (int i = 0; i < 2; i++) {
            client.send("{}"); assertFalse(WireCodec.bool(receive(client).body, "fatal"));
        }
        client.send(codec.encode("JOIN_ROOM", 2, "join", null, fields("roomId", "TEST")));
        assertEquals("ACK", receive(client).type); assertEquals("ROOM_STATE", receive(client).type);
        for (int i = 0; i < 3; i++) {
            client.send("{}");
            WireCodec.Message error = receive(client);
            assertEquals("BAD_MESSAGE", WireCodec.string(error.body, "code"));
            assertEquals(i == 2, WireCodec.bool(error.body, "fatal"));
        }
        assertTrue(client.closed.await(5, TimeUnit.SECONDS));
    }

    @Test public void malformedFirstHelloCanRecoverWithTheNextSequenceAndJoin() throws Exception {
        RawClient client = connect();
        client.send(incompleteHello(1));
        WireCodec.Message first = receive(client);
        assertEquals("BAD_MESSAGE", WireCodec.string(first.body, "code"));
        assertFalse(WireCodec.bool(first.body, "fatal"));
        client.send(hello().replace("\"seq\":1", "\"seq\":2").replace("\"r1\"", "\"corrected\""));
        assertEquals("WELCOME", receive(client).type);
        // The review probe sends another HELLO with a new request ID after success.
        client.send(hello().replace("\"seq\":1", "\"seq\":3").replace("\"r1\"", "\"corrected-again\""));
        WireCodec.Message duplicate = receive(client);
        assertEquals("INVALID_PHASE", WireCodec.string(duplicate.body, "code"));
        assertFalse(WireCodec.bool(duplicate.body, "fatal"));
        assertEquals("TEST", join(client, "TEST", 4).body.get("roomId").getAsString());
    }

    @Test public void malformedBodyConsumesItsEnvelopeSequenceAndCannotBeReused() throws Exception {
        RawClient client = connect(); client.send(incompleteHello(1)); receive(client);
        client.send(hello());
        WireCodec.Message stale = receive(client);
        assertEquals("STALE_MESSAGE", WireCodec.string(stale.body, "code"));
        assertFalse(WireCodec.bool(stale.body, "fatal"));
        client.send(hello().replace("\"seq\":1", "\"seq\":2"));
        assertEquals("WELCOME", receive(client).type);
    }

    @Test public void increasingSequencesDoNotResetMalformedBodyCount() throws Exception {
        RawClient client = connect();
        for (int seq = 1; seq <= 3; seq++) {
            client.send(incompleteHello(seq));
            WireCodec.Message error = receive(client);
            assertEquals("BAD_MESSAGE", WireCodec.string(error.body, "code"));
            assertEquals(seq == 3, WireCodec.bool(error.body, "fatal"));
        }
        assertTrue(client.closed.await(5, TimeUnit.SECONDS));
    }

    @Test public void roomFullIsRecoverableOnTheSameConnection() throws Exception {
        RawClient a = connect(), b = connect(), c = connect();
        for (RawClient client : Arrays.asList(a, b, c)) { client.send(hello()); receive(client); }
        join(a, "TEST", 2); join(b, "TEST", 2); receive(a);
        c.send(codec.encode("JOIN_ROOM", 2, "join-TEST", null, fields("roomId", "TEST")));
        WireCodec.Message error = receive(c);
        assertEquals("ROOM_FULL", WireCodec.string(error.body, "code"));
        assertFalse(WireCodec.bool(error.body, "fatal"));
        assertEquals("OTHER", join(c, "OTHER", 3).body.get("roomId").getAsString());
    }

    @Test public void disconnectedMemberIsReplacedWithFreshIdentity() throws Exception {
        RawClient a = connect(), b = connect();
        a.send(hello()); receive(a); b.send(hello()); WireCodec.Message old = receive(b);
        join(a, "TEST", 2); join(b, "TEST", 2); receive(a);
        b.close(); assertTrue(b.closed.await(5, TimeUnit.SECONDS));
        assertEquals(1, receive(a).body.getAsJsonArray("players").size());
        RawClient c = connect(); c.send(hello()); WireCodec.Message fresh = receive(c);
        assertNotEquals(old.body.get("playerId"), fresh.body.get("playerId"));
        assertNotEquals(old.body.get("sessionId"), fresh.body.get("sessionId"));
        assertEquals(2, join(c, "TEST", 2).body.getAsJsonArray("players").size());
        assertEquals(2, receive(a).body.getAsJsonArray("players").size());
    }

    private WireCodec.Message join(RawClient client, String room, long seq) throws Exception {
        client.send(codec.encode("JOIN_ROOM", seq, "join-" + room, null, fields("roomId", room)));
        assertEquals("ACK", receive(client).type);
        WireCodec.Message state = receive(client); assertEquals("ROOM_STATE", state.type); return state;
    }

    private RawClient connect() throws Exception {
        RawClient client = new RawClient(URI.create("ws://127.0.0.1:" + server.getPort() + "/game"));
        clients.add(client); assertTrue(client.connectBlocking(5, TimeUnit.SECONDS)); return client;
    }

    private String hello() {
        return codec.encode("HELLO", 1, "r1", null,
                fields("clientBuild", "TEST_ONLY", "mapHash", data.mapHash, "configHash", data.configHash));
    }

    private String incompleteHello(long seq) {
        return codec.encode("HELLO", seq, "bad-hello-" + seq, null,
                fields("clientBuild", "TEST_ONLY", "mapHash", data.mapHash));
    }

    private WireCodec.Message receive(RawClient client) throws Exception {
        String text = client.incoming.poll(5, TimeUnit.SECONDS);
        assertNotNull("Expected a server response", text); return codec.decodeServer(text);
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
