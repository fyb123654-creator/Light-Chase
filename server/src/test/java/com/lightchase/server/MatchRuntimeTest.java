package com.lightchase.server;

import com.lightchase.contracts.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Clock/inbox tests use a recording engine; no game rules are duplicated here. */
public class MatchRuntimeTest {
    private final RecordingEngine engine = new RecordingEngine();
    private final MatchRuntime runtime = new MatchRuntime(engine, new MatchInit("match", "p", "t"), 30, 0);
    private void pump(long now) { runtime.pump(now, step -> {}); }

    static final class RecordingEngine implements MatchEngine {
        final List<StepInput> steps = new ArrayList<>();
        int aborts;
        MatchResult result;
        public StepResult step(StepInput input) {
            steps.add(input);
            return new StepResult(input.tick, false, result != null, result);
        }
        public MatchResult result() { return result; }
        public MatchResult abort() {
            aborts++;
            result = new MatchResult("match:result", "match", null, ResultReason.SERVER_ABORTED,
                    null, steps.size(), 0, 0, steps.size());
            return result;
        }
        public PlayerView viewFor(String id) {
            return new PlayerView("match", steps.size(), 0, 0, id.equals("p") ? Role.POLICE : Role.THIEF,
                    5400 - steps.size(), 0, 0, false, new ActorPose(id, 7.5, 4.5, 0), null,
                    Arrays.asList(new OriginStatus("M1", false)), null);
        }
    }

    @Test public void thirtyIntegerStepsConsumeExactlyOneSecondWithout33msDrift() {
        pump(33); assertTrue(engine.steps.isEmpty());
        pump(34); assertEquals(1, engine.steps.size());
        for (int ms = 40; ms <= 1000; ms += 10) pump(ms);
        assertEquals(30, engine.steps.size());
        for (int i = 0; i < 30; i++) assertEquals(i + 1, engine.steps.get(i).tick);
        pump(1033); assertEquals(30, engine.steps.size());
        pump(1034); assertEquals(31, engine.steps.size());
    }

    @Test public void eachPumpCatchesUpAtMostFiveStepsAndRetainsAllDebt() {
        pump(1000); assertEquals(5, engine.steps.size());
        pump(1000); assertEquals(10, engine.steps.size());
        for (int i = 0; i < 4; i++) pump(1000);
        assertEquals(30, engine.steps.size()); pump(1000); assertEquals(30, engine.steps.size());
    }

    @Test public void debtOverOneSecondIsReportedBeforeStepping() {
        assertThrows(IllegalStateException.class, () -> pump(1001));
        assertTrue(engine.steps.isEmpty());
        assertEquals(ResultReason.SERVER_ABORTED, runtime.abort().reason);
    }

    @Test public void oldDebtAndNewElapsedTimeAreBothCountedForLag() {
        pump(1000); assertEquals(5, engine.steps.size()); // 25 ticks still owed.
        assertThrows(IllegalStateException.class, () -> pump(1200)); // 31 ticks owed.
        assertEquals(5, engine.steps.size());
    }

    @Test public void fifoBatchPreservesPressReleaseAndDropAcrossBothPlayers() {
        assertNull(runtime.input("t", 1, 0, 0, true, null, 0));
        assertNull(runtime.input("p", 1, 1, 0, false, null, 0));
        assertNull(runtime.input("t", 2, 0, 0, false, null, 1));
        assertNull(runtime.input("t", 3, 0, 0, true, 1L, 2));
        assertEquals(0, runtime.viewFor("t", 2).ackInputSeq);
        pump(34);
        List<PlayerCommand> commands = engine.steps.get(0).commands;
        assertEquals(Arrays.asList("t", "p", "t", "t"), Arrays.asList(commands.get(0).playerId,
                commands.get(1).playerId, commands.get(2).playerId, commands.get(3).playerId));
        assertTrue(commands.get(0).interactHeld); assertFalse(commands.get(2).interactHeld);
        assertTrue(commands.get(3).interactHeld); assertEquals(Long.valueOf(1), commands.get(3).dropId);
        assertEquals(3, runtime.viewFor("t", 34).ackInputSeq);
        assertEquals(1, runtime.viewFor("p", 34).ackInputSeq);
    }

    @Test public void callbacksCanOnlyQueueCommandsForTheNextBoundary() {
        runtime.input("t", 1, 0, 0, true, null, 0);
        runtime.pump(100, step -> {
            if (step.tick == 1) runtime.input("t", 2, 0, 0, false, null, 100);
        });
        assertEquals(3, engine.steps.size());
        assertEquals(1, engine.steps.get(0).commands.get(0).inputSeq);
        assertEquals(2, engine.steps.get(1).commands.get(0).inputSeq);
        assertTrue(engine.steps.get(2).commands.isEmpty());
    }

    @Test public void staleInputIsDroppedButNewContinuousInputWithOldDropIsKept() {
        runtime.input("t", 1, 1, 0, true, 10L, 0);
        assertEquals("STALE_INPUT", runtime.input("t", 1, -1, 0, false, 20L, 10));
        assertNull(runtime.input("t", 2, 0, 0, false, 9L, 20));
        assertNull(runtime.input("t", 3, 0, 1, false, 10L, 21));
        assertNull(runtime.input("t", 4, 0, 0, true, 11L, 22));
        pump(34);
        List<PlayerCommand> batch = engine.steps.get(0).commands;
        assertEquals(4, batch.size()); assertEquals(Long.valueOf(10), batch.get(0).dropId);
        assertNull(batch.get(1).dropId); assertNull(batch.get(2).dropId);
        assertEquals(Long.valueOf(11), batch.get(3).dropId);
    }

    @Test public void invalidCommandsDoNotConsumeSequenceOrDropIds() {
        assertEquals("INVALID_INPUT", runtime.input("t", 9, 2, 0, true, 100L, 0));
        assertEquals("INVALID_INPUT", runtime.input("t", 9, Double.NaN, 0, true, 100L, 0));
        assertEquals("INVALID_INPUT", runtime.input("t", 0, 0, 0, true, null, 0));
        assertEquals("INVALID_INPUT", runtime.input("t", 1, 0, 0, true, 0L, 0));
        assertEquals("INVALID_ACTION", runtime.input("p", 1, 0, 0, true, null, 0));
        assertEquals("INVALID_ACTION", runtime.input("p", 1, 0, 0, false, 1L, 0));
        assertNull(runtime.input("t", 1, 0, 0, false, 1L, 0));
        assertNull(runtime.input("p", 1, 0, 0, false, null, 0));
        pump(34); assertEquals(2, engine.steps.get(0).commands.size());
    }

    @Test public void leaseExpiresAt250msWithSameSeqAndNoDropExactlyOnce() {
        runtime.input("t", 7, 1, 0, true, 1L, 0);
        pump(34);
        pump(249); pump(249); // Clear due steps without touching the input lease.
        for (StepInput step : engine.steps) {
            for (PlayerCommand command : step.commands) assertTrue(command.interactHeld);
        }
        pump(250); // No step is due until 267ms; expiration is inserted at that boundary.
        pump(267);
        PlayerCommand released = engine.steps.get(engine.steps.size() - 1).commands.get(0);
        assertEquals(7, released.inputSeq); assertEquals(0, released.moveX, 0);
        assertFalse(released.interactHeld); assertNull(released.dropId);
        assertEquals(7, runtime.viewFor("t", 267).ackInputSeq);
        pump(300); assertTrue(engine.steps.get(engine.steps.size() - 1).commands.isEmpty());
    }

    @Test public void invalidAndStaleInputCannotRenewTheLease() {
        runtime.input("t", 1, 1, 0, true, null, 0); pump(34);
        assertEquals("INVALID_INPUT", runtime.input("t", 999, 2, 0, false, null, 249));
        assertEquals("STALE_INPUT", runtime.input("t", 1, 0, 0, false, null, 249));
        pump(250); pump(267);
        List<PlayerCommand> releases = new ArrayList<>();
        for (StepInput step : engine.steps) for (PlayerCommand command : step.commands)
            if (!command.interactHeld) releases.add(command);
        assertEquals(1, releases.size()); assertEquals(1, releases.get(0).inputSeq);
    }

    @Test public void departureBatchKeepsBothIdentitiesAndDeduplicatesTheSamePlayer() {
        runtime.input("t", 4, 1, 0, true, null, 0);
        runtime.depart("t", DepartureReason.QUIT); runtime.depart("t", DepartureReason.DISCONNECT);
        runtime.depart("p", DepartureReason.BACKGROUND);
        assertEquals("INVALID_PHASE", runtime.input("t", 5, 0, 0, false, null, 1));
        pump(34);
        List<Departure> departures = engine.steps.get(0).departures;
        assertEquals(2, departures.size()); assertEquals("t", departures.get(0).playerId);
        assertEquals(DepartureReason.QUIT, departures.get(0).reason);
        assertEquals(DepartureReason.BACKGROUND, departures.get(1).reason);
        assertEquals(3, engine.steps.get(0).commands.size());
    }

    @Test public void confirmedEndStopsCatchUpAndAbortRemainsIdempotent() {
        MatchResult ended = runtime.abort(); assertSame(ended, runtime.abort());
        pump(100); assertTrue(engine.steps.isEmpty()); assertEquals(1, engine.aborts);
        assertEquals("INVALID_PHASE", runtime.input("t", 1, 0, 0, false, null, 0));
    }

    @Test public void metadataIsAddedWithoutChangingSemanticView() {
        runtime.input("p", 12, 1, 0, false, null, 0); pump(34);
        PlayerView view = runtime.viewFor("p", 1234);
        assertEquals(1234, view.serverTimeMs); assertEquals(12, view.ackInputSeq);
        assertEquals(1, view.tick); assertEquals(5399, view.remainingTicks);
        assertEquals("p", view.self.playerId); assertEquals(7.5, view.self.x, 0);
        assertNull(view.thief); assertNull(view.opponent); assertEquals("M1", view.originStatus.get(0).materialId);
    }
}
