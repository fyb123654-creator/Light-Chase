package com.lightchase.server;

import com.google.gson.JsonObject;
import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.game.V1EngineFactory;
import com.lightchase.network.WireCodec;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One logic thread owns lobby, countdown, fixed-step engine and transport inboxes. */
public final class LobbyServer extends WebSocketServer {
    private static final Logger log = LoggerFactory.getLogger(LobbyServer.class);
    private static final long HANDSHAKE_MS = 3000;
    private static final long SUSPECT_MS = 3000;
    private static final long DISCONNECT_MS = 5000;
    private static final long COUNTDOWN_BROADCAST_MS = 100;
    private static final long SESSION_SCAN_MS = 10;
    private static final int MAX_ROOMS = 8;
    private static final int MAX_CONTROL_RESULTS = 128;
    private static final int MAX_OUTBOUND_CONTROL = 64;
    private static final int MAX_INBOUND_MESSAGES = 128;

    /** Only transport admission uses atomics; session/game state stays on the logic thread. */
    private static final class InboundAdmission {
        final AtomicInteger pending = new AtomicInteger();
        final AtomicBoolean overflowed = new AtomicBoolean();
    }

    private static final class OutboundMessage {
        final String type;
        final String requestId;
        final String matchId;
        final Object body;
        OutboundMessage(String type, String requestId, String matchId, Object body) {
            this.type = type;
            this.requestId = requestId;
            this.matchId = matchId;
            this.body = body;
        }
    }

    private final WireCodec codec = new WireCodec();
    private final DataFiles data;
    private final LongSupplier clock;
    private final MatchEngineFactory engineFactory;
    private final boolean automaticTimeouts;
    // All session/room mutation and timeout checks execute on this one thread.
    private final ScheduledExecutorService logic = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lightchase-lobby-logic");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<WebSocket, Session> sessions = new LinkedHashMap<>();
    private final ConcurrentMap<WebSocket, InboundAdmission> inbound = new ConcurrentHashMap<>();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile Exception startFailure;

    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final String playerId = UUID.randomUUID().toString();
        final long openedAt;
        final Map<String, ControlResult> results = new LinkedHashMap<>();
        final Map<String, WireCodec.Message> pendingLeaves = new LinkedHashMap<>();
        String roomId;
        Role role;
        boolean welcomed, ready, departing, suspect, healthBroadcastPending;
        boolean returned, resultAcked;
        long lastGameOverSentAt;
        long inputWindowStart, controlWindowStart;
        int inputCount, controlCount;
        Long inputViolationStart, controlViolationStart;
        final Deque<OutboundMessage> controlQueue = new ArrayDeque<>();
        OutboundMessage pendingSnapshot;
        String mapHash, configHash;
        int badMessages;
        long inSeq, outSeq, lastPacket;
        Long lastStaleReport;
        Long lastStaleInputReport;
        Session(long now) {
            openedAt = lastPacket = now;
            inputWindowStart = controlWindowStart = now;
        }
    }

    /** JSON object equality ignores key order without conflating strings and numbers. */
    private static final class ControlResult {
        final String action, matchId, responseType;
        final JsonObject requestBody;
        final Object responseBody;
        ControlResult(WireCodec.Message request, String responseType, Object responseBody) {
            action = request.type;
            matchId = request.matchId;
            requestBody = request.body.deepCopy();
            this.responseType = responseType;
            this.responseBody = responseBody;
        }
        boolean matches(WireCodec.Message request) {
            return action.equals(request.type) && Objects.equals(matchId, request.matchId)
                    && requestBody.equals(request.body);
        }
    }

    private static final class Room {
        long revision;
        RoomPhase phase = RoomPhase.LOBBY;
        long countdownStartedAt, countdownBroadcastAt;
        int countdownRemainingMs;
        String matchId;
        MatchRuntime match;
        MatchResult result;
        final List<WebSocket> members = new ArrayList<>();
    }

    public LobbyServer(InetSocketAddress address, DataFiles data) {
        this(address, data, () -> System.nanoTime() / 1_000_000, true);
    }

    // Test-only dependency injection; never exposed as a protocol or player setting.
    LobbyServer(InetSocketAddress address, DataFiles data, LongSupplier clock, boolean automaticTimeouts) {
        this(address, data, clock, automaticTimeouts, new V1EngineFactory());
    }

    LobbyServer(InetSocketAddress address, DataFiles data, LongSupplier clock, boolean automaticTimeouts,
                MatchEngineFactory engineFactory) {
        super(address);
        this.data = Objects.requireNonNull(data);
        this.clock = Objects.requireNonNull(clock);
        this.engineFactory = Objects.requireNonNull(engineFactory);
        this.automaticTimeouts = automaticTimeouts;
        setReuseAddr(true);
        setConnectionLostTimeout(0);
    }

    public void awaitStart() throws Exception {
        if (!started.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Server startup timed out");
        if (startFailure != null) throw startFailure;
    }

    public void shutdown() throws InterruptedException {
        if (!stopping.compareAndSet(false, true)) return;
        log.info("[SHUTDOWN] Initiating LobbyServer graceful shutdown on port {}...", getPort());
        logic.shutdown();
        if (!logic.awaitTermination(2, TimeUnit.SECONDS)) {
            logic.shutdownNow();
        }
        stop(1000);
        inbound.clear();
        log.info("[SHUTDOWN] LobbyServer stopped, active connections closed, port released.");
    }

    @Override public void onOpen(WebSocket connection, ClientHandshake handshake) {
        inbound.put(connection, new InboundAdmission());
        String path = handshake.getResourceDescriptor();
        long openedAt = clock.getAsLong();
        dispatch(() -> {
            Session session = new Session(openedAt);
            sessions.put(connection, session);
            log.info("[CONNECT] Client transport connected from {}, totalSessions={}",
                    safeRemote(connection), sessions.size());
            if (!"/game".equals(path)) {
                fail(connection, session, null, "BAD_MESSAGE", "Use WebSocket path /game", true);
            }
        });
    }

    @Override public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        inbound.remove(connection);
        dispatch(() -> {
            Session session = sessions.get(connection);
            String playerId = session != null ? session.playerId : "unknown";
            String roomId = session != null ? session.roomId : null;
            log.info("[DISCONNECT] Client disconnected: playerId={}, roomId={}, code={}, reason={}, remote={}",
                    playerId, roomId, code, reason, remote);
            disconnected(connection);
        });
    }

    @Override public void onMessage(WebSocket connection, String text) {
        // Oversize payloads must not be retained by the executor while its consumer is behind.
        if (text != null && (text.length() > WireCodec.MAX_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > WireCodec.MAX_BYTES)) {
            dispatchMessage(connection, () -> {
                Session session = sessions.get(connection);
                if (session != null && !expire(connection, session, clock.getAsLong())) {
                    malformed(connection, session, null, true);
                }
            });
            return;
        }
        dispatchMessage(connection, () -> receive(connection, text));
    }

    @Override public void onMessage(WebSocket connection, ByteBuffer bytes) {
        int size = bytes.remaining();
        dispatchMessage(connection, () -> {
            Session session = sessions.get(connection);
            if (session != null && !expire(connection, session, clock.getAsLong())) {
                malformed(connection, session, null, size > WireCodec.MAX_BYTES);
            }
        });
    }

    private void receive(WebSocket connection, String text) {
        Session session = sessions.get(connection);
        if (session == null || expire(connection, session, clock.getAsLong())) return;
        WireCodec.Message message = null;
        try {
            message = codec.decode(text);
            if (message.v != Protocol.VERSION) {
                fail(connection, session, message, "VERSION_MISMATCH", "Protocol version must be 1", true);
                return;
            }
            if (!session.welcomed && !"HELLO".equals(message.type)) {
                fail(connection, session, message, "HANDSHAKE_REQUIRED", "Send HELLO first", true);
                return;
            }
            if (session.inSeq == 0 && message.seq != 1) {
                throw new IllegalArgumentException("BAD_MESSAGE");
            }
            if (message.seq <= session.inSeq) {
                long now = clock.getAsLong();
                if (session.lastStaleReport == null || now - session.lastStaleReport >= 1000) {
                    session.lastStaleReport = now;
                    fail(connection, session, message, "STALE_MESSAGE", "Sequence must increase", false);
                }
                return;
            }
            // A valid envelope consumes its sequence even when its body is rejected.
            // Liveness is refreshed only after semantic acceptance, not merely a valid body.
            session.inSeq = message.seq;
            codec.validateClient(message);
            long now = clock.getAsLong();
            if (!checkRateLimit(connection, session, message, now)) return;
            ControlResult cached = session.results.get(message.requestId);
            if (cached != null) {
                if (!cached.matches(message)) {
                    fail(connection, session, message, "REQUEST_ID_REUSED", "Request ID reused with different contents", false);
                } else {
                    send(connection, session, cached.responseType, message.requestId, cached.matchId, cached.responseBody);
                    if (!"ERROR".equals(cached.responseType)) validPacket(connection, session);
                    if ("JOIN_ROOM".equals(message.type) && "ACK".equals(cached.responseType)) sendRoom(connection, session);
                    if ("RETURN_LOBBY".equals(message.type) && "ACK".equals(cached.responseType)) sendRoom(connection, session);
                }
                return;
            }
            WireCodec.Message pending = session.pendingLeaves.get(message.requestId);
            if (pending != null) {
                if (!pending.type.equals(message.type) || !Objects.equals(pending.matchId, message.matchId)
                        || !pending.body.equals(message.body)) {
                    fail(connection, session, message, "REQUEST_ID_REUSED", "Request ID reused with different contents", false);
                } else {
                    validPacket(connection, session);
                }
                return; // First leave still waits for the next rule step, including its ACK.
            }
            if ("INPUT".equals(message.type)) {
                input(connection, session, message);
                return;
            }
            if ("RESULT_ACK".equals(message.type)) {
                resultAck(connection, session, message);
                return;
            }
            if ("RETURN_LOBBY".equals(message.type)) {
                returnLobby(connection, session, message);
                return;
            }
            if (message.matchId != null) {
                reject(connection, session, message, "INVALID_PHASE", "No running or finished match", false);
                return;
            }
            handle(connection, session, message);
        } catch (IllegalArgumentException ex) {
            malformed(connection, session, message, "MESSAGE_TOO_LARGE".equals(ex.getMessage()));
        } catch (RuntimeException ex) {
            // A server defect must not be misreported as malformed client input.
            log.error("[SESSION_ERROR] Unexpected error in session: {}", ex.getMessage(), ex);
            fail(connection, session, message, "SERVER_ERROR", "Server could not process this session", true);
        } finally {
            flushHealth(session);
        }
    }

    private void handle(WebSocket connection, Session session, WireCodec.Message message) {
        switch (message.type) {
            case "HELLO":
                if (session.welcomed) {
                    reject(connection, session, message, "INVALID_PHASE", "Already handshaken", false);
                } else if (!data.mapHash.equals(WireCodec.string(message.body, "mapHash"))) {
                    reject(connection, session, message, "MAP_MISMATCH", "Map hash differs", true);
                } else if (!data.configHash.equals(WireCodec.string(message.body, "configHash"))) {
                    reject(connection, session, message, "CONFIG_MISMATCH", "Rule hash differs", true);
                } else {
                    session.welcomed = true;
                    session.mapHash = data.mapHash;
                    session.configHash = data.configHash;
                    log.info("[HANDSHAKE] Handshake completed: sessionId={}, playerId={}, clientSeq={}",
                            session.id, session.playerId, message.seq);
                    reply(connection, session, message, "WELCOME", fields("sessionId", session.id,
                            "playerId", session.playerId, "protocolVersion", Protocol.VERSION,
                            "mapHash", data.mapHash, "configHash", data.configHash,
                            "heartbeatMs", 1000, "disconnectMs", DISCONNECT_MS));
                }
                break;
            case "JOIN_ROOM": join(connection, session, message); break;
            case "SELECT_ROLE": selectRole(connection, session, message); break;
            case "SET_READY": setReady(connection, session, message); break;
            case "HEARTBEAT":
                validPacket(connection, session);
                flushHealth(session);
                send(connection, session, "PONG", null, null,
                        fields("nonce", WireCodec.integer(message.body, "nonce"), "serverTimeMs", clock.getAsLong()));
                break;
            case "LEAVE_ROOM":
                if (session.roomId == null) reject(connection, session, message, "NOT_IN_ROOM", "Join first", false);
                else if (rooms.get(session.roomId).phase == RoomPhase.RUNNING) {
                    validPacket(connection, session);
                    session.pendingLeaves.put(message.requestId, message);
                    departure(session, DepartureReason.valueOf(WireCodec.string(message.body, "reason")));
                }
                else {
                    ack(connection, session, message);
                    close(connection, 1000, WireCodec.string(message.body, "reason"));
                }
                break;
            default:
                reject(connection, session, message, "INVALID_PHASE", "Action is unavailable in this phase", false);
        }
    }

    private void input(WebSocket connection, Session session, WireCodec.Message message) {
        Room room = rooms.get(session.roomId);
        if (room == null || room.phase != RoomPhase.RUNNING) {
            reject(connection, session, message, "INVALID_PHASE", "Input requires a running match", false);
            return;
        }
        if (!room.matchId.equals(message.matchId)) {
            reject(connection, session, message, "STALE_MATCH", "Input belongs to another match", false);
            return;
        }
        long now = clock.getAsLong();
        String error = room.match.input(session.playerId, WireCodec.integer(message.body, "inputSeq"),
                message.body.get("moveX").getAsDouble(), message.body.get("moveY").getAsDouble(),
                WireCodec.bool(message.body, "interactHeld"),
                message.body.get("dropId").isJsonNull() ? null : WireCodec.integer(message.body, "dropId"), now);
        if (error == null) { validPacket(connection, session); return; }
        if ("STALE_INPUT".equals(error)) {
            if (session.lastStaleInputReport != null && now - session.lastStaleInputReport < 1000) return;
            session.lastStaleInputReport = now;
        }
        reject(connection, session, message, error, "Input was not accepted", false);
    }

    private boolean checkRateLimit(WebSocket connection, Session session, WireCodec.Message message, long now) {
        if ("INPUT".equals(message.type)) {
            if (now - session.inputWindowStart >= 1000) {
                if (session.inputCount <= 60) session.inputViolationStart = null;
                session.inputWindowStart = now;
                session.inputCount = 0;
            }
            session.inputCount++;
            if (session.inputCount > 60) {
                if (session.inputViolationStart == null) session.inputViolationStart = now;
                boolean fatal = (now - session.inputViolationStart >= 3000);
                log.warn("[RATE_LIMIT] Client {} in roomId={} exceeded input rate limit (count={}), fatal={}",
                        session.playerId, session.roomId, session.inputCount, fatal);
                reject(connection, session, message, "RATE_LIMITED", "Input rate limit exceeded (max 60/s)", fatal);
                return false;
            }
        } else if (message.requestId != null) {
            if (now - session.controlWindowStart >= 1000) {
                if (session.controlCount <= 10) session.controlViolationStart = null;
                session.controlWindowStart = now;
                session.controlCount = 0;
            }
            session.controlCount++;
            if (session.controlCount > 10) {
                if (session.controlViolationStart == null) session.controlViolationStart = now;
                boolean fatal = (now - session.controlViolationStart >= 3000);
                log.warn("[RATE_LIMIT] Client {} in roomId={} exceeded control rate limit (count={}), fatal={}",
                        session.playerId, session.roomId, session.controlCount, fatal);
                reject(connection, session, message, "RATE_LIMITED", "Control rate limit exceeded (max 10/s)", fatal);
                return false;
            }
        }
        return true;
    }

    private void resultAck(WebSocket connection, Session session, WireCodec.Message message) {
        if (session.roomId == null) {
            fail(connection, session, message, "INVALID_PHASE", "Not in a room", false);
            return;
        }
        Room room = rooms.get(session.roomId);
        if (room == null) {
            fail(connection, session, message, "INVALID_PHASE", "Not in a room", false);
            return;
        }
        if (room.phase == RoomPhase.RUNNING) {
            if (!Objects.equals(room.matchId, message.matchId)) {
                fail(connection, session, message, "STALE_MATCH", "Result acknowledgement belongs to another match", false);
            } else {
                fail(connection, session, message, "INVALID_PHASE", "Match is still running", false);
            }
            return;
        }
        if (room.phase != RoomPhase.FINISHED) {
            fail(connection, session, message, "INVALID_PHASE", "No finished match in this room", false);
            return;
        }
        String resultId = WireCodec.string(message.body, "resultId");
        if (!Objects.equals(room.matchId, message.matchId) || room.result == null || !room.result.resultId.equals(resultId)) {
            fail(connection, session, message, "RESULT_MISMATCH", "Result confirmation does not match current match", false);
            return;
        }
        session.resultAcked = true;
        validPacket(connection, session);
    }

    private void returnLobby(WebSocket connection, Session session, WireCodec.Message message) {
        if (session.roomId == null) {
            reject(connection, session, message, "INVALID_PHASE", "No running or finished match", false);
            return;
        }
        Room room = rooms.get(session.roomId);
        if (room == null) {
            reject(connection, session, message, "INVALID_PHASE", "No running or finished match", false);
            return;
        }
        if (room.phase == RoomPhase.RUNNING) {
            if (!Objects.equals(room.matchId, message.matchId)) {
                reject(connection, session, message, "STALE_MATCH", "Request belongs to another match", false);
            } else {
                reject(connection, session, message, "INVALID_PHASE", "Match is still running", false);
            }
            return;
        }
        if (room.phase != RoomPhase.FINISHED) {
            reject(connection, session, message, "INVALID_PHASE", "No finished match in this room", false);
            return;
        }
        String resultId = WireCodec.string(message.body, "resultId");
        if (!Objects.equals(room.matchId, message.matchId) || room.result == null || !room.result.resultId.equals(resultId)) {
            reject(connection, session, message, "RESULT_MISMATCH", "Result does not match current match", false);
            return;
        }
        if (session.returned) {
            ack(connection, session, message);
            sendRoom(connection, session);
            return;
        }
        session.returned = true;
        session.resultAcked = true;
        log.info("[RETURN_LOBBY] Player {} returned in roomId={}, matchId={}",
                session.playerId, session.roomId, message.matchId);
        ack(connection, session, message);
        checkLobbyTransition(session.roomId, room);
    }

    private void checkLobbyTransition(String id, Room room) {
        if (room.phase != RoomPhase.FINISHED) return;
        if (room.members.isEmpty()) return;
        boolean allReturned = true;
        for (WebSocket peer : room.members) {
            Session s = sessions.get(peer);
            if (s != null && !s.returned) {
                allReturned = false;
                break;
            }
        }
        if (allReturned) {
            room.phase = RoomPhase.LOBBY;
            room.matchId = null;
            room.match = null;
            room.result = null;
            for (WebSocket peer : room.members) {
                Session s = sessions.get(peer);
                if (s != null) {
                    s.ready = false;
                    s.returned = false;
                    s.resultAcked = false;
                }
            }
            room.revision++;
            log.info("[STATE_TRANSITION] roomId={} phase transition: FINISHED -> LOBBY, all players returned, state reset, roles retained, revision={}",
                    id, room.revision);
            broadcastRoom(id);
        } else {
            room.revision++;
            broadcastRoom(id);
        }
    }

    private void join(WebSocket connection, Session session, WireCodec.Message message) {
        String id = WireCodec.string(message.body, "roomId");
        if (!id.matches("[A-Z0-9]{1,8}")) {
            reject(connection, session, message, "INVALID_ROOM_ID", "Use 1-8 uppercase letters/digits", false);
        } else if (session.roomId != null) {
            if (!session.roomId.equals(id)) reject(connection, session, message, "INVALID_PHASE", "Leave current room first", false);
            else {
                ack(connection, session, message);
                sendRoom(connection, session); // No revision change or peer broadcast on rejoin.
            }
        } else {
            Room room = rooms.get(id);
            if (room == null && rooms.size() >= MAX_ROOMS) {
                reject(connection, session, message, "SERVER_BUSY", "Maximum 8 rooms", true);
                return;
            }
            if (room != null && room.members.size() >= 2) {
                reject(connection, session, message, "ROOM_FULL", "Room has two players", false);
                return;
            }
            if (room != null && room.phase != RoomPhase.LOBBY) {
                reject(connection, session, message, "ROOM_BUSY", "Room is not in the lobby", false);
                return;
            }
            if (room == null) { room = new Room(); rooms.put(id, room); }
            room.members.add(connection);
            room.revision++;
            session.roomId = id;
            log.info("[ROOM] Player {} joined roomId={}, revision={}, members={}",
                    session.playerId, id, room.revision, room.members.size());
            ack(connection, session, message);
            broadcastRoom(id);
        }
    }

    private void selectRole(WebSocket connection, Session session, WireCodec.Message message) {
        if (session.roomId == null) {
            reject(connection, session, message, "NOT_IN_ROOM", "Join first", false);
            return;
        }
        Role role = Role.valueOf(WireCodec.string(message.body, "role"));
        Room room = rooms.get(session.roomId);
        if (!lobbyPhase(connection, session, message, room)) return;
        for (WebSocket peer : room.members) {
            if (peer != connection && sessions.get(peer).role == role) {
                reject(connection, session, message, "ROLE_TAKEN", "Choose the other role", false);
                return;
            }
        }
        boolean changed = session.role != role;
        session.role = role;
        if (changed) {
            clearPreparation(room);
            room.revision++;
        }
        log.info("[ROOM] Player {} selected role {} in roomId={}, revision={}",
                session.playerId, role, session.roomId, room.revision);
        ack(connection, session, message);
        if (changed) broadcastRoom(session.roomId); else sendRoom(connection, session);
    }

    private boolean lobbyPhase(WebSocket connection, Session session, WireCodec.Message message, Room room) {
        if (room.phase == RoomPhase.LOBBY || room.phase == RoomPhase.COUNTDOWN) return true;
        reject(connection, session, message, "INVALID_PHASE", "Preparation is only available before a match", false);
        return false;
    }

    private void setReady(WebSocket connection, Session session, WireCodec.Message message) {
        if (session.roomId == null) {
            reject(connection, session, message, "NOT_IN_ROOM", "Join first", false);
            return;
        }
        Room room = rooms.get(session.roomId);
        if (!lobbyPhase(connection, session, message, room)) return;
        boolean ready = WireCodec.bool(message.body, "ready");
        if (ready && session.role == null) {
            reject(connection, session, message, "ROLE_REQUIRED", "Select a role before preparing", false);
            return;
        }
        boolean changed = session.ready != ready;
        if (changed) {
            if (!ready) clearPreparation(room);
            else {
                session.ready = true;
                if (canStart(room)) {
                    room.phase = RoomPhase.COUNTDOWN;
                    room.countdownStartedAt = room.countdownBroadcastAt = clock.getAsLong();
                    room.countdownRemainingMs = countdownDurationMs();
                    log.info("[STATE_TRANSITION] roomId={} phase transition: LOBBY -> COUNTDOWN, durationMs={}, revision={}",
                            session.roomId, room.countdownRemainingMs, room.revision + 1);
                }
            }
            room.revision++;
        }
        log.info("[ROOM] Player {} set ready={} in roomId={}, revision={}",
                session.playerId, ready, session.roomId, room.revision);
        ack(connection, session, message);
        if (changed) broadcastRoom(session.roomId); else sendRoom(connection, session);
    }

    private boolean canStart(Room room) {
        if (room.members.size() != 2) return false;
        Session first = sessions.get(room.members.get(0));
        Session second = sessions.get(room.members.get(1));
        return first != null && second != null && first.ready && second.ready
                && first.role != null && second.role != null && first.role != second.role
                && data.mapHash.equals(first.mapHash) && data.mapHash.equals(second.mapHash)
                && data.configHash.equals(first.configHash) && data.configHash.equals(second.configHash);
    }

    private int countdownDurationMs() {
        return (data.rules.countdownTicks * 1000 + data.rules.tickRate - 1) / data.rules.tickRate;
    }

    private void clearPreparation(Room room) {
        if (room.phase == RoomPhase.COUNTDOWN) {
            log.info("[STATE_TRANSITION] Phase transition: COUNTDOWN -> LOBBY (preparation cancelled)");
        }
        room.phase = RoomPhase.LOBBY;
        room.countdownStartedAt = room.countdownBroadcastAt = 0;
        room.countdownRemainingMs = 0;
        for (WebSocket peer : room.members) {
            Session session = sessions.get(peer);
            if (session != null) session.ready = false;
        }
    }

    private void advanceCountdowns(long now) {
        for (Map.Entry<String, Room> entry : new ArrayList<>(rooms.entrySet())) {
            String id = entry.getKey();
            Room room = entry.getValue();
            if (room.phase != RoomPhase.COUNTDOWN) continue;
            // Socket closure can precede its queued onClose callback. Never start with that peer.
            for (WebSocket peer : new ArrayList<>(room.members)) {
                if (!peer.isOpen()) remove(peer);
            }
            if (rooms.get(id) != room || room.phase != RoomPhase.COUNTDOWN) continue;
            if (!canStart(room)) {
                clearPreparation(room);
                room.revision++;
                broadcastRoom(id);
                continue;
            }
            long elapsed = Math.max(0, now - room.countdownStartedAt);
            // 90 integer ticks at 30Hz, independently of the 100ms presentation cadence.
            long ticks = elapsed >= countdownDurationMs() ? data.rules.countdownTicks
                    : elapsed * data.rules.tickRate / 1000;
            if (ticks >= data.rules.countdownTicks) startMatch(id, room, now);
            else if (now - room.countdownBroadcastAt >= COUNTDOWN_BROADCAST_MS) {
                room.countdownRemainingMs = (int) ((data.rules.countdownTicks - ticks) * 1000 / data.rules.tickRate);
                // Keep deadlines anchored to the countdown, not to the last task's actual run time.
                // A late update coalesces missed presentation slots without drifting future deadlines.
                room.countdownBroadcastAt = room.countdownStartedAt
                        + elapsed / COUNTDOWN_BROADCAST_MS * COUNTDOWN_BROADCAST_MS;
                room.revision++;
                broadcastRoom(id);
            }
        }
    }

    private void startMatch(String id, Room room, long now) {
        String police = null, thief = null;
        for (WebSocket peer : room.members) {
            Session session = sessions.get(peer);
            if (session.role == Role.POLICE) police = session.playerId;
            else thief = session.playerId;
        }
        try {
            String matchId = UUID.randomUUID().toString();
            MatchInit init = new MatchInit(matchId, police, thief);
            room.match = new MatchRuntime(engineFactory.create(data.map, data.rules, init), init, data.rules.tickRate, now);
            room.matchId = matchId;
            room.phase = RoomPhase.RUNNING;
            room.countdownRemainingMs = 0;
            room.revision++;
            for (WebSocket peer : room.members) {
                Session session = sessions.get(peer);
                if (session != null) {
                    session.returned = false;
                    session.resultAcked = false;
                }
            }
            log.info("[STATE_TRANSITION] roomId={} matchId={} phase transition: COUNTDOWN -> RUNNING, police={}, thief={}, revision={}",
                    id, matchId, police, thief, room.revision);
            broadcastRoom(id);
            sendViews(room, now);
        } catch (RuntimeException ex) {
            // Do not invent a game result if creation/projection failed. Isolate this room.
            log.error("[MATCH_ERROR] Match startup failed for roomId={}: {}", id, ex.getMessage(), ex);
            rooms.remove(id); // Suppress transient membership/lobby broadcasts during fatal teardown.
            for (WebSocket peer : new ArrayList<>(room.members)) {
                Session session = sessions.get(peer);
                if (session != null) fail(peer, session, null, "SERVER_ERROR", "Could not start match", true);
            }
        }
    }

    private void sendViews(Room room, long now) {
        for (WebSocket peer : new ArrayList<>(room.members)) {
            Session session = sessions.get(peer);
            if (session != null) send(peer, session, "SNAPSHOT", null, room.matchId,
                    room.match.viewFor(session.playerId, now));
        }
    }

    private void advanceMatches(long now) {
        for (Map.Entry<String, Room> entry : new ArrayList<>(rooms.entrySet())) {
            Room room = entry.getValue();
            if (room.phase != RoomPhase.RUNNING) continue;
            // Observe already-closed transports even when their onClose callback is still queued.
            for (WebSocket peer : new ArrayList<>(room.members)) {
                if (!peer.isOpen()) disconnected(peer);
            }
            try {
                room.match.pump(now, step -> {
                    if (step.ended) finishMatch(entry.getKey(), room, step.result, now);
                    else if (step.visibilityChanged || step.tick % 2 == 0) sendViews(room, now);
                });
            } catch (RuntimeException ex) {
                log.error("[MATCH_ERROR] Match runtime error in roomId={}, matchId={}: {}", entry.getKey(), room.matchId, ex.getMessage(), ex);
                try { finishMatch(entry.getKey(), room, room.match.abort(), now); }
                catch (RuntimeException abortFailure) {
                    // An unavailable engine cannot confirm any winner, including a fabricated abort.
                    rooms.remove(entry.getKey());
                    for (WebSocket peer : new ArrayList<>(room.members)) {
                        Session session = sessions.get(peer);
                        if (session != null) fail(peer, session, null, "SERVER_ERROR", "Match could not continue", true);
                    }
                }
            }
        }
    }

    private void finishMatch(String id, Room room, MatchResult result, long now) {
        if (room.phase != RoomPhase.RUNNING) return;
        room.result = result;
        room.phase = RoomPhase.FINISHED;
        room.revision++;
        log.info("[STATE_TRANSITION] roomId={} matchId={} phase transition: RUNNING -> FINISHED, reason={}, winner={}, revision={}",
                id, room.matchId, result.reason, result.winner, room.revision);
        broadcastRoom(id);
        // A terminal snapshot is optional; a projection defect must not hide a confirmed result.
        try { sendViews(room, now); }
        catch (RuntimeException ex) { log.error("[PROJECTION_ERROR] Terminal projection failed in roomId={}, matchId={}: {}", id, room.matchId, ex.getMessage(), ex); }
        for (WebSocket peer : new ArrayList<>(room.members)) {
            Session session = sessions.get(peer);
            if (session != null) {
                session.resultAcked = false;
                session.returned = false;
                session.lastGameOverSentAt = now;
                send(peer, session, "GAME_OVER", null, room.matchId, result);
            }
        }
        for (WebSocket peer : new ArrayList<>(room.members)) {
            Session session = sessions.get(peer);
            if (session == null) continue;
            if (session.departing) {
                for (WireCodec.Message request : session.pendingLeaves.values()) ack(peer, session, request);
                session.pendingLeaves.clear();
                close(peer, 1000, "MATCH_DEPARTURE");
            }
        }
    }

    private void ack(WebSocket connection, Session session, WireCodec.Message message) {
        reply(connection, session, message, "ACK", fields("action", message.type));
    }

    private void reply(WebSocket connection, Session session, WireCodec.Message request, String type, Object body) {
        if (request.requestId != null) {
            session.results.put(request.requestId, new ControlResult(request, type, body));
            if (session.results.size() > MAX_CONTROL_RESULTS) {
                session.results.remove(session.results.keySet().iterator().next());
            }
        }
        send(connection, session, type, request.requestId, request.matchId, body);
        if (!"ERROR".equals(type)) validPacket(connection, session);
    }

    private void reject(WebSocket connection, Session session, WireCodec.Message request,
                        String code, String message, boolean fatal) {
        log.warn("[CONTROLLED_ERROR] Rejection: code={}, message={}, fatal={}, seq={}, requestId={}, matchId={}, roomId={}",
                code, message, fatal, session != null ? session.inSeq : 0,
                request != null ? request.requestId : null, request != null ? request.matchId : null,
                session != null ? session.roomId : null);
        reply(connection, session, request, "ERROR", fields("code", code, "message", message, "fatal", fatal));
        if (fatal) close(connection, 1008, code);
    }

    private void fail(WebSocket connection, Session session, WireCodec.Message request,
                      String code, String message, boolean fatal) {
        // Malformed/stale/conflicting requests must not overwrite a cached control result.
        log.warn("[CONTROLLED_ERROR] Session error: code={}, message={}, fatal={}, seq={}, requestId={}, matchId={}, roomId={}",
                code, message, fatal, session != null ? session.inSeq : 0,
                request != null ? request.requestId : null, request != null ? request.matchId : null,
                session != null ? session.roomId : null);
        send(connection, session, "ERROR", request == null ? null : request.requestId,
                request == null ? null : request.matchId, fields("code", code, "message", message, "fatal", fatal));
        if (fatal) close(connection, 1008, code);
    }

    private void malformed(WebSocket connection, Session session, WireCodec.Message request, boolean tooLarge) {
        boolean fatal = tooLarge || ++session.badMessages >= 3;
        fail(connection, session, request, tooLarge ? "MESSAGE_TOO_LARGE" : "BAD_MESSAGE",
                "Invalid message or field", fatal);
    }

    private boolean isBuffered(WebSocket connection) {
        if (connection instanceof org.java_websocket.WebSocketImpl) {
            return ((org.java_websocket.WebSocketImpl) connection).outQueue.size() > 1;
        }
        try {
            return connection.hasBufferedData();
        } catch (UnsupportedOperationException | AbstractMethodError e) {
            return false;
        }
    }

    private int outboundQueueSize(WebSocket connection) {
        if (connection instanceof org.java_websocket.WebSocketImpl) {
            return ((org.java_websocket.WebSocketImpl) connection).outQueue.size();
        }
        try {
            java.lang.reflect.Method m = connection.getClass().getMethod("outboundQueueSize");
            return (Integer) m.invoke(connection);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void transmit(WebSocket connection, Session session, String type, String requestId, String matchId, Object body) {
        try {
            connection.send(codec.encode(type, ++session.outSeq, requestId, matchId, body));
        } catch (WebsocketNotConnectedException ex) {
            disconnected(connection);
        }
    }

    private void drainOutbound(WebSocket connection, Session session) {
        if (!connection.isOpen()) {
            disconnected(connection);
            return;
        }
        if (!isBuffered(connection) && session.pendingSnapshot != null) {
            OutboundMessage snap = session.pendingSnapshot;
            session.pendingSnapshot = null;
            transmit(connection, session, snap.type, snap.requestId, snap.matchId, snap.body);
        }
    }

    private void send(WebSocket connection, Session session, String type, String requestId, String matchId, Object body) {
        if (!connection.isOpen()) { disconnected(connection); return; }
        drainOutbound(connection, session);

        if ("SNAPSHOT".equals(type)) {
            if (!isBuffered(connection) && session.pendingSnapshot == null) {
                transmit(connection, session, type, requestId, matchId, body);
            } else {
                session.pendingSnapshot = new OutboundMessage(type, requestId, matchId, body);
            }
        } else {
            if (outboundQueueSize(connection) >= MAX_OUTBOUND_CONTROL) {
                log.warn("[BACKPRESSURE] Outbound control queue overflow (>= {}) for playerId={}, closing connection",
                        MAX_OUTBOUND_CONTROL, session.playerId);
                close(connection, 1008, "OUTBOUND_QUEUE_OVERFLOW");
                return;
            }
            transmit(connection, session, type, requestId, matchId, body);
        }
    }

    private RoomState state(String id, Room room) {
        List<RoomPlayer> players = new ArrayList<>();
        for (WebSocket peer : room.members) {
            Session session = sessions.get(peer);
            players.add(new RoomPlayer(session.playerId, session.role, session.ready,
                    session.suspect ? "SUSPECT" : "CONNECTED", session.returned));
        }
        return new RoomState(id, room.revision, room.phase, data.map.mapId, data.mapHash,
                data.configHash, room.matchId, room.countdownRemainingMs, players);
    }

    private void sendRoom(WebSocket connection, Session session) {
        if (session.healthBroadcastPending) flushHealth(session);
        Room room = rooms.get(session.roomId);
        if (room != null && sessions.containsKey(connection)) {
            send(connection, session, "ROOM_STATE", null, null, state(session.roomId, room));
        }
    }

    private void broadcastRoom(String id) {
        Room room = rooms.get(id);
        if (room == null) return;
        for (WebSocket peer : room.members) {
            Session session = sessions.get(peer);
            if (session != null) session.healthBroadcastPending = false;
        }
        for (WebSocket peer : new ArrayList<>(room.members)) {
            Session session = sessions.get(peer);
            if (session != null) sendRoom(peer, session);
        }
    }

    private void close(WebSocket connection, int code, String reason) {
        connection.close(code, reason);
        disconnected(connection);
    }

    private void departure(Session session, DepartureReason reason) {
        Room room = rooms.get(session.roomId);
        if (room != null && room.phase == RoomPhase.RUNNING && !session.departing) {
            session.departing = true;
            room.match.depart(session.playerId, reason);
        }
    }

    private void disconnected(WebSocket connection) {
        Session session = sessions.get(connection);
        Room room = session == null ? null : rooms.get(session.roomId);
        if (room != null && room.phase == RoomPhase.RUNNING) departure(session, DepartureReason.DISCONNECT);
        else remove(connection);
    }

    private void remove(WebSocket connection) {
        Session session = sessions.remove(connection);
        if (session != null) session.healthBroadcastPending = false;
        if (session == null || session.roomId == null) return;
        Room room = rooms.get(session.roomId);
        if (room == null) return;
        room.members.remove(connection);
        log.info("[ROOM] Player {} left roomId={}, remainingMembers={}",
                session.playerId, session.roomId, room.members.size());
        if (room.members.isEmpty()) {
            rooms.remove(session.roomId);
            log.info("[ROOM] Room {} empty and destroyed", session.roomId);
            return;
        }
        if (room.phase == RoomPhase.LOBBY || room.phase == RoomPhase.COUNTDOWN) {
            clearPreparation(room);
            room.revision++;
            broadcastRoom(session.roomId);
        } else if (room.phase == RoomPhase.FINISHED) {
            checkLobbyTransition(session.roomId, room);
        } else {
            room.revision++;
            broadcastRoom(session.roomId);
        }
    }

    private boolean expire(WebSocket connection, Session session, long now) {
        if (!session.welcomed && now - session.openedAt >= HANDSHAKE_MS) {
            log.warn("[HANDSHAKE_TIMEOUT] Session {} handshake timed out after {}ms", session.id, now - session.openedAt);
            fail(connection, session, null, "HANDSHAKE_TIMEOUT", "HELLO was not completed within 3 seconds", true);
            return true;
        }
        if (session.welcomed && !session.departing && now - session.lastPacket >= DISCONNECT_MS) {
            log.warn("[DISCONNECT_TIMEOUT] Session {} inactivity timeout after {}ms", session.id, now - session.lastPacket);
            close(connection, 1001, "DISCONNECT");
            return true;
        }
        if (session.welcomed && !session.departing && !session.suspect && now - session.lastPacket >= SUSPECT_MS) {
            session.suspect = true;
            broadcastHealth(session);
        }
        return false;
    }

    private void validPacket(WebSocket connection, Session session) {
        if (sessions.get(connection) != session || !connection.isOpen()) return;
        session.lastPacket = clock.getAsLong();
        session.badMessages = 0;
        if (session.suspect) {
            session.suspect = false;
            Room room = rooms.get(session.roomId);
            if (room != null) {
                room.revision++;
                session.healthBroadcastPending = true;
            }
        }
    }

    private void flushHealth(Session session) {
        if (session.healthBroadcastPending) {
            session.healthBroadcastPending = false;
            broadcastRoom(session.roomId);
        }
    }

    private void broadcastHealth(Session session) {
        Room room = rooms.get(session.roomId);
        if (room != null) {
            room.revision++;
            broadcastRoom(session.roomId);
        }
    }

    private void advanceFinishedRooms(long now) {
        for (Map.Entry<String, Room> entry : new ArrayList<>(rooms.entrySet())) {
            Room room = entry.getValue();
            if (room.phase != RoomPhase.FINISHED) continue;
            for (WebSocket peer : new ArrayList<>(room.members)) {
                Session session = sessions.get(peer);
                if (session != null && !session.resultAcked && !session.departing) {
                    if (now - session.lastGameOverSentAt >= 1000) {
                        session.lastGameOverSentAt = now;
                        send(peer, session, "GAME_OVER", null, room.matchId, room.result);
                    }
                }
            }
        }
    }

    private void expireSessions() {
        long now = clock.getAsLong();
        for (Map.Entry<WebSocket, Session> entry : new ArrayList<>(sessions.entrySet())) {
            drainOutbound(entry.getKey(), entry.getValue());
            if (sessions.containsKey(entry.getKey())) expire(entry.getKey(), entry.getValue(), now);
        }
        // Expire every session first, including a peer other than the sender, before deadline checks.
        advanceCountdowns(now);
        advanceMatches(now);
        advanceFinishedRooms(now);
    }

    // Tests advance a fake monotonic clock and await this sweep, without sleeping.
    Future<?> checkTimeouts() { return logic.submit(this::expireSessions); }

    private void dispatchMessage(WebSocket connection, Runnable message) {
        InboundAdmission admission = inbound.get(connection);
        if (admission == null || stopping.get() || admission.overflowed.get()) return;
        if (admission.pending.incrementAndGet() > MAX_INBOUND_MESSAGES) {
            admission.pending.decrementAndGet();
            if (admission.overflowed.compareAndSet(false, true)) {
                log.warn("[INBOUND_OVERFLOW] Closing transport with more than {} pending messages", MAX_INBOUND_MESSAGES);
                // Do not wait behind the overloaded consumer. onClose queues normal Departure handling.
                connection.closeConnection(1008, "INBOUND_QUEUE_FULL");
            }
            return;
        }
        dispatch(() -> {
            try {
                // Overflow terminates the whole session; never replay its backlog after disconnect.
                if (!admission.overflowed.get()) message.run();
            } finally {
                admission.pending.decrementAndGet();
            }
        });
    }

    private void dispatch(Runnable event) {
        if (stopping.get()) return;
        try { logic.execute(event); }
        catch (RejectedExecutionException ex) { if (!stopping.get()) throw ex; }
    }

    @Override public void onError(WebSocket connection, Exception ex) {
        if (connection == null) {
            startFailure = ex;
            started.countDown();
            log.error("[STARTUP_ERROR] Server startup failed: {}", ex.getMessage(), ex);
        } else {
            log.error("[TRANSPORT_ERROR] WebSocket transport error: {}", ex.getMessage(), ex);
            dispatch(() -> {
                Session session = sessions.get(connection);
                if (session != null) fail(connection, session, null, "SERVER_ERROR", "Transport error", true);
            });
        }
    }

    @Override public void onStart() {
        if (automaticTimeouts && !stopping.get()) {
            // Scan faster than the broadcast cadence so millisecond rounding/early timer wakes
            // cannot defer a due update by another full 100ms scan. MatchRuntime independently
            // converts elapsed monotonic time into 30Hz steps; a scan is not a simulation tick.
            logic.scheduleAtFixedRate(this::expireSessions, SESSION_SCAN_MS, SESSION_SCAN_MS, TimeUnit.MILLISECONDS);
        }
        started.countDown();
        log.info("[STARTUP] LobbyServer started and listening on ws://0.0.0.0:{}/game, map={}, mapHash={}, configHash={}",
                getPort(), data.map.mapId, data.mapHash, data.configHash);
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(result);
    }

    private static String safeRemote(WebSocket connection) {
        if (connection == null) return "unknown";
        try {
            Object addr = connection.getRemoteSocketAddress();
            return addr != null ? addr.toString() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
