package com.lightchase.server;

import com.lightchase.data.DataFiles;
import java.net.InetSocketAddress;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerMain {
    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    public static void main(String[] args) throws Exception {
        int port = parsePort(args);
        DataFiles data = DataFiles.bundled();
        LobbyServer server = new LobbyServer(new InetSocketAddress("0.0.0.0", port), data);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutdown signal received (SIGINT/SIGTERM), gracefully shutting down LobbyServer...");
                server.shutdown();
                log.info("LobbyServer graceful shutdown complete.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("LobbyServer shutdown interrupted.");
            }
        }, "lightchase-shutdown-hook"));

        try {
            server.start();
            server.awaitStart();
        } catch (Exception ex) {
            try {
                server.shutdown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                ex.addSuppressed(interrupted);
            }
            throw ex;
        }
        System.out.println("Light Chase B7 server listening on ws://0.0.0.0:" + server.getPort() + "/game");
        System.out.println("V1 simulation/input, GAME_OVER retry, RESULT_ACK, RETURN_LOBBY rematch, backpressure, rate limiting, and diagnostic logging available.");
        System.out.println("Map: " + data.map.mapId + "; connect both clients with the same map/rules resources.");
    }

    static int parsePort(String[] args) {
        return parsePort(args, System.getenv());
    }

    static int parsePort(String[] args, Map<String, String> env) {
        if (args != null && args.length > 1) {
            throw new IllegalArgumentException("Usage: server [port], port must be 1..65535");
        }
        if (args != null && args.length == 1) {
            int port;
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Usage: server [port], port must be an integer in 1..65535", ex);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Usage: server [port], port must be 1..65535");
            }
            return port;
        }
        // No CLI argument: check PORT environment variable
        String envPort = env != null ? env.get("PORT") : null;
        if (envPort != null && !envPort.trim().isEmpty()) {
            int port;
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Usage: server [port] or PORT env var, port must be an integer in 1..65535 (PORT=" + envPort + ")", ex);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Usage: server [port] or PORT env var, port must be 1..65535 (PORT=" + envPort + ")");
            }
            return port;
        }
        return 8080;
    }
}
