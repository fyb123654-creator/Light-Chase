package com.lightchase.network;

import com.google.gson.*;
import com.lightchase.contracts.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Real WebSocket regressions for room authority and lifecycle, with ACK receive barriers. */
public class GatewayRoomStateTest {
    private final WireCodec codec = new WireCodec();
    private final List<RoomState> rooms = new CopyOnWriteArrayList<>();
    private final BlockingQueue<GatewayError> errors = new LinkedBlockingQueue<>();
    private final BlockingQueue<ControlStatus> controls = new LinkedBlockingQueue<>();
    private ClientGatewayHealthTest.MockPeerServer peer;
    private WebSocketClientGateway gateway;
    private WireCodec.Message hello;
    private long seq = 20;

    @Before public void open() throws Exception {
        peer = new ClientGatewayHealthTest.MockPeerServer(); peer.start();
        assertTrue(peer.started.await(5, TimeUnit.SECONDS));
        gateway = new WebSocketClientGateway(() -> 1000, 1000, 3000, 5000, 250, 0);
        gateway.connect(new ConnectionOptions("127.0.0.1", peer.getPort(), " test ", "test",
                "map-hash-v1", "config-hash-v1"), new ClientGatewayHealthTest.TestListener() {
            @Override public void onRoom(RoomState room) { rooms.add(room); }
            @Override public void onControl(ControlStatus status) {
                if (status.state == ControlStatus.State.SUCCEEDED) controls.add(status);
            }
            @Override public void onError(GatewayError error) { errors.add(error); }
        });
        hello = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull(hello);
    }
    @After public void stop() throws Exception {
        if (gateway != null) gateway.close();
        if (peer != null) peer.stop(1000);
    }

    @Test public void roomCannotPrecedeWelcome() throws Exception {
        send(room(10, RoomPhase.LOBBY, null)); assertRejected(0);
    }
    @Test public void oldRevisionCannotOverwriteState() throws Exception {
        welcome(); accept(room(10, RoomPhase.LOBBY, null));
        send(room(9, RoomPhase.COUNTDOWN, null)); fence();
        assertEquals(1, rooms.size()); assertEquals(10, last().revision);
    }
    @Test public void identicalRevisionRetransmissionIsIdempotent() throws Exception {
        welcome(); JsonObject state = room(10, RoomPhase.LOBBY, null); accept(state);
        send(state); fence(); assertEquals(1, rooms.size()); assertTrue(errors.isEmpty());
    }
    @Test public void sameRevisionDifferentContentsIsRejected() throws Exception {
        rejectLobby(v -> v.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("ready", true), 10);
    }
    @Test public void wrongRoomIsRejected() throws Exception { rejectLobby(v -> v.addProperty("roomId", "OTHER"), 11); }
    @Test public void wrongMapHashIsRejected() throws Exception { rejectLobby(v -> v.addProperty("mapHash", "wrong"), 11); }
    @Test public void wrongConfigHashIsRejected() throws Exception { rejectLobby(v -> v.addProperty("configHash", "wrong"), 11); }
    @Test public void mapIdCannotChangeWithinConnection() throws Exception { rejectLobby(v -> v.addProperty("mapId", "other-map"), 11); }
    @Test public void duplicateMembersAreRejected() throws Exception {
        rejectLobby(v -> v.getAsJsonArray("players").get(1).getAsJsonObject().addProperty("playerId", "p-1"), 11);
    }
    @Test public void welcomedIdentityMustBeAMember() throws Exception {
        rejectLobby(v -> v.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("playerId", "stranger"), 11);
    }
    @Test public void duplicateRoleSlotsAreRejected() throws Exception {
        rejectLobby(v -> v.getAsJsonArray("players").get(1).getAsJsonObject().addProperty("role", "POLICE"), 11);
    }
    @Test public void readyRequiresAssignedRole() throws Exception {
        rejectLobby(v -> {
            JsonObject self = v.getAsJsonArray("players").get(0).getAsJsonObject();
            self.add("role", JsonNull.INSTANCE); self.addProperty("ready", true);
        }, 11);
    }
    @Test public void returnedIsOnlyValidInFinished() throws Exception {
        rejectLobby(v -> v.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("returned", true), 11);
    }
    @Test public void lobbyCannotCarryMatchId() throws Exception { rejectLobby(v -> v.addProperty("matchId", "m-1"), 11); }
    @Test public void runningRequiresMatchId() throws Exception {
        welcome(); JsonObject state = room(10, RoomPhase.RUNNING, null); send(state); assertRejected(0);
    }
    @Test public void countdownNeedsTwoReadyPlayers() throws Exception {
        welcome(); JsonObject state = room(10, RoomPhase.COUNTDOWN, null);
        state.getAsJsonArray("players").get(1).getAsJsonObject().addProperty("ready", false);
        send(state); assertRejected(0);
    }
    @Test public void countdownRangeIsChecked() throws Exception {
        welcome(); JsonObject state = room(10, RoomPhase.COUNTDOWN, null);
        state.addProperty("countdownRemainingMs", 3001); send(state); assertRejected(0);
    }
    @Test public void nonCountdownRequiresZeroCountdown() throws Exception {
        rejectLobby(v -> v.addProperty("countdownRemainingMs", 1000), 11);
    }
    @Test public void runningRequiresTwoAssignedMembers() throws Exception {
        welcome(); JsonObject state = room(10, RoomPhase.RUNNING, "m-1");
        state.getAsJsonArray("players").remove(1); send(state); assertRejected(0);
    }
    @Test public void moreThanTwoMembersIsRejected() throws Exception {
        rejectLobby(v -> v.getAsJsonArray("players").add(v.getAsJsonArray("players").get(0).deepCopy()), 11);
    }
    @Test public void finishedCannotReenterRunningWithSameMatch() throws Exception {
        welcome(); accept(room(10, RoomPhase.RUNNING, "m-1"));
        accept(room(11, RoomPhase.FINISHED, "m-1"));
        send(room(12, RoomPhase.RUNNING, "m-1")); assertRejected(2);
    }
    @Test public void gameOverClosesMatchEvenWithoutFinishedRoomOrTerminalView() throws Exception {
        welcome(); accept(room(10, RoomPhase.RUNNING, "m-1")); result("m-1");
        send(room(11, RoomPhase.RUNNING, "m-1")); assertRejected(1);
    }
    @Test public void finishedMatchCannotBeReusedAfterLobby() throws Exception {
        welcome(); accept(room(10, RoomPhase.RUNNING, "m-1"));
        accept(room(11, RoomPhase.FINISHED, "m-1")); accept(room(12, RoomPhase.LOBBY, null));
        send(room(15, RoomPhase.RUNNING, "m-1")); assertRejected(3);
    }
    @Test public void immediateRunningToLobbyCannotSkipFinishRevision() throws Exception {
        welcome(); accept(room(10, RoomPhase.RUNNING, "m-1"));
        send(room(11, RoomPhase.LOBBY, null)); assertRejected(1);
    }
    @Test public void rolesCannotChangeDuringSameMatch() throws Exception {
        welcome(); accept(room(10, RoomPhase.RUNNING, "m-1"));
        JsonObject state = room(11, RoomPhase.RUNNING, "m-1"); swapRoles(state);
        send(state); assertRejected(1);
    }
    @Test public void finishedAllowsRemainingMemberAndReturned() throws Exception {
        welcome(); accept(room(10, RoomPhase.RUNNING, "m-1"));
        JsonObject state = room(11, RoomPhase.FINISHED, "m-1");
        state.getAsJsonArray("players").remove(1);
        state.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("returned", true);
        accept(state); assertEquals(1, last().players.size());
    }
    @Test public void skippedIntermediateUpdatesAndNormalNextMatchAreAccepted() throws Exception {
        welcome(); accept(room(10, RoomPhase.LOBBY, null));
        accept(room(12, RoomPhase.RUNNING, "m-1")); result("m-1");
        // FINISHED, lobby, countdown and optional terminal view were not received.
        JsonObject next = room(16, RoomPhase.RUNNING, "m-2"); swapRoles(next); accept(next);
        assertEquals("m-2", last().matchId); assertEquals(Role.THIEF, last().players.get(0).role);
        accept(room(17, RoomPhase.FINISHED, "m-2", Role.THIEF));
        accept(room(18, RoomPhase.LOBBY, null, Role.THIEF));
        accept(room(19, RoomPhase.COUNTDOWN, null, Role.THIEF));
        accept(room(20, RoomPhase.RUNNING, "m-3", Role.THIEF));
        assertEquals("m-3", last().matchId); assertTrue(errors.isEmpty());
    }
    @Test public void cancelledCountdownAndRestartAreAccepted() throws Exception {
        welcome(); accept(room(10, RoomPhase.COUNTDOWN, null));
        accept(room(11, RoomPhase.LOBBY, null));
        accept(room(12, RoomPhase.COUNTDOWN, null)); assertTrue(errors.isEmpty());
    }
    @Test public void adjacentCountdownCannotIncreaseRemainingTime() throws Exception {
        welcome(); JsonObject state = room(10, RoomPhase.COUNTDOWN, null);
        state.addProperty("countdownRemainingMs", 1000); accept(state);
        send(room(11, RoomPhase.COUNTDOWN, null)); assertRejected(1);
    }
    @Test public void adjacentCountdownCannotSwapRoles() throws Exception {
        welcome(); accept(room(10, RoomPhase.COUNTDOWN, null));
        JsonObject changed = room(11, RoomPhase.COUNTDOWN, null); swapRoles(changed);
        send(changed); assertRejected(1);
    }
    @Test public void immediateMatchStartPreservesCountdownAssignments() throws Exception {
        welcome(); accept(room(10, RoomPhase.COUNTDOWN, null));
        JsonObject changed = room(11, RoomPhase.RUNNING, "m-1"); swapRoles(changed);
        send(changed); assertRejected(1);
    }
    @Test public void missedCountdownCancellationAndRestartAreAccepted() throws Exception {
        welcome(); JsonObject first = room(10, RoomPhase.COUNTDOWN, null);
        first.addProperty("countdownRemainingMs", 1000); accept(first);
        JsonObject restarted = room(13, RoomPhase.COUNTDOWN, null); swapRoles(restarted); accept(restarted);
        accept(room(14, RoomPhase.RUNNING, "m-1", Role.THIEF)); assertTrue(errors.isEmpty());
    }
    @Test public void finishedDoesNotRequirePreviouslyDeliveredSnapshot() throws Exception {
        welcome(); accept(room(10, RoomPhase.LOBBY, null));
        accept(room(13, RoomPhase.FINISHED, "m-1")); result("m-1");
        assertEquals(RoomPhase.FINISHED, last().phase); assertTrue(errors.isEmpty());
    }
    @Test public void skippedNextMatchStartStillClearsPreviousResultReference() throws Exception {
        welcome(); accept(room(10, RoomPhase.RUNNING, "m-1")); result("m-1");
        accept(room(15, RoomPhase.FINISHED, "m-2"));
        gateway.returnToLobby();
        GatewayError error = errors.poll(1, TimeUnit.SECONDS);
        assertNotNull("Must await the new match result instead of using the old result ID", error);
        assertEquals("INVALID_PHASE", error.code);
        assertTrue("No stale RETURN_LOBBY request may be sent", peer.messages.isEmpty());
        result("m-2");
        gateway.returnToLobby();
        WireCodec.Message request = peer.pollMessage(3, TimeUnit.SECONDS);
        assertNotNull(request); assertEquals("RETURN_LOBBY", request.type);
        assertEquals("m-2", request.matchId); assertEquals("result-m-2", WireCodec.string(request.body, "resultId"));
    }

    private void welcome() throws Exception {
        peer.sendWelcome(hello.requestId, "map-hash-v1", "config-hash-v1");
        WireCodec.Message join = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull(join);
        assertEquals("JOIN_ROOM", join.type); assertEquals("TEST", WireCodec.string(join.body, "roomId"));
        peer.conn.send(codec.encode("ACK", ++seq, join.requestId, null, ProtocolContractTest.fields("action", "JOIN_ROOM")));
    }
    private void fence() throws Exception {
        gateway.setReady(false);
        WireCodec.Message request = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull(request);
        assertEquals("SET_READY", request.type);
        peer.conn.send(codec.encode("ACK", ++seq, request.requestId, null, ProtocolContractTest.fields("action", "SET_READY")));
        ControlStatus status;
        do { status = controls.poll(3, TimeUnit.SECONDS); assertNotNull("ACK receive barrier", status); }
        while (!request.requestId.equals(status.requestId));
    }
    private void result(String match) throws Exception {
        peer.conn.send(codec.encode("GAME_OVER", ++seq, null, match,
                new MatchResult("result-" + match, match, Role.POLICE, ResultReason.CAPTURED, null, 100, 3, 0, 100)));
        WireCodec.Message ack = peer.pollMessage(3, TimeUnit.SECONDS);
        assertNotNull(ack); assertEquals("RESULT_ACK", ack.type);
    }
    private void accept(JsonObject state) throws Exception {
        int before = rooms.size(); send(state); fence();
        assertEquals("Valid room must be delivered", before + 1, rooms.size());
    }
    private void rejectLobby(Consumer<JsonObject> change, long revision) throws Exception {
        welcome(); accept(room(10, RoomPhase.LOBBY, null));
        JsonObject invalid = room(revision, RoomPhase.LOBBY, null); change.accept(invalid);
        send(invalid); assertRejected(1);
    }
    private void assertRejected(int expectedRooms) throws Exception {
        GatewayError error = errors.poll(1, TimeUnit.SECONDS);
        assertNotNull("Invalid room must report a controlled protocol error", error);
        assertEquals("BAD_MESSAGE", error.code); assertEquals(expectedRooms, rooms.size());
    }
    private RoomState last() { return rooms.get(rooms.size() - 1); }
    private void send(JsonObject state) { peer.conn.send(codec.encode("ROOM_STATE", ++seq, null, null, state)); }
    private JsonObject room(long revision, RoomPhase phase, String match) {
        return room(revision, phase, match, Role.POLICE);
    }
    private JsonObject room(long revision, RoomPhase phase, String match, Role selfRole) {
        boolean ready = phase == RoomPhase.COUNTDOWN || phase == RoomPhase.RUNNING || phase == RoomPhase.FINISHED;
        RoomState state = new RoomState("TEST", revision, phase, "test-room", "map-hash-v1", "config-hash-v1", match,
                phase == RoomPhase.COUNTDOWN ? 3000 : 0, Arrays.asList(
                new RoomPlayer("p-1", selfRole, ready, "CONNECTED", false),
                new RoomPlayer("p-2", selfRole == Role.POLICE ? Role.THIEF : Role.POLICE, ready, "CONNECTED", false)));
        return codec.decode(codec.encode("ROOM_STATE", 1, null, null, state)).body;
    }
    private void swapRoles(JsonObject state) {
        state.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("role", "THIEF");
        state.getAsJsonArray("players").get(1).getAsJsonObject().addProperty("role", "POLICE");
    }
}
