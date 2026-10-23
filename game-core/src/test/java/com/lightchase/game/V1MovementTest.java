package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.DepartureReason;
import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.PlayerCommand;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.StepInput;
import com.lightchase.contracts.StepResult;
import com.lightchase.contracts.WallCell;
import com.lightchase.data.DataFiles;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class V1MovementTest {
    private static MatchEngine engine(MapDefinition map, RuleConfig rules) {
        return new V1EngineFactory().create(map, rules, TestMaps.init());
    }

    private static MatchEngine openEngine(int durationTicks) {
        return engine(TestMaps.open(10, 10, 5.5, 5.5, 8.5, 5.5), TestMaps.rules(durationTicks));
    }

    private static StepInput one(long tick, String player, double x, double y) {
        return commands(tick, new PlayerCommand(player, tick, x, y, false, null));
    }

    private static StepInput commands(long tick, PlayerCommand... commands) {
        return new StepInput(tick, Arrays.asList(commands), Collections.emptyList());
    }

    private static StepInput empty(long tick) {
        return new StepInput(tick, Collections.emptyList(), Collections.emptyList());
    }

    private static StepInput departures(long tick, Departure... departures) {
        return new StepInput(tick, Collections.emptyList(), Arrays.asList(departures));
    }

    private static PlayerCommand cmd(long tick, String player, double x, double y) {
        return new PlayerCommand(player, tick, x, y, false, null);
    }

    @Test
    public void tickZeroViewIsAvailable() {
        DataFiles data = DataFiles.bundled();
        MatchEngine engine = new V1EngineFactory().create(data.map, data.rules, new MatchInit("m", "p", "t"));

        PlayerView police = engine.viewFor("p");
        assertEquals(0L, police.tick);
        assertEquals(Role.POLICE, police.role);
        assertEquals(data.rules.durationTicks, police.remainingTicks);
        assertEquals(3, police.originStatus.size());
        assertNull(police.thief);
        assertNull(police.opponent);
        assertEquals(0L, police.serverTimeMs);
        assertEquals(0L, police.ackInputSeq);

        PlayerView thief = engine.viewFor("t");
        assertEquals(Role.THIEF, thief.role);
        assertNotNull(thief.thief);
        assertEquals(3, thief.thief.groundMaterials.size());
        assertTrue(thief.thief.deliveredMaterialIds.isEmpty());
        assertNull(thief.thief.carriedMaterialId);
        assertNull(thief.thief.interaction.candidate);
        assertNull(thief.thief.interaction.active);
        assertEquals(0, thief.thief.interaction.progressTicks);
        assertFalse(thief.thief.interaction.requiresRelease);
        assertEquals("NO_TARGET", thief.thief.interaction.blockedReason);
    }

    @Test
    public void diagonalIsNormalized() {
        MatchEngine engine = openEngine(100);
        double x = engine.viewFor("p").self.x;
        double y = engine.viewFor("p").self.y;
        engine.step(one(1, "p", 1, 1));
        assertEquals(0.1, Math.hypot(engine.viewFor("p").self.x - x, engine.viewFor("p").self.y - y), 1e-9);
    }

    @Test
    public void multipleCommandsMoveOnce() {
        MatchEngine engine = openEngine(100);
        double x = engine.viewFor("p").self.x;
        double y = engine.viewFor("p").self.y;
        engine.step(commands(1,
                cmd(1, "p", 1, 0),
                cmd(1, "p", 0, 1),
                cmd(1, "p", 1, 1)));
        assertEquals(0.1, Math.hypot(engine.viewFor("p").self.x - x, engine.viewFor("p").self.y - y), 1e-9);
    }

    @Test
    public void lastZeroCommandStopsMovement() {
        MatchEngine engine = openEngine(100);
        engine.step(one(1, "p", 1, 0));
        double x = engine.viewFor("p").self.x;
        engine.step(commands(2, cmd(2, "p", 1, 0), cmd(2, "p", 0, 0)));
        assertEquals(x, engine.viewFor("p").self.x, 0);
        engine.step(empty(3));
        assertEquals(x, engine.viewFor("p").self.x, 0);
    }

    @Test
    public void noCommandRetainsLastMovement() {
        MatchEngine engine = openEngine(100);
        double x = engine.viewFor("p").self.x;
        engine.step(one(1, "p", 1, 0));
        engine.step(empty(2));
        assertEquals(x + 0.2, engine.viewFor("p").self.x, 1e-9);
    }

    @Test
    public void sameInputSeqLeaseZeroStillStopsMovement() {
        MatchEngine engine = openEngine(100);
        engine.step(one(1, "p", 1, 0));
        double x = engine.viewFor("p").self.x;
        engine.step(new StepInput(2, Arrays.asList(
                new PlayerCommand("p", 1, 0, 0, false, null)),
                Collections.emptyList()));
        assertEquals(x, engine.viewFor("p").self.x, 0);
    }

    @Test
    public void newCommandsDoNotInheritPreviousMovementAttempt() {
        ActorState actor = new ActorState("p", Role.POLICE, new SpawnPose(5.5, 5.5, 0));
        actor.moveX = 1;
        Map<String, ActorState> actors = new LinkedHashMap<>();
        actors.put("p", actor);

        InputMerger.Plan plan = InputMerger.merge(new StepInput(2, Arrays.asList(
                new PlayerCommand("p", 2, 0, 0, true, null)),
                Collections.emptyList()), actors);
        InputMerger.PlayerStep step = plan.forPlayer("p");

        assertEquals(0, step.moveX, 0);
        assertEquals(0, step.moveY, 0);
        assertFalse(step.anyNonZeroMove);
        assertTrue(step.finalHeld);
        assertTrue(step.pressEdge);
        assertFalse(step.releaseEdge);
    }

    @Test
    public void headingFollowsMovementAndStaysWhenStopped() {
        MatchEngine engine = openEngine(100);
        engine.step(one(1, "p", 0, 1));
        assertEquals(Math.PI / 2, engine.viewFor("p").self.headingRad, 1e-9);
        engine.step(empty(2));
        assertEquals(Math.PI / 2, engine.viewFor("p").self.headingRad, 1e-9);
        engine.step(one(3, "p", 0, 0));
        assertEquals(Math.PI / 2, engine.viewFor("p").self.headingRad, 1e-9);
    }

    @Test
    public void wallStopsXAndAllowsYSlide() {
        // Start tangent to the wall at x=6 so the first X step is actually blocked.
        MapDefinition map = TestMaps.withWalls(10, 10, 5.75, 5.5, 8.5, 5.5,
                Arrays.asList(new WallCell(6, 5)));
        MatchEngine engine = engine(map, TestMaps.rules(100));
        double lastY = engine.viewFor("p").self.y;
        for (int tick = 1; tick <= 5; tick++) {
            engine.step(one(tick, "p", 1, 1));
            PlayerView view = engine.viewFor("p");
            assertEquals(5.75, view.self.x, 1e-9);
            assertTrue(view.self.y > lastY);
            lastY = view.self.y;
        }
        assertTrue(lastY > 5.8);
    }

    @Test
    public void blockedMovementStillUpdatesHeading() {
        Map<Role, SpawnPose> spawns = new LinkedHashMap<>();
        spawns.put(Role.POLICE, new SpawnPose(5.75, 5.5, Math.PI));
        spawns.put(Role.THIEF, new SpawnPose(8.5, 5.5, 0));
        MapDefinition map = new MapDefinition("test", 1, 10, 10,
                Arrays.asList(new WallCell(6, 5)), spawns,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        MatchEngine engine = new V1EngineFactory().create(map, TestMaps.rules(100), TestMaps.init());
        engine.step(one(1, "p", 1, 0));
        PlayerView view = engine.viewFor("p");
        assertEquals(5.75, view.self.x, 1e-9);
        assertEquals(0.0, view.self.headingRad, 1e-9);
    }

    @Test
    public void boundaryStopsPlayer() {
        MatchEngine engine = engine(TestMaps.open(10, 10, 1.5, 5.5, 8.5, 5.5), TestMaps.rules(100));
        for (int tick = 1; tick <= 30; tick++) {
            engine.step(one(tick, "p", -1, 0));
        }
        assertTrue(engine.viewFor("p").self.x >= 0.25 - 1e-9);
    }

    @Test
    public void timeoutAtDuration() {
        MatchEngine engine = openEngine(5);
        for (int tick = 1; tick <= 5; tick++) {
            engine.step(empty(tick));
        }
        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.TIMEOUT, result.reason);
        assertEquals(Role.POLICE, result.winner);
        assertEquals(5, result.elapsedTicks);
        assertEquals(5L, result.finalTick);
        assertEquals(0, engine.viewFor("p").remainingTicks);
    }

    @Test
    public void timeoutBeatsSameStepDeparture() {
        MatchEngine engine = openEngine(3);
        engine.step(empty(1));
        engine.step(empty(2));
        StepResult result = engine.step(departures(3, new Departure("p", DepartureReason.QUIT)));
        assertTrue(result.ended);
        assertEquals(ResultReason.TIMEOUT, result.result.reason);
        assertEquals(Role.POLICE, result.result.winner);
        assertNull(result.result.departedPlayerId);
    }

    @Test
    public void singleDepartureLoses() {
        MatchEngine engine = openEngine(100);
        StepResult step = engine.step(departures(1, new Departure("p", DepartureReason.QUIT)));
        assertTrue(step.ended);
        assertEquals(Role.THIEF, step.result.winner);
        assertEquals(ResultReason.QUIT, step.result.reason);
        assertEquals("p", step.result.departedPlayerId);
        assertEquals(1, step.result.elapsedTicks);
        assertEquals("m:result", step.result.resultId);
    }

    @Test
    public void bothDeparturesAreBothLeft() {
        MatchEngine engine = openEngine(100);
        StepResult step = engine.step(departures(1,
                new Departure("p", DepartureReason.QUIT),
                new Departure("t", DepartureReason.DISCONNECT)));
        assertTrue(step.ended);
        assertNull(step.result.winner);
        assertEquals(ResultReason.BOTH_LEFT, step.result.reason);
        assertNull(step.result.departedPlayerId);
    }

    @Test
    public void duplicateDepartureIsDeduped() {
        MatchEngine engine = openEngine(100);
        StepResult step = engine.step(departures(1,
                new Departure("p", DepartureReason.QUIT),
                new Departure("p", DepartureReason.QUIT)));
        assertEquals(ResultReason.QUIT, step.result.reason);
        assertEquals(Role.THIEF, step.result.winner);
    }

    @Test
    public void endedStepIsIdempotent() {
        MatchEngine engine = openEngine(100);
        StepResult first = engine.step(departures(1, new Departure("p", DepartureReason.QUIT)));
        MatchResult result = engine.result();
        StepResult second = engine.step(empty(2));
        assertTrue(second.ended);
        assertSame(result, second.result);
        assertEquals(1L, second.tick);
        assertSame(result, engine.abort());
    }

    @Test
    public void abortIsFinal() {
        MatchEngine engine = openEngine(100);
        MatchResult aborted = engine.abort();
        assertEquals(ResultReason.SERVER_ABORTED, aborted.reason);
        assertNull(aborted.winner);
        assertEquals(0L, aborted.finalTick);
        StepResult after = engine.step(empty(1));
        assertSame(aborted, after.result);
        assertEquals(0L, after.tick);
    }

    @Test
    public void invalidTickDoesNotMutateState() {
        MatchEngine engine = openEngine(100);
        double x = engine.viewFor("p").self.x;
        assertThrows(IllegalArgumentException.class, () -> engine.step(one(2, "p", 1, 0)));
        assertEquals(0L, engine.viewFor("p").tick);
        assertEquals(x, engine.viewFor("p").self.x, 0);
    }

    @Test
    public void invalidLaterCommandDoesNotMutateState() {
        MatchEngine engine = openEngine(100);
        double x = engine.viewFor("p").self.x;
        assertThrows(IllegalArgumentException.class, () -> engine.step(commands(1,
                cmd(1, "p", 1, 0),
                cmd(1, "unknown", 1, 0))));
        assertEquals(0L, engine.viewFor("p").tick);
        assertEquals(x, engine.viewFor("p").self.x, 0);
    }

    @Test
    public void policeInteractionAndDropAreIgnored() {
        MatchEngine engine = openEngine(100);
        StepResult interaction = engine.step(commands(1,
                new PlayerCommand("p", 1, 0, 0, true, null)));
        assertFalse(interaction.ended);
        assertEquals(1L, engine.viewFor("p").tick);

        StepResult drop = engine.step(commands(2,
                new PlayerCommand("p", 2, 0, 0, false, 1L)));
        assertFalse(drop.ended);
        assertEquals(2L, engine.viewFor("p").tick);
    }

    @Test
    public void viewForDoesNotChangeWithNextStep() {
        MatchEngine engine = openEngine(100);
        PlayerView before = engine.viewFor("p");
        assertEquals(0L, before.tick);
        engine.step(one(1, "p", 1, 0));
        assertEquals(0L, before.tick);
        assertEquals(5.5, before.self.x, 0);
        assertEquals(1L, engine.viewFor("p").tick);
    }

    @Test
    public void visibilityChangedOnFirstAndEndingStep() {
        MatchEngine engine = openEngine(100);
        assertTrue(engine.step(empty(1)).visibilityChanged);
        assertFalse(engine.step(empty(2)).visibilityChanged);
        assertTrue(engine.step(departures(3,
                new Departure("p", DepartureReason.QUIT))).visibilityChanged);
    }

    @Test
    public void samePlayerIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new V1EngineFactory().create(
                TestMaps.open(10, 10, 5.5, 5.5, 8.5, 5.5),
                TestMaps.rules(100),
                new MatchInit("m", "p", "p")));
    }

    @Test
    public void missingSpawnIsRejected() {
        Map<Role, SpawnPose> spawns = new LinkedHashMap<>();
        MapDefinition map = new MapDefinition("test", 1, 10, 10,
                new ArrayList<>(), spawns, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> new V1EngineFactory().create(
                map, TestMaps.rules(100), TestMaps.init()));
    }

    @Test
    public void unknownViewPlayerIsRejected() {
        MatchEngine engine = openEngine(100);
        assertThrows(IllegalArgumentException.class, () -> engine.viewFor("unknown"));
    }

    @Test
    public void prototypeFactoryStillReturnsLegacyBehavior() {
        DataFiles data = DataFiles.bundled();
        MatchInit init = new MatchInit("m", "p", "t");
        MatchEngine legacy = new PrototypeEngineFactory().create(data.map, data.rules, init);
        assertThrows(UnsupportedOperationException.class, () -> legacy.step(new StepInput(1,
                Collections.emptyList(),
                Arrays.asList(new Departure("p", DepartureReason.QUIT)))));

        MatchEngine v1 = new V1EngineFactory().create(data.map, data.rules, init);
        StepResult ended = v1.step(new StepInput(1, Collections.emptyList(),
                Arrays.asList(new Departure("p", DepartureReason.QUIT))));
        assertTrue(ended.ended);
        assertEquals(ResultReason.QUIT, ended.result.reason);
    }
}
