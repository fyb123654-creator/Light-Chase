package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.DepartureReason;
import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.PlayerCommand;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.Protection;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.StepInput;
import com.lightchase.contracts.StepResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class OutcomeTest {
    private static MatchEngine engine(RuleConfig rules) {
        return new V1EngineFactory().create(
                TestMaps.open(10, 10, 5.5, 5.5, 7.5, 5.5), rules, TestMaps.init());
    }

    private static MatchEngine threeTaskEngine(int durationTicks) {
        return new V1EngineFactory().create(TestScripts.threeTaskMap(),
                TestMaps.rules(durationTicks), TestMaps.init());
    }

    private static StepInput empty(long tick) {
        return new StepInput(tick, Collections.emptyList(), Collections.emptyList());
    }

    private static StepInput departures(long tick, Departure... departures) {
        return new StepInput(tick, Collections.emptyList(), Arrays.asList(departures));
    }

    private static StepInput commandAndDepartures(long tick, Departure... departures) {
        return new StepInput(tick,
                Arrays.asList(new PlayerCommand("t", tick, 0, 0, true, null)),
                Arrays.asList(departures));
    }

    private static void assertEndState(MatchEngine engine, StepResult step, Role winner,
                                       ResultReason reason, int marks, long finalTick,
                                       String departedPlayerId) {
        assertTrue(step.ended);
        assertSame(engine.result(), step.result);
        assertEquals("m", step.result.matchId);
        assertEquals("m:result", step.result.resultId);
        assertEquals(winner, step.result.winner);
        assertEquals(reason, step.result.reason);
        assertEquals(marks, step.result.marks);
        assertEquals(finalTick, step.result.elapsedTicks);
        assertEquals(finalTick, step.result.finalTick);
        assertEquals(departedPlayerId, step.result.departedPlayerId);

        StepResult again = engine.step(empty(finalTick + 1));
        assertTrue(again.ended);
        assertSame(step.result, again.result);
        assertEquals(finalTick, again.tick);
        assertSame(step.result, engine.result());
        assertSame(step.result, engine.abort());
    }

    @Test
    public void timeoutWinsOverSameStepThirdMark() {
        MatchEngine engine = engine(TestMaps.rules(3, 1, 0, 0));
        engine.step(empty(1));
        engine.step(empty(2));
        StepResult step = engine.step(empty(3));

        assertEndState(engine, step, Role.POLICE, ResultReason.TIMEOUT, 3, 3L, null);
    }

    @Test
    public void captureWinsOverSameStepPoliceDeparture() {
        MatchEngine engine = engine(TestMaps.rules(100, 1, 0, 0));
        engine.step(empty(1));
        engine.step(empty(2));
        StepResult step = engine.step(departures(3,
                new Departure("p", DepartureReason.QUIT)));

        assertEndState(engine, step, Role.POLICE, ResultReason.CAPTURED, 3, 3L, null);
    }

    @Test
    public void captureWinsOverSameStepBothDepartures() {
        MatchEngine engine = engine(TestMaps.rules(100, 1, 0, 0));
        engine.step(empty(1));
        engine.step(empty(2));
        StepResult step = engine.step(departures(3,
                new Departure("p", DepartureReason.QUIT),
                new Departure("t", DepartureReason.DISCONNECT)));

        assertEndState(engine, step, Role.POLICE, ResultReason.CAPTURED, 3, 3L, null);
    }

    @Test
    public void captureWinsOverSameStepEscapeAt29() {
        MatchEngine engine = new V1EngineFactory().create(
                TestScripts.threeTaskMapAt(2.5, 5.5),
                TestMaps.rules(5400, 1, 0, 0),
                TestMaps.init());
        engine.step(empty(1)); // mark 1
        engine.step(empty(2)); // mark 2
        engine.step(TestScripts.policeMove(3, 0, 1)); // turn north, beam off
        engine.step(TestScripts.policeMove(4, 0, 0)); // stop

        long tick = TestScripts.deliverAllFrom(engine, 5);
        assertTrue(engine.viewFor("t").exitsOpen);
        tick = TestScripts.hold(engine, tick, 29); // escape progress 29

        StepInput finalStep = new StepInput(tick, Arrays.asList(
                new PlayerCommand("t", tick, 0, 0, true, null),
                new PlayerCommand("p", tick, 1, 0, false, null)),
                Collections.emptyList());
        StepResult step = engine.step(finalStep);

        assertEndState(engine, step, Role.POLICE, ResultReason.CAPTURED, 3, 220L, null);
        assertEquals(3, step.result.deliveredCount);
    }

    @Test
    public void timeoutWinsOverSameStepEscape() {        MatchEngine engine = threeTaskEngine(216);
        long tick = TestScripts.deliverAll(engine);
        assertTrue(engine.viewFor("t").exitsOpen);
        tick = TestScripts.hold(engine, tick, 29);
        StepResult step = engine.step(TestScripts.thiefCommand(tick, true, null));

        assertEndState(engine, step, Role.POLICE, ResultReason.TIMEOUT, 0, 216L, null);
        assertEquals(3, step.result.deliveredCount);
    }

    @Test
    public void escapeWinsOverSameStepPoliceDeparture() {
        MatchEngine engine = threeTaskEngine(5400);
        long tick = TestScripts.deliverAll(engine);
        tick = TestScripts.hold(engine, tick, 29);
        StepResult step = engine.step(commandAndDepartures(tick,
                new Departure("p", DepartureReason.QUIT)));

        assertEndState(engine, step, Role.THIEF, ResultReason.ESCAPED, 0, 216L, null);
        assertEquals(3, step.result.deliveredCount);
    }

    @Test
    public void escapeWinsOverSameStepBothDepartures() {
        MatchEngine engine = threeTaskEngine(5400);
        long tick = TestScripts.deliverAll(engine);
        tick = TestScripts.hold(engine, tick, 29);
        StepResult step = engine.step(commandAndDepartures(tick,
                new Departure("p", DepartureReason.QUIT),
                new Departure("t", DepartureReason.DISCONNECT)));

        assertEndState(engine, step, Role.THIEF, ResultReason.ESCAPED, 0, 216L, null);
        assertEquals(3, step.result.deliveredCount);
    }

    @Test
    public void captureWinsOverSameStepThiefDeparture() {
        MatchEngine engine = engine(TestMaps.rules(100, 1, 0, 0));
        engine.step(empty(1));
        engine.step(empty(2));
        StepResult step = engine.step(departures(3,
                new Departure("t", DepartureReason.QUIT)));

        assertEndState(engine, step, Role.POLICE, ResultReason.CAPTURED, 3, 3L, null);
    }

    @Test
    public void timeoutWinsOverSameStepBothDepartures() {
        MatchEngine engine = engine(TestMaps.rules(1));
        StepResult step = engine.step(departures(1,
                new Departure("p", DepartureReason.QUIT),
                new Departure("t", DepartureReason.DISCONNECT)));

        assertEndState(engine, step, Role.POLICE, ResultReason.TIMEOUT, 0, 1L, null);
    }

    @Test
    public void singleDepartureKeepsItsReason() {
        for (DepartureReason reason : DepartureReason.values()) {
            MatchEngine engine = engine(TestMaps.rules(100));
            StepResult step = engine.step(departures(1, new Departure("p", reason)));
            assertTrue(step.ended);
            assertEquals(Role.THIEF, step.result.winner);
            assertEquals(ResultReason.valueOf(reason.name()), step.result.reason);
            assertEquals("p", step.result.departedPlayerId);
            assertEquals(1L, step.result.finalTick);
        }
    }

    @Test
    public void mixedBothDeparturesAreBothLeft() {
        MatchEngine engine = engine(TestMaps.rules(100));
        StepResult step = engine.step(departures(1,
                new Departure("p", DepartureReason.QUIT),
                new Departure("t", DepartureReason.DISCONNECT)));

        assertEndState(engine, step, null, ResultReason.BOTH_LEFT, 0, 1L, null);
    }

    @Test
    public void abortBeforeEndIsServerAbortedAndIdempotent() {
        MatchEngine engine = engine(TestMaps.rules(100));
        MatchResult aborted = engine.abort();
        assertEquals(ResultReason.SERVER_ABORTED, aborted.reason);
        assertEquals("m", aborted.matchId);
        assertEquals("m:result", aborted.resultId);
        assertNull(aborted.winner);
        assertEquals(0L, aborted.elapsedTicks);
        assertEquals(0L, aborted.finalTick);
        assertEquals(0, aborted.marks);
        assertEquals(0, aborted.deliveredCount);

        StepResult after = engine.step(empty(1));
        assertTrue(after.ended);
        assertSame(aborted, after.result);
        assertEquals(0L, after.tick);
        assertSame(aborted, engine.result());
        assertSame(aborted, engine.abort());
    }

    @Test
    public void newEngineStartsWithFreshState() {
        MatchEngine first = threeTaskEngine(100);
        first.step(departures(1, new Departure("p", DepartureReason.QUIT)));
        assertNotNull(first.result());

        MatchEngine second = threeTaskEngine(100);
        assertNull(second.result());
        PlayerView thief = second.viewFor("t");
        assertEquals(0L, thief.tick);
        assertEquals(0, thief.marks);
        assertEquals(0, thief.deliveredCount);
        assertNull(thief.thief.carriedMaterialId);
        assertEquals(3, thief.thief.groundMaterials.size());
        assertTrue(thief.thief.deliveredMaterialIds.isEmpty());
        assertNull(thief.thief.interaction.active);
        assertEquals(Protection.NORMAL, thief.thief.protection);
        assertEquals(0, thief.thief.protectionRemainingTicks);
        assertFalse(thief.exitsOpen);
    }
}
