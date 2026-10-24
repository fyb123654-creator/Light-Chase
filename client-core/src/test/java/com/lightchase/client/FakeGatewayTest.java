package com.lightchase.client;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import org.junit.Test;
import static org.junit.Assert.*;

public class FakeGatewayTest {

    private static class Listener implements GatewayListener {
        PlayerView view;
        String state;
        SessionInfo session;
        ControlStatus control;
        GatewayError error;
        RoomState room;
        MatchResult result;
        int results;

        public void onSession(SessionInfo session) { this.session = session; }
        final java.util.List<String> controlLog = new java.util.ArrayList<>();
        public void onControl(ControlStatus status) { control = status; controlLog.add(status.action + ':' + status.state); }
        public void onConnection(ConnectionStatus s) { state = s.state; }
        public void onRoom(RoomState s) {
            assertNotNull("room update must never precede the session", session);
            assertEquals(session.playerId, s.players.get(0).playerId);
            room = s;
        }
        public void onView(PlayerView s) { view = s; }
        public void onResult(MatchResult s) { result = s; results++; }
        public void onError(GatewayError e) { error = e; }
    }

    private static FakeGateway fixture(DataFiles data, Listener listener) {
        FakeGateway g = new FakeGateway();
        g.configure(data);
        g.connect(new ConnectionOptions("offline", 8080, "DEMO", "test", data.mapHash, data.configHash), listener);
        return g;
    }

    @Test public void theResultWinnerFollowsTheContractForEveryReason() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        assertEquals(Role.THIEF, g.winnerFor(ResultReason.ESCAPED));
        assertEquals(Role.POLICE, g.winnerFor(ResultReason.CAPTURED));
        assertEquals(Role.POLICE, g.winnerFor(ResultReason.TIMEOUT));
        assertNull("both left: nobody won", g.winnerFor(ResultReason.BOTH_LEFT));
        assertNull("server abort must not award a win", g.winnerFor(ResultReason.SERVER_ABORTED));

        // A departure always hands the win to the other side, whichever role leaves.
        for (ResultReason leaving : new ResultReason[]{
                ResultReason.QUIT, ResultReason.BACKGROUND, ResultReason.DISCONNECT}) {
            assertEquals("thief leaving", Role.POLICE, g.winnerFor(leaving));
            g.selectRole(Role.POLICE);
            assertEquals("police leaving", Role.THIEF, g.winnerFor(leaving));
            g.selectRole(Role.THIEF);
        }
    }

    @Test public void leavingARunningMatchSettlesItBeforeDroppingTheSession() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);
        g.setReady(true);
        g.advance(3.2f);
        assertEquals(RoomPhase.RUNNING, l.room.phase);

        g.leaveRoom();

        assertEquals("leaving mid-match must produce a result", 1, l.results);
        assertEquals(ResultReason.QUIT, l.result.reason);
        assertEquals("a quitting thief hands the win to the police", Role.POLICE, l.result.winner);
        assertNull("the session is dropped only after the result", l.session);
        assertEquals(ConnectionStates.CLOSED, l.state);
    }

    @Test public void theWinnerOfADepartureIsTheOtherSideWhicheverRoleLeaves() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);
        g.selectRole(Role.POLICE);
        g.setReady(true);
        g.advance(3.2f);

        g.leaveRoom();

        assertEquals(ResultReason.QUIT, l.result.reason);
        assertEquals("a quitting police officer hands the win to the thief", Role.THIEF, l.result.winner);
    }

    @Test public void leavingTheLobbyNeverProducesAResult() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);
        assertEquals(RoomPhase.LOBBY, l.room.phase);

        g.leaveRoom();

        assertEquals("no match, no result", 0, l.results);
        assertNull(l.session);
    }

    @Test public void connectReportsSessionThenRoom() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);
        assertEquals("CONNECTED", l.state);
        assertNotNull(l.session);
        assertNotNull(l.room);
        assertEquals(RoomPhase.LOBBY, l.room.phase);
        assertEquals(0, l.room.countdownRemainingMs);
        assertEquals(2, l.room.players.size());
        assertEquals(Role.THIEF, l.room.players.get(0).role);
        assertEquals(Role.POLICE, l.room.players.get(1).role);
    }

    @Test public void readyRunsCountdownBeforeTheFirstView() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        g.setReady(true);
        assertEquals(ControlStatus.State.SUCCEEDED, l.control.state);
        assertEquals(RoomPhase.COUNTDOWN, g.phase());
        assertTrue(l.room.countdownRemainingMs > 0);
        assertNull("no match view may be delivered during the countdown", l.view);

        g.advance(1.0f);
        assertEquals(RoomPhase.COUNTDOWN, g.phase());
        assertNull(l.view);

        g.advance(2.5f);
        assertEquals(RoomPhase.RUNNING, g.phase());
        assertNotNull(l.view);
        assertEquals(5400, l.view.remainingTicks);
    }

    @Test public void unreadyCancelsTheCountdownAndRestoresTheLobby() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        g.setReady(true);
        g.advance(1.0f);
        g.setReady(false);
        assertEquals(RoomPhase.LOBBY, g.phase());
        assertEquals(0, l.room.countdownRemainingMs);
        assertFalse(l.room.players.get(0).ready);
    }

    @Test public void backgroundClearsInputAndStopsFixture() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        g.setReady(true);
        g.advance(3.2f);
        g.submitInput(new InputFrame(1, 0, true, true));
        g.advance(0.1f);
        double x = l.view.self.x;

        g.background();
        g.advance(1);

        assertNull(l.session);
        // Contract §12.1: backgrounding is best effort; the request may end as CANCELLED.
        assertEquals(ControlStatus.State.CANCELLED, l.control.state);
        assertEquals("CLOSED", l.state);
        assertEquals(0, g.lastInput().moveX, 0);
        assertFalse(g.lastInput().interactHeld);
        assertEquals(x, l.view.self.x, 0);
    }

    @Test public void onlyLeaveRoomAcknowledgesLeavingAndOnlyForTheAskingPlayer() {
        DataFiles d = DataFiles.bundled();

        // leaveRoom(): PENDING then a terminal ACK, exactly like the real gateway.
        Listener l1 = new Listener();
        FakeGateway g1 = fixture(d, l1);
        g1.setReady(true);
        g1.advance(3.2f);
        l1.controlLog.clear();   // only the leaving sequence matters here
        g1.leaveRoom();
        assertEquals(java.util.Arrays.asList(
                "LEAVE_ROOM:PENDING", "LEAVE_ROOM:SUCCEEDED"), l1.controlLog);

        // background(): best effort, so the request ends as CANCELLED rather than SUCCEEDED.
        Listener l2 = new Listener();
        FakeGateway g2 = fixture(d, l2);
        g2.background();
        assertEquals(java.util.Collections.singletonList("LEAVE_ROOM:CANCELLED"), l2.controlLog);

        // close(): used by dispose(), mode switches and error paths. It must not invent a control
        // event the player never triggered.
        Listener l3 = new Listener();
        FakeGateway g3 = fixture(d, l3);
        g3.close();
        assertTrue("close must not fabricate a control event, saw " + l3.controlLog, l3.controlLog.isEmpty());
    }

    @Test public void policeFixtureDoesNotExposeThiefPrivateState() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        g.selectRole(Role.POLICE);
        g.setReady(true);
        g.advance(3.2f);

        assertNotNull(l.view);
        assertNull("police must not receive thief private state", l.view.thief);
        assertNull("fixture does not model police visibility", l.view.opponent);
    }

    @Test public void thiefFixtureReportsOnlyGroundMaterialsAsGroundTruth() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        g.setReady(true);
        g.advance(3.2f);

        assertNotNull(l.view.thief);
        assertEquals(d.map.materials.size(), l.view.thief.groundMaterials.size());
        assertEquals(3, l.view.originStatus.size());
        for (OriginStatus s : l.view.originStatus) assertFalse(s.everPicked);
    }

    @Test public void opponentDisappearsWhenItLeavesTheThiefViewRectangle() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        g.setReady(true);
        g.advance(3.2f);
        boolean sawOpponent = l.view.opponent != null;

        // Walk into the far corner: the fixture keeps the police near the map centre, so the
        // opponent must leave the view rectangle and be delivered as null.
        g.submitInput(new InputFrame(1, 1, false, false));
        for (int i = 0; i < 120; i++) g.advance(0.1f);

        assertTrue("the fixture should have shown the police at least once", sawOpponent);
        assertNull("outside the rectangle the opponent must be null, not a stale position", l.view.opponent);
    }

    private static void advanceFor(FakeGateway g, double seconds) {
        int steps = (int) Math.round(seconds * 10);
        for (int i = 0; i < steps; i++) g.advance(0.1f);
    }

    @Test public void policeWinScriptMarksThenCaptures() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);
        g.useScenario(FakeGateway.Scenario.POLICE_WIN);
        g.setReady(true);
        g.advance(3.2f);
        assertEquals("no marks at the start", 0, l.view.marks);

        advanceFor(g, 2.5);
        assertEquals(1, l.view.marks);
        assertEquals(Protection.INVISIBLE, l.view.thief.protection);
        assertTrue("invisible remaining must count down", l.view.thief.protectionRemainingTicks > 0);

        advanceFor(g, 1.2);
        assertEquals("after 1.5 s the thief becomes immune", Protection.IMMUNE, l.view.thief.protection);
        assertTrue(l.view.thief.protectionRemainingTicks > 0);

        advanceFor(g, 1.0);
        assertEquals(Protection.NORMAL, l.view.thief.protection);
        assertEquals(0, l.view.thief.protectionRemainingTicks);
        assertEquals("marks do not accumulate during protection", 1, l.view.marks);

        advanceFor(g, 1.5);
        assertEquals(2, l.view.marks);

        advanceFor(g, 5.0);
        assertEquals(1, l.results);
        assertEquals(ResultReason.CAPTURED, l.result.reason);
        assertEquals(Role.POLICE, l.result.winner);
        assertEquals("the third mark is the one that ends the match", 3, l.result.marks);
    }

    @Test public void thiefWinScriptDeliversThenEscapes() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);
        g.useScenario(FakeGateway.Scenario.THIEF_WIN);
        g.setReady(true);
        g.advance(3.2f);

        advanceFor(g, 2.5);
        assertNotNull("a pickup target must be offered", l.view.thief.interaction.active);
        assertEquals(InteractionType.PICKUP, l.view.thief.interaction.active.type);
        assertTrue("progress accumulates", l.view.thief.interaction.progressTicks > 0);
        assertNull("not carried yet", l.view.thief.carriedMaterialId);

        advanceFor(g, 1.0);
        assertEquals("M1", l.view.thief.carriedMaterialId);
        assertEquals("a carried material leaves the ground", 2, l.view.thief.groundMaterials.size());

        advanceFor(g, 1.0);
        assertEquals(InteractionType.DELIVER, l.view.thief.interaction.active.type);

        // Land mid-segment: accumulated 0.1 s steps are not exact, so never assert on a boundary.
        advanceFor(g, 2.0);
        assertEquals(1, l.view.deliveredCount);
        assertFalse("exits stay shut before the third delivery", l.view.exitsOpen);
        assertEquals(InteractionType.DELIVER, l.view.thief.interaction.active.type);

        advanceFor(g, 1.0);
        assertEquals(2, l.view.deliveredCount);

        advanceFor(g, 2.0);
        assertEquals(3, l.view.deliveredCount);
        assertTrue("the third delivery opens the exits", l.view.exitsOpen);
        assertEquals("nothing left on the ground", 0, l.view.thief.groundMaterials.size());
        assertEquals(InteractionType.ESCAPE, l.view.thief.interaction.active.type);

        advanceFor(g, 2.0);
        assertEquals(1, l.results);
        assertEquals(ResultReason.ESCAPED, l.result.reason);
        assertEquals(Role.THIEF, l.result.winner);
    }

    @Test public void invisibleThiefIsHiddenFromThePoliceRole() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);
        g.useScenario(FakeGateway.Scenario.POLICE_WIN);
        g.selectRole(Role.POLICE);
        g.setReady(true);
        g.advance(3.2f);

        advanceFor(g, 2.5);
        assertEquals(Protection.INVISIBLE, g.protection());
        assertNull("an invisible thief must not be visible", l.view.opponent);

        advanceFor(g, 1.2);
        assertEquals(Protection.IMMUNE, g.protection());
        assertNotNull("the immune thief is visible but cannot be marked again", l.view.opponent);
        assertNull("police never receives thief private state", l.view.thief);
    }

    @Test public void matchEndsExactlyOnceAndReturnsToTheLobby() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        g.setReady(true);
        g.advance(3.2f);
        for (int i = 0; i < 200 && g.phase() == RoomPhase.RUNNING; i++) g.advance(0.1f);

        assertEquals(RoomPhase.FINISHED, g.phase());
        assertEquals(1, l.results);
        assertNotNull(l.result);
        assertEquals(ResultReason.TIMEOUT, l.result.reason);
        assertEquals(Role.POLICE, l.result.winner);
        assertEquals(RoomPhase.FINISHED, l.room.phase);

        g.returnToLobby();
        assertEquals(RoomPhase.LOBBY, g.phase());
        assertEquals(0, l.room.countdownRemainingMs);
        assertNull("a new lobby must not carry the previous match id", l.room.matchId);
    }

    @Test public void connectFailureIsReportedWithoutASession() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = new FakeGateway();
        g.configure(d);
        g.useScenario(FakeGateway.Scenario.CONNECT_FAIL);
        g.connect(new ConnectionOptions("offline", 8080, "DEMO", "test", d.mapHash, d.configHash), l);

        assertEquals(ConnectionStates.DISCONNECTED, l.state);
        assertNull(l.session);
        assertNotNull(l.error);
        assertEquals("CONNECT_TIMEOUT", l.error.code);
        assertNull(l.room);
    }

    @Test public void roomFullIsReportedAfterTheSession() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = new FakeGateway();
        g.configure(d);
        g.useScenario(FakeGateway.Scenario.ROOM_FULL);
        g.connect(new ConnectionOptions("offline", 8080, "DEMO", "test", d.mapHash, d.configHash), l);

        assertNotNull(l.session);
        assertNotNull(l.error);
        assertEquals("ROOM_FULL", l.error.code);
        assertFalse(l.error.recoverable);
        assertNull(l.room);
    }

    @Test public void roleConflictAndInvalidPhaseUseContractErrorCodes() {
        DataFiles d = DataFiles.bundled();
        Listener l = new Listener();
        FakeGateway g = fixture(d, l);

        g.useScenario(FakeGateway.Scenario.ROLE_TAKEN);
        g.selectRole(Role.POLICE);
        assertEquals(ControlStatus.State.FAILED, l.control.state);
        assertEquals("ROLE_TAKEN", l.control.errorCode);
        assertEquals("a rejected role change must not alter the role", Role.THIEF, g.selfRole());

        g.useScenario(FakeGateway.Scenario.INVALID_PHASE);
        g.setReady(true);
        assertEquals(ControlStatus.State.FAILED, l.control.state);
        assertEquals("INVALID_PHASE", l.control.errorCode);
        assertEquals(RoomPhase.LOBBY, g.phase());
    }
}
