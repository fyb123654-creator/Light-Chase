package com.lightchase.client;

import com.lightchase.contracts.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MatchQuitTest {

    private static MatchResult result(ResultReason reason, Role winner) {
        return new MatchResult("r1", "m1", winner, reason, null, 100, 0, 0, 100);
    }

    @Test public void aSecondRequestIsIgnoredWhileOneIsRunning() {
        MatchQuit q = new MatchQuit();
        assertTrue(q.request(1000));
        assertFalse("must not start twice", q.request(1100));
        assertTrue(q.active());
    }

    @Test public void theGatewaySpontaneousPendingDoesNotCancelTheWait() {
        MatchQuit q = new MatchQuit();
        q.request(1000);
        // The real gateway emits this the instant leaveRoom() is called.
        q.onControl("LEAVE_ROOM", ControlStatus.State.PENDING);
        assertTrue("PENDING is not an outcome", q.active());
        q.onControl("LEAVE_ROOM", ControlStatus.State.WAITING);
        assertTrue("WAITING is not an outcome either", q.active());
        q.onControl("LEAVE_ROOM", ControlStatus.State.SUCCEEDED);
        assertFalse("a terminal state ends the wait", q.active());
    }

    @Test public void otherActionsAndTimeoutsEndTheWaitOnlyWhenTerminal() {
        MatchQuit q = new MatchQuit();
        q.request(1000);
        q.onControl("SET_READY", ControlStatus.State.SUCCEEDED);
        assertTrue("an unrelated action must not end it", q.active());
        q.onControl("LEAVE_ROOM", ControlStatus.State.TIMED_OUT);
        assertFalse(q.active());
    }

    @Test public void theResultBecomesASentenceThatSurvivesTheSessionDrop() {
        MatchQuit q = new MatchQuit();
        q.request(1000);
        q.onResult(result(ResultReason.QUIT, Role.POLICE));

        assertFalse(q.active());
        assertEquals("You left the match (QUIT); the win went to POLICE", q.takeSummary());
        assertNull("the summary is handed out once", q.takeSummary());
    }

    @Test public void aResultNobodyWonIsReportedAsNobody() {
        MatchQuit q = new MatchQuit();
        q.request(1000);
        q.onResult(result(ResultReason.BOTH_LEFT, null));
        assertEquals("You left the match (BOTH_LEFT); the win went to nobody", q.takeSummary());
    }

    @Test public void aResultArrivingWithoutAQuitIsNotRemembered() {
        MatchQuit q = new MatchQuit();
        q.onResult(result(ResultReason.TIMEOUT, Role.POLICE));
        assertNull("a normal ending needs no leave summary", q.takeSummary());
    }

    @Test public void theWaitTimesOutAndSaysSoOnce() {
        MatchQuit q = new MatchQuit();
        q.request(1000);
        assertFalse("not yet", q.tick(1000 + MatchQuit.TIMEOUT_MS - 1));
        assertTrue("timed out", q.tick(1000 + MatchQuit.TIMEOUT_MS + 1));
        assertFalse("only one timeout is reported", q.tick(1000 + MatchQuit.TIMEOUT_MS + 2));
        assertFalse(q.active());
    }

    /**
     * The exact order the production gateway and server produced in the headless probe
     * (build/c-probe.log): PENDING, then GAME_OVER, then the ACK. Encoded here so a future change to
     * MatchQuit cannot quietly stop matching reality.
     */
    @Test public void replaysTheOrderTheProductionServerActuallyProduced() {
        MatchQuit q = new MatchQuit();
        long t0 = 5_000;

        assertTrue(q.request(t0));
        q.onControl("LEAVE_ROOM", ControlStatus.State.PENDING);   // same frame as the request
        assertTrue("still waiting right after the request", q.active());
        assertFalse("no timeout while the server settles", q.tick(t0 + 400));

        q.onResult(result(ResultReason.QUIT, Role.THIEF));          // GAME_OVER
        q.onControl("LEAVE_ROOM", ControlStatus.State.SUCCEEDED);   // ACK, after the result
        assertFalse(q.active());
        assertEquals("You left the match (QUIT); the win went to THIEF", q.takeSummary());
    }

    @Test public void resetEndsTheWaitButKeepsTheOutcome() {
        MatchQuit q = new MatchQuit();
        q.request(1000);
        q.onResult(result(ResultReason.QUIT, Role.THIEF));
        q.reset();
        assertFalse(q.active());
        assertEquals("the session drop must not eat the result the player is about to read",
                "You left the match (QUIT); the win went to THIEF", q.takeSummary());
        assertNull("and it is still handed out only once", q.takeSummary());
    }

    @Test public void aNewResultReplacesAPendingSummary() {
        MatchQuit q = new MatchQuit();
        q.request(1000);
        q.onResult(result(ResultReason.QUIT, Role.THIEF));
        q.request(2000);
        q.onResult(result(ResultReason.QUIT, Role.POLICE));
        assertEquals("You left the match (QUIT); the win went to POLICE", q.takeSummary());
    }
}
