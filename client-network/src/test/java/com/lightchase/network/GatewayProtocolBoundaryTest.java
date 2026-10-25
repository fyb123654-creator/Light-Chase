package com.lightchase.network;

import com.lightchase.contracts.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.Test;
import static org.junit.Assert.*;

/** An intentionally faulty server tests the real gateway without any game engine. */
public class GatewayProtocolBoundaryTest {
    @Test public void incompatibleVersionIsReportedInsteadOfSilentlyIgnored() throws Exception {
        reject("VERSION_MISMATCH", "{\"v\":2,\"type\":\"FUTURE\",\"seq\":1,\"requestId\":null,\"matchId\":null,\"body\":{}}");
    }

    @Test public void wrongDirectionCannotMasqueradeAsAValidServerPacket() throws Exception {
        reject("BAD_MESSAGE", new WireCodec().encode("HEARTBEAT", 1, null, null,
                ProtocolContractTest.fields("nonce", 1)));
    }

    @Test public void duplicateIdentityFieldsNeverReachTheUi() throws Exception {
        reject("BAD_MESSAGE", "{\"v\":1,\"type\":\"WELCOME\",\"seq\":1,\"requestId\":\"r-1\",\"matchId\":null,\"body\":{\"sessionId\":\"s1\",\"playerId\":\"forged\",\"playerId\":\"p1\",\"protocolVersion\":1,\"mapHash\":\"map\",\"configHash\":\"config\",\"heartbeatMs\":1000,\"disconnectMs\":5000}}");
    }

    @Test public void malformedWelcomeIsRejectedBeforePublishingIdentity() throws Exception {
        reject("BAD_MESSAGE", new WireCodec().encode("WELCOME", 1, "r-1", null,
                ProtocolContractTest.fields("sessionId", "s1", "playerId", "p1", "protocolVersion", 1,
                        "mapHash", "map", "configHash", "config", "heartbeatMs", "1000", "disconnectMs", 5000)));
    }

    private void reject(String expectedCode, String reply) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Exception> serverFailure = new AtomicReference<>();
        WebSocketServer peer = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            public void onOpen(WebSocket c, ClientHandshake h) {}
            public void onClose(WebSocket c, int code, String reason, boolean remote) {}
            public void onMessage(WebSocket c, String text) { c.send(reply); }
            public void onStart() { started.countDown(); }
            public void onError(WebSocket c, Exception ex) { serverFailure.set(ex); started.countDown(); }
        };
        BlockingQueue<GatewayError> errors = new LinkedBlockingQueue<>();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<SessionInfo> published = new AtomicReference<>();
        AtomicReference<RoomState> room = new AtomicReference<>();
        WebSocketClientGateway gateway = new WebSocketClientGateway();
        try {
            peer.start(); assertTrue(started.await(5, TimeUnit.SECONDS)); assertNull(serverFailure.get());
            gateway.connect(new ConnectionOptions("127.0.0.1", peer.getPort(), "TEST", "TEST_ONLY", "map", "config"),
                    new GatewayListener() {
                        public void onSession(SessionInfo s) { if (s != null) published.set(s); }
                        public void onControl(ControlStatus s) {}
                        public void onConnection(ConnectionStatus s) { if ("CLOSED".equals(s.state)) closed.countDown(); }
                        public void onRoom(RoomState s) { room.set(s); }
                        public void onView(PlayerView s) {}
                        public void onResult(MatchResult s) {}
                        public void onError(GatewayError e) { errors.add(e); }
                    });
            GatewayError error = errors.poll(5, TimeUnit.SECONDS);
            assertNotNull("Expected protocol rejection", error);
            assertEquals(expectedCode, error.code); assertFalse(error.recoverable);
            assertTrue("Gateway must close after a fatal protocol error", closed.await(5, TimeUnit.SECONDS));
            assertNull(published.get()); assertNull(room.get());
        } finally {
            gateway.close(); peer.stop(1000);
        }
    }
}
