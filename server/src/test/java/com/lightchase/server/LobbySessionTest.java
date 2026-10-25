package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.network.WireCodec;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.junit.*;
import static org.junit.Assert.*;

/** Fake transports and a controllable clock test B2 without sleeping or starting an engine. */
public class LobbySessionTest {
    private final WireCodec codec = new WireCodec();
    private final DataFiles data = DataFiles.bundled();
    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger engineCreations = new AtomicInteger();
    private LobbyServer server;

    private static final class Peer {
        final HealthBroadcastFilter health = new HealthBroadcastFilter();
        final List<String> incoming = new ArrayList<>();
        final Set<String> deliveryThreads = new HashSet<>();
        final WebSocket socket;
        boolean open = true, failNextSend;
        String closeReason;
        long seq, receivedSeq;

        Peer() {
            socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isOpen": return open;
                            case "send":
                                if (failNextSend) {
                                    failNextSend = false; open = false;
                                    throw new WebsocketNotConnectedException();
                                }
                                if (!health.healthOnly((String) args[0])) incoming.add((String) args[0]);
                                deliveryThreads.add(Thread.currentThread().getName()); return null;
                            case "close": open = false; closeReason = (String) args[1]; return null;
                            case "equals": return proxy == args[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            case "toString": return "TEST_ONLY_PEER";
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    @Before public void create() {
        server = new LobbyServer(new InetSocketAddress("127.0.0.1", 0), data, clock::get, false,
                (map, rules, init) -> {
                    engineCreations.incrementAndGet();
                    throw new IllegalStateException("TEST_ONLY: these session tests must never start an engine");
                });
    }

    @After public void stop() throws Exception { server.shutdown(); }

    @Test public void onlyGameEndpointIsAccepted() throws Exception {
        Peer peer = open("/wrong");
        error(peer, "BAD_MESSAGE", true); assertFalse(peer.open);
    }

    @Test public void handshakeIsRequiredBeforeJoiningOrHeartbeating() throws Exception {
        Peer join = open("/game");
        send(join, "JOIN_ROOM", "r1", null, fields("roomId", "TEST"));
        error(join, "HANDSHAKE_REQUIRED", true); assertFalse(join.open);
        Peer heartbeat = open("/game");
        send(heartbeat, "HEARTBEAT", null, null, fields("nonce", 1));
        error(heartbeat, "HANDSHAKE_REQUIRED", true);
    }

    @Test public void bothResourceHashesAreCheckedBeforeIdentityIsPublished() throws Exception {
        Peer map = open("/game");
        send(map, "HELLO", "r1", null, fields("clientBuild", "TEST_ONLY", "mapHash", "wrong", "configHash", data.configHash));
        error(map, "MAP_MISMATCH", true); assertTrue(map.incoming.isEmpty());
        Peer config = open("/game");
        send(config, "HELLO", "r1", null, fields("clientBuild", "TEST_ONLY", "mapHash", data.mapHash, "configHash", "wrong"));
        error(config, "CONFIG_MISMATCH", true); assertTrue(config.incoming.isEmpty());
    }

    @Test public void handshakeDeadlineIsAnchoredToConnectionOpen() throws Exception {
        Peer peer = open("/game");
        clock.set(1000); raw(peer, "{}"); error(peer, "BAD_MESSAGE", false);
        clock.set(2000); raw(peer, "{}"); error(peer, "BAD_MESSAGE", false);
        clock.set(2999); sync(); assertTrue(peer.open); assertTrue(peer.incoming.isEmpty());
        clock.set(3000); sync(); error(peer, "HANDSHAKE_TIMEOUT", true); assertFalse(peer.open);
    }

    @Test public void lateHelloCannotSlipInBetweenTimeoutSweeps() throws Exception {
        Peer peer = open("/game"); clock.set(3000);
        send(peer, "HELLO", "r1", null, helloBody());
        error(peer, "HANDSHAKE_TIMEOUT", true); assertTrue(peer.incoming.isEmpty());
    }

    @Test public void heartbeatRefreshesIdleDeadlineButStalePacketsDoNot() throws Exception {
        Peer peer = open("/game"); welcome(peer);
        clock.set(4000); send(peer, "HEARTBEAT", null, null, fields("nonce", 42));
        WireCodec.Message pong = take(peer, "PONG");
        assertEquals(42, WireCodec.integer(pong.body, "nonce"));
        assertEquals(4000, WireCodec.integer(pong.body, "serverTimeMs"));
        clock.set(7000);
        raw(peer, codec.encode("HEARTBEAT", peer.seq, null, null, fields("nonce", 43)));
        error(peer, "STALE_MESSAGE", false);
        clock.set(8999); sync(); assertTrue(peer.open);
        clock.set(9000); sync(); assertFalse(peer.open); assertEquals("DISCONNECT", peer.closeReason);
    }

    @Test public void consumingMalformedBodySequenceDoesNotRefreshIdleDeadline() throws Exception {
        Peer peer = open("/game"); welcome(peer);
        clock.set(4000);
        send(peer, "JOIN_ROOM", "bad-join", null, fields());
        error(peer, "BAD_MESSAGE", false);
        clock.set(4999); sync(); assertTrue(peer.open);
        clock.set(5000); sync(); assertFalse(peer.open); assertEquals("DISCONNECT", peer.closeReason);
    }

    @Test public void threeConsecutiveMalformedMessagesCloseOnlyTheBadSession() throws Exception {
        Peer bad = open("/game"), healthy = open("/game"); welcome(bad); welcome(healthy);
        raw(bad, "{}"); error(bad, "BAD_MESSAGE", false);
        raw(bad, "{}"); error(bad, "BAD_MESSAGE", false);
        assertTrue(bad.open);
        raw(bad, "{}"); error(bad, "BAD_MESSAGE", true); assertFalse(bad.open);
        send(healthy, "HEARTBEAT", null, null, fields("nonce", 1)); take(healthy, "PONG");
    }

    @Test public void validPacketResetsConsecutiveMalformedCount() throws Exception {
        Peer peer = open("/game"); welcome(peer);
        raw(peer, "{}"); error(peer, "BAD_MESSAGE", false);
        raw(peer, "{}"); error(peer, "BAD_MESSAGE", false);
        send(peer, "HEARTBEAT", null, null, fields("nonce", 1)); take(peer, "PONG");
        raw(peer, "{}"); error(peer, "BAD_MESSAGE", false);
        raw(peer, "{}"); error(peer, "BAD_MESSAGE", false); assertTrue(peer.open);
    }

    @Test public void oversizeMessageClosesImmediately() throws Exception {
        Peer peer = open("/game");
        raw(peer, " ".repeat(WireCodec.MAX_BYTES + 1));
        error(peer, "MESSAGE_TOO_LARGE", true); assertFalse(peer.open);
    }

    @Test public void binaryFramesAreRejectedInsteadOfBeingSilentlyIgnored() throws Exception {
        Peer peer = open("/game"); welcome(peer);
        server.onMessage(peer.socket, ByteBuffer.wrap(new byte[]{1, 2})); sync();
        error(peer, "BAD_MESSAGE", false); assertTrue(peer.open);
    }

    @Test public void replayedHelloWithReorderedKeysKeepsTheSameIdentity() throws Exception {
        Peer peer = open("/game"); WireCodec.Message original = welcome(peer);
        send(peer, "HELLO", "hello", null, fields("configHash", data.configHash, "mapHash", data.mapHash, "clientBuild", "TEST_ONLY"));
        assertEquals(original.body, take(peer, "WELCOME").body);
        send(peer, "HELLO", "hello", null, fields("clientBuild", "different", "mapHash", data.mapHash, "configHash", data.configHash));
        error(peer, "REQUEST_ID_REUSED", false);
        send(peer, "HELLO", "hello", null, helloBody());
        assertEquals(original.body, take(peer, "WELCOME").body);
    }

    @Test public void eachNewConnectionStartsItsOwnSequenceAtOne() throws Exception {
        Peer a = open("/game");
        raw(a, codec.encode("HELLO", 2, "hello", null, helloBody()));
        error(a, "BAD_MESSAGE", false);
        WireCodec.Message identityA = welcome(a); assertEquals(2, identityA.seq);
        Peer b = open("/game");
        WireCodec.Message identityB = welcome(b); assertEquals(1, identityB.seq);
        assertNotEquals(identityA.body.get("sessionId"), identityB.body.get("sessionId"));
    }

    @Test public void repeatedJoinIsIdempotentForBothCachedAndNewRequestIds() throws Exception {
        Peer a = open("/game"), b = open("/game"); welcome(a); welcome(b);
        join(a, "TEST"); join(b, "TEST"); RoomState before = room(a);
        send(a, "JOIN_ROOM", "join-TEST", null, fields("roomId", "TEST"));
        take(a, "ACK"); RoomState cached = room(a);
        send(a, "JOIN_ROOM", "new-join", null, fields("roomId", "TEST"));
        take(a, "ACK"); RoomState fresh = room(a);
        assertEquals(before.revision, cached.revision); assertEquals(before.revision, fresh.revision);
        assertEquals(2, fresh.players.size()); assertTrue(b.incoming.isEmpty());
    }

    @Test public void conflictingRequestCannotMoveThePlayerOrPoisonCachedSuccess() throws Exception {
        Peer peer = open("/game"); welcome(peer); join(peer, "TEST");
        send(peer, "JOIN_ROOM", "join-TEST", null, fields("roomId", "OTHER"));
        error(peer, "REQUEST_ID_REUSED", false);
        send(peer, "JOIN_ROOM", "join-TEST", null, fields("roomId", "TEST"));
        take(peer, "ACK"); assertEquals("TEST", room(peer).roomId);
        send(peer, "JOIN_ROOM", "switch", null, fields("roomId", "OTHER"));
        error(peer, "INVALID_PHASE", false);
    }

    @Test public void cachedBusinessErrorIsStableAndRecoverableWithANewRequestId() throws Exception {
        Peer peer = open("/game"); welcome(peer);
        send(peer, "JOIN_ROOM", "bad", null, fields("roomId", "bad#"));
        WireCodec.Message first = error(peer, "INVALID_ROOM_ID", false);
        send(peer, "JOIN_ROOM", "bad", null, fields("roomId", "bad#"));
        assertEquals(first.body, error(peer, "INVALID_ROOM_ID", false).body);
        send(peer, "JOIN_ROOM", "bad", null, fields("roomId", "TEST")); error(peer, "REQUEST_ID_REUSED", false);
        join(peer, "TEST"); assertTrue(peer.open);
    }

    @Test public void oldControlResultsAreEvictedAfter128NewResults() throws Exception {
        Peer peer = open("/game"); welcome(peer);
        for (int i = 0; i < 128; i++) {
            clock.addAndGet(200);
            send(peer, "JOIN_ROOM", "join-" + i, null, fields("roomId", "TEST"));
            take(peer, "ACK"); room(peer);
        }
        send(peer, "HELLO", "hello", null, helloBody());
        error(peer, "INVALID_PHASE", false); // Original WELCOME is no longer cached.
    }

    @Test public void roomLimitDoesNotBlockJoiningAnExistingRoomAndEmptyRoomsAreReleased() throws Exception {
        List<Peer> owners = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Peer owner = open("/game"); welcome(owner); join(owner, "R" + i); owners.add(owner);
        }
        Peer overflow = open("/game"); welcome(overflow);
        send(overflow, "JOIN_ROOM", "ninth", null, fields("roomId", "NINTH"));
        error(overflow, "SERVER_BUSY", true); assertFalse(overflow.open);
        Peer second = open("/game"); welcome(second); assertEquals(2, join(second, "R0").players.size());
        room(owners.get(0));
        send(owners.get(1), "LEAVE_ROOM", "leave", null, fields("reason", "QUIT")); take(owners.get(1), "ACK");
        Peer replacement = open("/game"); welcome(replacement);
        assertEquals(1, join(replacement, "NINTH").players.size());
    }

    @Test public void departureReleasesTheSlotAndRoleAndNewConnectionGetsNewIdentity() throws Exception {
        Peer a = open("/game"), b = open("/game");
        WireCodec.Message old = welcome(a); welcome(b); join(a, "TEST"); join(b, "TEST"); room(a);
        send(a, "SELECT_ROLE", "role", null, fields("role", "POLICE")); take(a, "ACK"); room(a); room(b);
        send(a, "LEAVE_ROOM", "leave", null, fields("reason", "BACKGROUND")); take(a, "ACK");
        RoomState remaining = room(b); assertEquals(1, remaining.players.size());
        Peer c = open("/game"); WireCodec.Message identity = welcome(c);
        assertNotEquals(old.body.get("playerId"), identity.body.get("playerId"));
        assertNotEquals(old.body.get("sessionId"), identity.body.get("sessionId"));
        join(c, "TEST"); room(b);
        send(c, "SELECT_ROLE", "role", null, fields("role", "POLICE")); take(c, "ACK"); room(c); room(b);
    }

    @Test public void abruptSocketCloseAndSendRaceCleanUpMembershipIdempotently() throws Exception {
        Peer a = open("/game"), b = open("/game"); welcome(a); welcome(b); join(a, "TEST"); join(b, "TEST"); room(a);
        a.failNextSend = true;
        send(a, "SELECT_ROLE", "role", null, fields("role", "POLICE"));
        assertFalse(a.open); assertEquals(1, room(b).players.size());
        b.incoming.clear();
        server.onClose(a.socket, 1006, "closed", true); sync(); assertTrue(b.incoming.isEmpty());
        b.open = false; server.onClose(b.socket, 1006, "closed", true); sync();
        Peer c = open("/game"); welcome(c);
        assertEquals(1, join(c, "TEST").revision); // Empty room was destroyed and recreated.
    }

    @Test public void notInRoomAndMatchPhaseErrorsEchoTheirCorrelation() throws Exception {
        Peer peer = open("/game"); welcome(peer);
        send(peer, "LEAVE_ROOM", "leave", null, fields("reason", "QUIT")); error(peer, "NOT_IN_ROOM", false);
        assertTrue(peer.open);
        send(peer, "RETURN_LOBBY", "return", "old-match", fields("resultId", "old-result"));
        WireCodec.Message error = error(peer, "INVALID_PHASE", false);
        assertEquals("return", error.requestId); assertEquals("old-match", error.matchId);
    }

    @Test public void staleErrorsAreThrottledAndDoNotReplayCachedSideEffects() throws Exception {
        Peer peer = open("/game"); welcome(peer); join(peer, "TEST");
        String stale = codec.encode("JOIN_ROOM", peer.seq, "join-TEST", null, fields("roomId", "TEST"));
        raw(peer, stale); error(peer, "STALE_MESSAGE", false);
        raw(peer, stale); assertTrue(peer.incoming.isEmpty());
        clock.set(999); raw(peer, stale); assertTrue(peer.incoming.isEmpty());
        clock.set(1000); raw(peer, stale); error(peer, "STALE_MESSAGE", false);
    }

    @Test public void allRepliesAndRoomMutationsAreSerializedOnTheLogicThread() throws Exception {
        Peer peer = open("/game"); welcome(peer); join(peer, "TEST");
        assertEquals(Collections.singleton("lightchase-lobby-logic"), peer.deliveryThreads);
    }

    @Test public void socketAlreadyClosedBeforeItsQueuedOnCloseCannotStartAtDeadline() throws Exception {
        Peer a = open("/game"), b = open("/game"); welcome(a); welcome(b);
        join(a, "TEST"); join(b, "TEST"); room(a);
        send(a, "SELECT_ROLE", "role-a", null, fields("role", "POLICE")); take(a, "ACK"); room(a); room(b);
        send(b, "SELECT_ROLE", "role-b", null, fields("role", "THIEF")); take(b, "ACK"); room(a); room(b);
        send(a, "SET_READY", "ready-a", null, fields("ready", true)); take(a, "ACK"); room(a); room(b);
        send(b, "SET_READY", "ready-b", null, fields("ready", true)); take(b, "ACK"); room(a); room(b);
        clock.set(3000); b.open = false; sync();
        RoomState remaining = room(a); assertEquals(RoomPhase.LOBBY, remaining.phase);
        assertEquals(1, remaining.players.size()); assertFalse(remaining.players.get(0).ready);
        assertEquals(0, engineCreations.get());
        server.onClose(b.socket, 1006, "late callback", true); sync(); assertTrue(a.incoming.isEmpty());
    }

    private Peer open(String path) throws Exception {
        Peer peer = new Peer();
        ClientHandshake handshake = (ClientHandshake) Proxy.newProxyInstance(ClientHandshake.class.getClassLoader(),
                new Class<?>[]{ClientHandshake.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getResourceDescriptor")) return path;
                    throw new UnsupportedOperationException(method.getName());
                });
        server.onOpen(peer.socket, handshake); sync(); return peer;
    }

    private WireCodec.Message welcome(Peer peer) throws Exception {
        send(peer, "HELLO", "hello", null, helloBody()); return take(peer, "WELCOME");
    }

    private RoomState join(Peer peer, String id) throws Exception {
        send(peer, "JOIN_ROOM", "join-" + id, null, fields("roomId", id)); take(peer, "ACK"); return room(peer);
    }

    private Map<String, Object> helloBody() { return fields("clientBuild", "TEST_ONLY", "mapHash", data.mapHash, "configHash", data.configHash); }

    private void send(Peer peer, String type, String request, String match, Object body) throws Exception {
        raw(peer, codec.encode(type, ++peer.seq, request, match, body));
    }

    private void raw(Peer peer, String text) throws Exception { server.onMessage(peer.socket, text); sync(); }
    private void sync() throws Exception { server.checkTimeouts().get(3, TimeUnit.SECONDS); }
    private RoomState room(Peer peer) { return codec.body(take(peer, "ROOM_STATE"), RoomState.class); }

    private WireCodec.Message error(Peer peer, String code, boolean fatal) {
        WireCodec.Message message = take(peer, "ERROR");
        assertEquals(code, WireCodec.string(message.body, "code")); assertEquals(fatal, WireCodec.bool(message.body, "fatal"));
        return message;
    }

    private WireCodec.Message take(Peer peer, String type) {
        assertFalse("Expected " + type, peer.incoming.isEmpty());
        WireCodec.Message message = codec.decodeServer(peer.incoming.remove(0));
        assertEquals(type, message.type); assertTrue(message.seq > peer.receivedSeq); peer.receivedSeq = message.seq;
        return message;
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
