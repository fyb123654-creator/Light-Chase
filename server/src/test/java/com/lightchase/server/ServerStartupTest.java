package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.network.WebSocketClientGateway;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServerStartupTest {

    @Test public void defaultAndCustomPortsAreValidatedBeforeStartingThreads() {
        assertEquals(8080, ServerMain.parsePort(new String[0]));
        assertEquals(1, ServerMain.parsePort(new String[]{"1"}));
        assertEquals(65535, ServerMain.parsePort(new String[]{"65535"}));
        for (String invalid : new String[]{"0", "65536", "-1", "abc", "1.5", ""}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> ServerMain.parsePort(new String[]{invalid}));
            assertTrue(ex.getMessage().contains("Usage") || ex.getMessage().contains("1..65535"));
        }
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ServerMain.parsePort(new String[]{"8080", "extra"}));
        assertTrue(ex.getMessage().contains("Usage: server [port]"));
    }

    @Test public void cliPortArgumentPrecedenceOverEnvironmentVariable() {
        // When CLI arg is provided, it must take precedence over PORT environment variable
        int port = ServerMain.parsePort(new String[]{"9090"}, Map.of("PORT", "7070"));
        assertEquals(9090, port);
    }

    @Test public void portFromEnvironmentVariableWhenNoCliArguments() {
        // PORT env var parsed when args is empty
        assertEquals(7777, ServerMain.parsePort(new String[0], Map.of("PORT", "7777")));
        // Whitespace trimmed
        assertEquals(12345, ServerMain.parsePort(new String[0], Map.of("PORT", "  12345  ")));
        // Valid boundary ports
        assertEquals(1, ServerMain.parsePort(new String[0], Map.of("PORT", "1")));
        assertEquals(65535, ServerMain.parsePort(new String[0], Map.of("PORT", "65535")));
    }

    @Test public void defaultPortWhenNoCliArgsAndEmptyOrMissingEnvVar() {
        assertEquals(8080, ServerMain.parsePort(new String[0], Collections.emptyMap()));
        assertEquals(8080, ServerMain.parsePort(new String[0], null));
        assertEquals(8080, ServerMain.parsePort(new String[0], Map.of("PORT", "   ")));
        assertEquals(8080, ServerMain.parsePort(null, Collections.emptyMap()));
    }

    @Test public void invalidPortInEnvironmentVariableThrowsControlledException() {
        for (String invalid : new String[]{"0", "65536", "-1", "not_a_number", "80.80", "-9999"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> ServerMain.parsePort(new String[0], Map.of("PORT", invalid)));
            assertTrue("Expected message to describe port requirement: " + ex.getMessage(),
                    ex.getMessage().contains("1..65535") || ex.getMessage().contains("PORT"));
        }
    }

    @Test public void occupiedPortReportsStartupFailureAndShutdownIsIdempotent() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            LobbyServer server = new LobbyServer(new InetSocketAddress("127.0.0.1", occupied.getLocalPort()), DataFiles.bundled());
            try {
                server.start();
                assertThrows(BindException.class, server::awaitStart);
            } finally {
                server.shutdown();
                server.shutdown(); // Idempotency check
            }
        }
    }

    @Test public void gracefulShutdownClosesActiveConnectionsAndReleasesPort() throws Exception {
        DataFiles data = DataFiles.bundled();
        LobbyServer server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data);
        server.start();
        server.awaitStart();
        int port = server.getPort();
        assertTrue(port > 0);

        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);

        WebSocketClientGateway client = new WebSocketClientGateway();
        client.connect(new ConnectionOptions("127.0.0.1", port, "TEST", "p1", data.mapHash, data.configHash),
                new GatewayListener() {
                    public void onConnection(ConnectionStatus status) {
                        if ("CONNECTED".equals(status.state)) connected.countDown();
                        if ("CLOSED".equals(status.state)) disconnected.countDown();
                    }
                    public void onSession(SessionInfo session) {}
                    public void onControl(ControlStatus status) {}
                    public void onRoom(RoomState room) {}
                    public void onView(PlayerView view) {}
                    public void onResult(MatchResult result) {}
                    public void onError(GatewayError error) {}
                });

        assertTrue("Client should connect successfully", connected.await(5, TimeUnit.SECONDS));

        // Graceful shutdown
        server.shutdown();
        server.shutdown(); // Must be safe and idempotent

        assertTrue("Client should observe disconnection on server shutdown",
                disconnected.await(5, TimeUnit.SECONDS));

        client.close();

        // Verify port is freed and can be rebound
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress("127.0.0.1", port));
            assertTrue(probe.isBound());
        }
    }
}
