package com.lightchase.network;

import com.lightchase.contracts.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/**
 * Client-side network gateway implementing contracts.ClientGateway.
 * Shields Android/client-core from WebSocket, JSON, and network threading details.
 */
public final class WebSocketClientGateway implements ClientGateway {

    @FunctionalInterface
    public interface TimeSource {
        long now();
    }

    private final WireCodec codec = new WireCodec();
    private final TimeSource timeSource;
    private final long heartbeatIntervalMs;
    private final long suspectTimeoutMs;
    private final long disconnectTimeoutMs;
    private final long inputLeaseMs;
    private final long pulseIntervalMs;

    private WebSocketClient socket;
    private ScheduledExecutorService clock;
    private GatewayListener listener;
    private final ControlRequests requests = new ControlRequests(this::notifyControl);
    private final RoomStateTracker roomStates = new RoomStateTracker();
    private ConnectionOptions options;

    private String connectionState = "DISCONNECTED";
    private boolean closed = false;

    private long seq;
    private long requestSeq;
    private long lastServerSeq;
    private long inputSeq;
    private long dropSeq;

    private long lastPacket;
    private long lastInput;
    private long lastHeartbeat;
    private long lastSend;

    private boolean welcomed;
    private boolean suspect;
    private boolean running;
    private boolean inputLeaseExpired;

    private String matchId;
    private String resultId;
    private MatchResult acceptedResult;
    private boolean resultConflictReported;
    private String playerId;
    private RoomState snapshotRoom;
    private long lastViewTick = -1;
    private boolean terminalViewReceived;
    private InputFrame currentFrame = InputFrame.ZERO;
    // Raw activity is remembered only as release metadata, never as a queued movement/drop.
    private boolean sampledMoving, sampledInteracting, sampledDropping;
    private boolean movementNeedsNeutral, interactionNeedsRelease, dropNeedsRelease;

    private final Deque<Long> sentNonces = new ArrayDeque<>();

    public WebSocketClientGateway() {
        this(() -> System.nanoTime() / 1_000_000, 1000, 3000, 5000, 250, 50);
    }

    WebSocketClientGateway(TimeSource timeSource,
                           long heartbeatIntervalMs,
                           long suspectTimeoutMs,
                           long disconnectTimeoutMs,
                           long inputLeaseMs,
                           long pulseIntervalMs) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.suspectTimeoutMs = suspectTimeoutMs;
        this.disconnectTimeoutMs = disconnectTimeoutMs;
        this.inputLeaseMs = inputLeaseMs;
        this.pulseIntervalMs = pulseIntervalMs;
    }

    private long now() {
        return timeSource.now();
    }

    @Override
    public synchronized void connect(ConnectionOptions o, GatewayListener l) {
        if (l == null) return;
        if (socket != null && !"CLOSED".equals(connectionState)) {
            try {
                l.onError(new GatewayError("ALREADY_CONNECTED", "Already connecting or connected", true, null));
            } catch (Throwable ignored) {}
            return;
        }

        this.listener = l;
        this.options = o;
        this.closed = false;

        notifySession(null);

        if (o == null || o.host == null || !o.host.matches("[A-Za-z0-9.-]+") || o.port < 1 || o.port > 65535) {
            notifyError(new GatewayError("INVALID_ADDRESS", "Enter an IPv4 address or DNS host and valid port", true, null));
            return;
        }

        seq = 0;
        requestSeq = 0;
        lastServerSeq = 0;
        inputSeq = 0;
        dropSeq = 0;
        matchId = null;
        resultId = null;
        acceptedResult = null;
        resultConflictReported = false;
        playerId = null;
        snapshotRoom = null;
        lastViewTick = -1;
        terminalViewReceived = false;
        roomStates.reset();
        welcomed = false;
        suspect = false;
        running = false;
        inputLeaseExpired = false;
        currentFrame = InputFrame.ZERO;
        resetInputRecovery();
        sentNonces.clear();

        long n = now();
        lastPacket = n;
        lastInput = n;
        lastHeartbeat = n;
        lastSend = n;

        URI uri = URI.create("ws://" + o.host + ":" + o.port + "/game");
        socket = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake h) {
                synchronized (WebSocketClientGateway.this) {
                    if (socket != this) return;
                    transitionState("HANDSHAKING", null);
                    sendControl("HELLO", map("clientBuild", o.clientBuild, "mapHash", o.mapHash, "configHash", o.configHash), null);
                }
            }

            @Override
            public void onMessage(String text) {
                synchronized (WebSocketClientGateway.this) {
                    if (socket == this) receive(text);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                synchronized (WebSocketClientGateway.this) {
                    if (socket != this) return;
                    socket = null;
                    if (!closed) {
                        closed = true;
                        running = false;
                        currentFrame = InputFrame.ZERO;
                        inputLeaseExpired = false;
                        stopClock();
                        requests.cancel();
                        notifySession(null);
                        transitionState("CLOSED", reason != null && !reason.isEmpty() ? reason : "Socket closed");
                    }
                }
            }

            @Override
            public void onError(Exception ex) {
                synchronized (WebSocketClientGateway.this) {
                    if (socket == this) {
                        notifyError(new GatewayError("CONNECTION_LOST", ex != null ? ex.getClass().getSimpleName() : "Socket error", true, null));
                    }
                }
            }
        };

        if (pulseIntervalMs > 0) {
            clock = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lightchase-client");
                t.setDaemon(true);
                return t;
            });
            clock.scheduleAtFixedRate(this::pulse, pulseIntervalMs, pulseIntervalMs, TimeUnit.MILLISECONDS);
        }

        transitionState("CONNECTING", null);
        socket.connect();
    }

    synchronized void pulse() {
        if (closed || socket == null) return;
        long n = now();

        if (requests.pulse(n)) {
            close("Control request timeout");
            return;
        }

        if (n - lastPacket >= disconnectTimeoutMs) {
            notifyError(new GatewayError("CONNECTION_LOST", "No server response for 5 seconds", true, null));
            close("No server response for 5 seconds");
            return;
        }

        if (!welcomed) return;

        if ("CONNECTED".equals(connectionState) && n - lastPacket >= suspectTimeoutMs && !suspect) {
            suspect = true;
            currentFrame = InputFrame.ZERO;
            movementNeedsNeutral = sampledMoving;
            interactionNeedsRelease = sampledInteracting;
            dropNeedsRelease = sampledDropping;
            transitionState("SUSPECT", "No recent server packets");
        }

        if (n - lastHeartbeat >= heartbeatIntervalMs) {
            long nonce = n;
            sentNonces.addLast(nonce);
            if (sentNonces.size() > 10) sentNonces.removeFirst();
            send("HEARTBEAT", null, null, map("nonce", nonce));
            lastHeartbeat = n;
        }

        if (running && !suspect) {
            if (n - lastInput >= inputLeaseMs) {
                if (!inputLeaseExpired) {
                    inputLeaseExpired = true;
                    currentFrame = InputFrame.ZERO;
                    sendInput(null);
                    lastSend = n;
                }
            } else {
                if (n - lastSend >= pulseIntervalMs) {
                    sendInput(null);
                    lastSend = n;
                }
            }
        }
    }

    private void receive(String text) {
        try {
            WireCodec.Message m = codec.decodeServer(text);
            if (m.v != Protocol.VERSION) {
                notifyError(new GatewayError("VERSION_MISMATCH", "Server protocol version differs", false, m.requestId));
                close("Protocol version mismatch");
                return;
            }
            if (lastServerSeq == 0 && m.seq != 1) throw new IllegalArgumentException("INVALID_FIRST_SEQUENCE");
            if (m.seq <= lastServerSeq) return;
            lastServerSeq = m.seq;
            lastPacket = now();

            if (suspect || "SUSPECT".equals(connectionState)) {
                suspect = false;
                currentFrame = InputFrame.ZERO;
                inputLeaseExpired = false;
                transitionState("CONNECTED", null);
            }

            switch (m.type) {
                case "WELCOME":
                    // Validate correlation before exposing identity or starting another control request.
                    if (welcomed || !"HANDSHAKING".equals(connectionState)
                            || !requests.isPending(m.requestId, "HELLO")) {
                        throw new IllegalArgumentException("UNEXPECTED_WELCOME");
                    }
                    if (requests.pulse(now())) {
                        close("Control request timeout");
                        return;
                    }
                    if (!options.mapHash.equals(WireCodec.string(m.body, "mapHash"))) {
                        notifyError(new GatewayError("MAP_MISMATCH", "Server map hash differs", false, m.requestId));
                        close("Map hash mismatch");
                        return;
                    }
                    if (!options.configHash.equals(WireCodec.string(m.body, "configHash"))) {
                        notifyError(new GatewayError("CONFIG_MISMATCH", "Server resource hashes differ", false, m.requestId));
                        close("Config hash mismatch");
                        return;
                    }
                    WireCodec.exactFields(m.body, "sessionId", "playerId", "protocolVersion", "mapHash", "configHash", "heartbeatMs", "disconnectMs");
                    if (WireCodec.integer(m.body, "protocolVersion") != Protocol.VERSION
                            || WireCodec.integer(m.body, "heartbeatMs") != 1000
                            || WireCodec.integer(m.body, "disconnectMs") != 5000) {
                        throw new IllegalArgumentException("INVALID_WELCOME");
                    }
                    playerId = WireCodec.string(m.body, "playerId");
                    notifySession(new SessionInfo(WireCodec.string(m.body, "sessionId"), playerId));
                    requests.complete(m.requestId, "HELLO", null);
                    welcomed = true;
                    transitionState("JOINING", null);
                    sendControl("JOIN_ROOM", map("roomId", options.roomId.trim().toUpperCase(Locale.ROOT)), null);
                    break;
                case "ROOM_STATE":
                    RoomState room = codec.body(m, RoomState.class);
                    if (!roomStates.accept(room, m.body, options, playerId)) break;
                    running = (room.phase == RoomPhase.RUNNING);
                    if (!Objects.equals(matchId, room.matchId)) {
                        lastViewTick = -1;
                        terminalViewReceived = false;
                        inputSeq = 0;
                        dropSeq = 0;
                        currentFrame = InputFrame.ZERO;
                        resetInputRecovery();
                        inputLeaseExpired = false;
                    }
                    if (!Objects.equals(matchId, room.matchId) || room.phase != RoomPhase.FINISHED) {
                        resultId = null;
                        acceptedResult = null;
                        resultConflictReported = false;
                    }
                    matchId = room.matchId;
                    snapshotRoom = room;
                    transitionState("CONNECTED", null);
                    notifyRoom(room);
                    break;
                case "SNAPSHOT":
                    if (resultId == null && snapshotRoom != null && Objects.equals(matchId, m.matchId)
                            && (snapshotRoom.phase == RoomPhase.RUNNING || snapshotRoom.phase == RoomPhase.FINISHED)) {
                        PlayerView view = codec.body(m, PlayerView.class);
                        SnapshotValidator.validate(view, playerId, snapshotRoom);
                        boolean terminal = snapshotRoom.phase == RoomPhase.FINISHED;
                        if (view.tick < lastViewTick || (view.tick == lastViewTick && !terminal)
                                || (terminal && terminalViewReceived)) break;
                        lastViewTick = view.tick;
                        if (terminal) terminalViewReceived = true;
                        notifyView(view);
                    }
                    break;
                case "GAME_OVER":
                    if (Objects.equals(matchId, m.matchId)) {
                        MatchResult result = codec.body(m, MatchResult.class);
                        if (acceptedResult != null) {
                            if (!sameResult(acceptedResult, result)) {
                                if (!resultConflictReported) {
                                    resultConflictReported = true;
                                    notifyError(new GatewayError("RESULT_MISMATCH",
                                            "Conflicting game result ignored; first result retained", true, null));
                                }
                                break;
                            }
                            send("RESULT_ACK", null, matchId, map("resultId", acceptedResult.resultId));
                            break;
                        }
                        if (!Objects.equals(matchId, result.matchId) || result.resultId.trim().isEmpty()
                                || result.elapsedTicks < 0 || result.elapsedTicks > 5400
                                || result.marks < 0 || result.marks > 3
                                || result.deliveredCount < 0 || result.deliveredCount > 3) {
                            throw new IllegalArgumentException("INVALID_RESULT");
                        }
                        roomStates.finish(matchId);
                        running = false;
                        currentFrame = InputFrame.ZERO;
                        inputLeaseExpired = false;
                        acceptedResult = result;
                        resultId = result.resultId;
                        send("RESULT_ACK", null, matchId, map("resultId", resultId));
                        notifyResult(result);
                    }
                    break;
                case "ERROR":
                    WireCodec.exactFields(m.body, "code", "message", "fatal");
                    boolean fatal = WireCodec.bool(m.body, "fatal");
                    requests.complete(m.requestId, null, WireCodec.string(m.body, "code"));
                    notifyError(new GatewayError(WireCodec.string(m.body, "code"), WireCodec.string(m.body, "message"), !fatal, m.requestId));
                    if (fatal) close("Server reported fatal error: " + WireCodec.string(m.body, "code"));
                    break;
                case "ACK":
                    WireCodec.exactFields(m.body, "action");
                    requests.complete(m.requestId, WireCodec.string(m.body, "action"), null);
                    break;
                case "PONG":
                    WireCodec.exactFields(m.body, "nonce", "serverTimeMs");
                    long pongNonce = WireCodec.integer(m.body, "nonce");
                    WireCodec.integer(m.body, "serverTimeMs");
                    if (!sentNonces.remove(pongNonce)) {
                        throw new IllegalArgumentException("INVALID_PONG_NONCE");
                    }
                    break;
                default:
                    notifyError(new GatewayError("BAD_MESSAGE", "Unknown server message", false, null));
                    close("Unknown message type");
            }
        } catch (RuntimeException ex) {
            notifyError(new GatewayError("BAD_MESSAGE", "Invalid server message", false, null));
            close("Malformed server message");
        }
    }

    @Override
    public synchronized void selectRole(Role r) {
        if (r == null) return;
        sendControl("SELECT_ROLE", map("role", r.name()), null);
    }

    @Override
    public synchronized void setReady(boolean r) {
        sendControl("SET_READY", map("ready", r), null);
    }

    @Override
    public synchronized void submitInput(InputFrame f) {
        if (f == null) return;
        sampledMoving = f.moveX != 0 || f.moveY != 0;
        sampledInteracting = f.interactHeld;
        sampledDropping = f.dropPressed;
        if (suspect) {
            // Observe releases/presses during the outage without storing an effective frame or renewing its lease.
            movementNeedsNeutral = sampledMoving;
            interactionNeedsRelease = sampledInteracting;
            dropNeedsRelease = sampledDropping;
            currentFrame = InputFrame.ZERO;
            return;
        }
        if (!sampledMoving) movementNeedsNeutral = false;
        if (!sampledInteracting) interactionNeedsRelease = false;
        if (!sampledDropping) dropNeedsRelease = false;
        long n = now();
        lastInput = n;

        boolean held = f.interactHeld && !interactionNeedsRelease;
        boolean edge = (currentFrame.interactHeld != held);
        boolean drop = f.dropPressed && !dropNeedsRelease;

        currentFrame = new InputFrame(movementNeedsNeutral ? 0 : f.moveX,
                movementNeedsNeutral ? 0 : f.moveY, held, false);
        inputLeaseExpired = false;

        if (running && !suspect) {
            if (drop) {
                long allocatedDropId = ++dropSeq;
                sendInput(allocatedDropId);
                lastSend = n;
            } else if (edge) {
                sendInput(null);
                lastSend = n;
            }
        }
    }

    private void resetInputRecovery() {
        sampledMoving = sampledInteracting = sampledDropping = false;
        movementNeedsNeutral = interactionNeedsRelease = dropNeedsRelease = false;
    }

    private static boolean sameResult(MatchResult first, MatchResult next) {
        return Objects.equals(first.resultId, next.resultId) && Objects.equals(first.matchId, next.matchId)
                && first.winner == next.winner && first.reason == next.reason
                && Objects.equals(first.departedPlayerId, next.departedPlayerId)
                && first.elapsedTicks == next.elapsedTicks && first.marks == next.marks
                && first.deliveredCount == next.deliveredCount && first.finalTick == next.finalTick;
    }

    private void sendInput(Long dropId) {
        send("INPUT", null, matchId, map(
                "inputSeq", ++inputSeq,
                "moveX", currentFrame.moveX,
                "moveY", currentFrame.moveY,
                "interactHeld", currentFrame.interactHeld,
                "dropId", dropId
        ));
    }

    @Override
    public synchronized void returnToLobby() {
        if (socket == null || !socket.isOpen()) {
            // sendControl produces the same PENDING -> FAILED/NOT_CONNECTED as other controls.
            sendControl("RETURN_LOBBY", map("resultId", resultId), matchId);
            return;
        }
        if (resultId == null) {
            if (listener != null) {
                String id = "r-" + (++requestSeq);
                requests.begin(id, "RETURN_LOBBY", now());
                requests.complete(id, "RETURN_LOBBY", "INVALID_PHASE");
                notifyError(new GatewayError("INVALID_PHASE", "Cannot return to lobby without match result", true, id));
            }
            return;
        }
        sendControl("RETURN_LOBBY", map("resultId", resultId), matchId);
    }

    @Override
    public synchronized void leaveRoom() {
        sendControl("LEAVE_ROOM", map("reason", "QUIT"), null);
    }

    @Override
    public synchronized void background() {
        currentFrame = InputFrame.ZERO;
        sendControl("LEAVE_ROOM", map("reason", "BACKGROUND"), null);
        close("Application entered background");
    }

    @Override
    public synchronized void close() {
        close("Closed locally");
    }

    synchronized void close(String reason) {
        if (closed || "CLOSED".equals(connectionState)) return;
        closed = true;
        transitionState("CLOSED", reason);
        running = false;
        currentFrame = InputFrame.ZERO;
        inputLeaseExpired = false;
        stopClock();
        WebSocketClient old = socket;
        socket = null;
        if (old != null) {
            try {
                old.close();
            } catch (Throwable ignored) {}
        }
        requests.cancel();
        notifySession(null);
    }

    private void stopClock() {
        if (clock != null) {
            clock.shutdownNow();
            clock = null;
        }
    }

    private void transitionState(String newState, String reason) {
        if (Objects.equals(this.connectionState, newState)) {
            return;
        }
        this.connectionState = newState;
        notifyConnection(new ConnectionStatus(newState, reason));
    }

    private void sendControl(String type, Object body, String match) {
        if (listener == null) return;
        String id = "r-" + (++requestSeq);
        requests.begin(id, type, now());
        if (socket == null || !socket.isOpen()) {
            requests.complete(id, type, "NOT_CONNECTED");
            notifyError(new GatewayError("NOT_CONNECTED", "Socket is not open", true, id));
            return;
        }
        send(type, id, match, body);
    }

    private void send(String type, String request, String match, Object body) {
        if (socket == null || !socket.isOpen()) {
            if (request != null) {
                notifyError(new GatewayError("NOT_CONNECTED", "Socket is not open", true, request));
            }
            return;
        }
        socket.send(codec.encode(type, ++seq, request, match, body));
    }

    private void notifySession(SessionInfo s) {
        if (listener != null) {
            try {
                listener.onSession(s);
            } catch (Throwable ignored) {}
        }
    }

    private void notifyControl(ControlStatus s) {
        if (listener != null) {
            try {
                listener.onControl(s);
            } catch (Throwable ignored) {}
        }
    }

    private void notifyConnection(ConnectionStatus s) {
        if (listener != null) {
            try {
                listener.onConnection(s);
            } catch (Throwable ignored) {}
        }
    }

    private void notifyRoom(RoomState s) {
        if (listener != null) {
            try {
                listener.onRoom(s);
            } catch (Throwable ignored) {}
        }
    }

    private void notifyView(PlayerView s) {
        if (listener != null) {
            try {
                listener.onView(s);
            } catch (Throwable ignored) {}
        }
    }

    private void notifyResult(MatchResult s) {
        if (listener != null) {
            try {
                listener.onResult(s);
            } catch (Throwable ignored) {}
        }
    }

    private void notifyError(GatewayError e) {
        if (listener != null) {
            try {
                listener.onError(e);
            } catch (Throwable ignored) {}
        }
    }

    String getConnectionState() {
        return connectionState;
    }

    boolean isClosedState() {
        return closed;
    }

    long getInputSeq() {
        return inputSeq;
    }

    long getDropSeq() {
        return dropSeq;
    }

    private static Map<String, Object> map(Object... fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            out.put((String) fields[i], fields[i + 1]);
        }
        return out;
    }
}
