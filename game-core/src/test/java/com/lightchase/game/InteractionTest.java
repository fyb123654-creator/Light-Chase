package com.lightchase.game;

import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.Departure;
import com.lightchase.contracts.DepartureReason;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.OriginStatus;
import com.lightchase.contracts.PlayerCommand;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.Protection;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.StepInput;
import com.lightchase.contracts.ThiefPrivate;
import com.lightchase.contracts.WallCell;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InteractionTest {
    private static MatchEngine engine(MapDefinition map, RuleConfig rules) {
        return new V1EngineFactory().create(map, rules, TestMaps.init());
    }

    private static MatchEngine simpleTaskEngine() {
        return engine(simpleTaskMap(), TestMaps.rules(5400));
    }

    private static MapDefinition simpleTaskMap() {
        return TestMaps.taskMap(12, 10, 1.5, 1.5, 5.5, 5.5,
                Arrays.asList(TestMaps.material("M1", 5.9, 5.5, "D1")),
                Arrays.asList(TestMaps.point("D1", 5.5, 5.9)),
                Arrays.asList(TestMaps.point("E1", 6.1, 5.1), TestMaps.point("E2", 4.9, 5.1)));
    }

    private static MapDefinition visibleTaskMap() {
        return TestMaps.taskMap(12, 10, 5.5, 5.5, 7.5, 5.5,
                Arrays.asList(TestMaps.material("M1", 7.9, 5.5, "D1")),
                Arrays.asList(TestMaps.point("D1", 7.9, 5.9)),
                Arrays.asList(TestMaps.point("E1", 8.1, 5.1), TestMaps.point("E2", 6.9, 5.1)));
    }

    private static MapDefinition threeTaskMap() {
        return threeTaskMapWithExits(Arrays.asList(
                TestMaps.point("E1", 5.5, 6.2),
                TestMaps.point("E2", 4.8, 5.5)));
    }

    private static MapDefinition threeTaskMapWithExits(List<MapPoint> exits) {
        return TestMaps.taskMap(12, 10, 1.5, 1.5, 5.5, 5.5,
                Arrays.asList(
                        TestMaps.material("M1", 6.1, 5.5, "D1"),
                        TestMaps.material("M2", 5.5, 6.1, "D2"),
                        TestMaps.material("M3", 4.9, 5.5, "D3")),
                Arrays.asList(
                        TestMaps.point("D1", 5.9, 5.9),
                        TestMaps.point("D2", 5.1, 5.9),
                        TestMaps.point("D3", 5.1, 5.1)),
                exits);
    }

    private static StepInput empty(long tick) {
        return new StepInput(tick, Collections.emptyList(), Collections.emptyList());
    }

    private static StepInput thiefCommand(long tick, boolean held, Long dropId) {
        return new StepInput(tick,
                Arrays.asList(new PlayerCommand("t", tick, 0, 0, held, dropId)),
                Collections.emptyList());
    }

    private static StepInput thiefMove(long tick, double x, double y, boolean held) {
        return new StepInput(tick,
                Arrays.asList(new PlayerCommand("t", tick, x, y, held, null)),
                Collections.emptyList());
    }

    private static StepInput thiefEdges(long tick, boolean firstHeld, boolean secondHeld) {
        return new StepInput(tick,
                Arrays.asList(
                        new PlayerCommand("t", tick, 0, 0, firstHeld, null),
                        new PlayerCommand("t", tick, 0, 0, secondHeld, null)),
                Collections.emptyList());
    }

    private static StepInput thiefDropReleasePress(long tick, long dropId) {
        return new StepInput(tick,
                Arrays.asList(
                        new PlayerCommand("t", tick, 0, 0, false, dropId),
                        new PlayerCommand("t", tick, 0, 0, true, null)),
                Collections.emptyList());
    }

    private static long hold(MatchEngine engine, long tick, int steps) {
        for (int i = 0; i < steps; i++) {
            engine.step(thiefCommand(tick, true, null));
            tick++;
        }
        return tick;
    }

    private static long release(MatchEngine engine, long tick) {
        engine.step(thiefCommand(tick, false, null));
        return tick + 1;
    }

    private static long drop(MatchEngine engine, long tick, long dropId, boolean held) {
        engine.step(thiefCommand(tick, held, dropId));
        return tick + 1;
    }

    private static long idle(MatchEngine engine, long tick, int steps) {
        for (int i = 0; i < steps; i++) {
            engine.step(empty(tick));
            tick++;
        }
        return tick;
    }

    private static long policeMove(MatchEngine engine, long tick, double x, double y) {
        engine.step(new StepInput(tick,
                Arrays.asList(new PlayerCommand("p", tick, x, y, false, null)),
                Collections.emptyList()));
        return tick + 1;
    }

    private static long deliverAll(MatchEngine engine) {
        long tick = 1;
        for (String expected : Arrays.asList("M1", "M2", "M3")) {
            tick = hold(engine, tick, 30);
            assertEquals(expected, thief(engine).carriedMaterialId);
            tick = release(engine, tick);
            tick = hold(engine, tick, 30);
            assertNull(thief(engine).carriedMaterialId);
            tick = release(engine, tick);
        }
        return tick;
    }

    private static ThiefPrivate thief(MatchEngine engine) {
        PlayerView view = engine.viewFor("t");
        assertNotNull(view.thief);
        return view.thief;
    }

    private static OriginStatus origin(PlayerView view, String materialId) {
        for (OriginStatus status : view.originStatus) {
            if (status.materialId.equals(materialId)) {
                return status;
            }
        }
        throw new AssertionError("Missing origin status for " + materialId);
    }

    @Test
    public void holdingWithNoCommandContinuesInteraction() {
        MatchEngine engine = simpleTaskEngine();
        engine.step(thiefCommand(1, true, null));
        for (long tick = 2; tick <= 30; tick++) {
            engine.step(empty(tick));
        }
        assertEquals("M1", thief(engine).carriedMaterialId);
    }

    @Test
    public void releaseThenPressSameStepRestartsAtOne() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 12);
        assertEquals(12, thief(engine).interaction.progressTicks);

        engine.step(thiefEdges(tick, false, true));
        assertEquals(1, thief(engine).interaction.progressTicks);
        assertNotNull(thief(engine).interaction.active);
        assertEquals("M1", thief(engine).interaction.active.targetId);
    }

    @Test
    public void pressThenReleaseSameStepDoesNotStart() {
        MatchEngine engine = simpleTaskEngine();
        engine.step(thiefEdges(1, true, false));

        assertNull(thief(engine).interaction.active);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertNull(thief(engine).carriedMaterialId);
    }

    @Test
    public void emptyHandDropPulseCancelsActiveInteraction() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 12);
        assertEquals(12, thief(engine).interaction.progressTicks);

        engine.step(thiefCommand(tick, true, 1L)); // new drop pulse, but empty hands
        assertNull(thief(engine).interaction.active);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertTrue(thief(engine).interaction.requiresRelease);
        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size()); // M1 was not dropped

        engine.step(thiefCommand(tick + 1, true, null)); // still held: no restart
        assertEquals(0, thief(engine).interaction.progressTicks);
        release(engine, tick + 2);
        hold(engine, tick + 3, 1);
        assertEquals(1, thief(engine).interaction.progressTicks);
    }

    @Test
    public void emptyHandDropReleasePressSameStepStillRequiresRelease() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 12);
        assertEquals(12, thief(engine).interaction.progressTicks);

        engine.step(thiefDropReleasePress(tick, 1L));
        assertNull(thief(engine).interaction.active);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertTrue(thief(engine).interaction.requiresRelease);
        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size());

        engine.step(thiefCommand(tick + 1, true, null));
        assertEquals(0, thief(engine).interaction.progressTicks);
        release(engine, tick + 2);
        hold(engine, tick + 3, 1);
        assertEquals(1, thief(engine).interaction.progressTicks);
    }

    @Test
    public void emptyHandDropPulseWithPressSameStepDoesNotStart() {
        MatchEngine engine = simpleTaskEngine();
        engine.step(thiefCommand(1, true, 1L));

        assertNull(thief(engine).interaction.active);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertTrue(thief(engine).interaction.requiresRelease);
        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size());

        release(engine, 2);
        hold(engine, 3, 1);
        assertEquals(1, thief(engine).interaction.progressTicks);
    }

    @Test
    public void oldDropIdDoesNotCancelNewInteraction() {
        MatchEngine engine = simpleTaskEngine();
        engine.step(thiefCommand(1, false, 1L)); // consume drop id 1 while empty-handed
        assertEquals(0, thief(engine).interaction.progressTicks);

        engine.step(thiefCommand(2, true, null)); // start pickup
        assertEquals(1, thief(engine).interaction.progressTicks);

        engine.step(thiefCommand(3, true, 1L)); // old id: no new pulse, no cancel
        assertEquals(2, thief(engine).interaction.progressTicks);
        assertFalse(thief(engine).interaction.requiresRelease);
        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size());
    }

    @Test
    public void pickupCompletesAt30AndSetsCarrying() {        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 29);
        assertNull(thief(engine).carriedMaterialId);
        assertNotNull(thief(engine).interaction.active);
        assertEquals("M1", thief(engine).interaction.active.targetId);
        assertEquals(29, thief(engine).interaction.progressTicks);
        assertFalse(thief(engine).interaction.requiresRelease);
        assertEquals("NONE", thief(engine).interaction.blockedReason);

        hold(engine, tick, 1);
        assertEquals("M1", thief(engine).carriedMaterialId);
        assertTrue(thief(engine).interaction.requiresRelease);
        assertNull(thief(engine).interaction.active);
        assertNull(thief(engine).interaction.candidate);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertEquals("RELEASE_REQUIRED", thief(engine).interaction.blockedReason);
        assertTrue(thief(engine).groundMaterials.isEmpty());
        assertTrue(origin(engine.viewFor("t"), "M1").everPicked);
    }

    @Test
    public void pickupAt29DoesNotComplete() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 29);
        assertNull(thief(engine).carriedMaterialId);
        release(engine, tick);
        assertNull(thief(engine).carriedMaterialId);
    }

    @Test
    public void outOfRangeAndWallBlockPickup() {
        MatchEngine outOfRange = engine(TestMaps.taskMap(12, 10, 1.5, 1.5, 5.5, 5.5,
                Arrays.asList(TestMaps.material("M1", 6.4, 5.5, "D1")),
                Arrays.asList(TestMaps.point("D1", 5.5, 5.9)),
                Collections.emptyList()), TestMaps.rules(5400));
        hold(outOfRange, 1, 30);
        assertNull(thief(outOfRange).carriedMaterialId);
        assertEquals("NO_TARGET", thief(outOfRange).interaction.blockedReason);

        MatchEngine walled = engine(TestMaps.taskMap(12, 10, 1.5, 1.5, 5.5, 5.5,
                Arrays.asList(new WallCell(6, 5)),
                Arrays.asList(TestMaps.material("M1", 6.2, 5.5, "D1")),
                Arrays.asList(TestMaps.point("D1", 5.5, 5.9)),
                Collections.emptyList()), TestMaps.rules(5400));
        hold(walled, 1, 30);
        assertNull(thief(walled).carriedMaterialId);
        assertEquals("NO_TARGET", thief(walled).interaction.blockedReason);
    }

    @Test
    public void movementCancelsInteraction() {
        MatchEngine engine = simpleTaskEngine();
        engine.step(thiefCommand(1, true, null));
        engine.step(thiefMove(2, 1, 0, true));

        assertNull(thief(engine).carriedMaterialId);
        assertTrue(thief(engine).interaction.requiresRelease);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertEquals("RELEASE_REQUIRED", thief(engine).interaction.blockedReason);

        hold(engine, 3, 30);
        assertNull(thief(engine).carriedMaterialId);
    }

    @Test
    public void deliverMatchingMaterial() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 30); // pickup M1
        tick = release(engine, tick);
        hold(engine, tick, 30); // deliver D1

        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, engine.viewFor("t").deliveredCount);
        assertEquals(Arrays.asList("M1"), thief(engine).deliveredMaterialIds);
        assertFalse(engine.viewFor("t").exitsOpen);
        assertTrue(thief(engine).interaction.requiresRelease);
    }

    @Test
    public void wrongDeliveryIsRejected() {
        MatchEngine engine = engine(TestMaps.taskMap(12, 10, 1.5, 1.5, 5.5, 5.5,
                Arrays.asList(TestMaps.material("M1", 5.9, 5.5, "D1")),
                Arrays.asList(TestMaps.point("D2", 5.5, 5.9)),
                Collections.emptyList()), TestMaps.rules(5400));
        long tick = hold(engine, 1, 30); // pickup M1
        tick = release(engine, tick);
        hold(engine, tick, 30);

        assertEquals("M1", thief(engine).carriedMaterialId);
        assertEquals(0, engine.viewFor("t").deliveredCount);
        assertEquals("NO_TARGET", thief(engine).interaction.blockedReason);
    }

    @Test
    public void dropAtPostMoveFeetAndRepick() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 30); // pickup M1
        tick = release(engine, tick);
        engine.step(thiefMove(tick, 1, 0, false)); // carrying does not slow; move 0.1
        tick++;
        double movedX = engine.viewFor("t").self.x;
        tick = drop(engine, tick, 1, false);

        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size());
        assertEquals(movedX, thief(engine).groundMaterials.get(0).x, 1e-9);
        assertTrue(origin(engine.viewFor("t"), "M1").everPicked);

        tick = drop(engine, tick, 1, false); // duplicate drop id is consumed, no new item
        assertEquals(1, thief(engine).groundMaterials.size());

        hold(engine, tick, 30);
        assertEquals("M1", thief(engine).carriedMaterialId);
    }

    @Test
    public void dropWhileHeldRequiresReleaseBeforeRepick() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 30); // pickup, still held

        tick = drop(engine, tick, 1, true); // drop while holding
        assertNull(thief(engine).carriedMaterialId);
        assertTrue(thief(engine).interaction.requiresRelease);
        assertEquals("RELEASE_REQUIRED", thief(engine).interaction.blockedReason);

        hold(engine, tick, 30);
        assertNull(thief(engine).carriedMaterialId);

        release(engine, tick + 30);
        hold(engine, tick + 31, 30);
        assertEquals("M1", thief(engine).carriedMaterialId);
    }

    @Test
    public void invisibleBlocksPickupAndImmuneAllowsIt() {
        MatchEngine invisible = engine(visibleTaskMap(), TestMaps.rules(5400, 1, 100, 0));
        hold(invisible, 1, 30);
        assertNull(thief(invisible).carriedMaterialId);
        assertEquals(Protection.INVISIBLE, thief(invisible).protection);
        assertEquals("INVISIBLE", thief(invisible).interaction.blockedReason);

        MatchEngine immune = engine(visibleTaskMap(), TestMaps.rules(5400, 1, 0, 100));
        long tick = hold(immune, 1, 1); // mark: interaction cancels this step
        tick = release(immune, tick);
        hold(immune, tick, 30); // immune allows pickup
        assertEquals("M1", thief(immune).carriedMaterialId);
        assertEquals(Protection.IMMUNE, thief(immune).protection);
    }

    @Test
    public void dropWhileInvisibleIsAllowed() {
        MatchEngine engine = engine(visibleTaskMap(), TestMaps.rules(5400, 40, 45, 30));
        long tick = hold(engine, 1, 30); // pickup
        tick = release(engine, tick);
        idle(engine, tick, 9); // ticks 32..40; mark at 40
        assertEquals(Protection.INVISIBLE, thief(engine).protection);

        tick = 41;
        drop(engine, tick, 1, false);
        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size());
        assertEquals(Protection.INVISIBLE, thief(engine).protection);
    }

    @Test
    public void invisibleBlocksDelivery() {
        MatchEngine engine = engine(visibleTaskMap(), TestMaps.rules(5400, 40, 100, 0));
        long tick = hold(engine, 1, 30); // pickup
        tick = release(engine, tick);
        idle(engine, tick, 9); // ticks 32..40; mark at 40
        assertEquals(Protection.INVISIBLE, thief(engine).protection);

        hold(engine, 41, 30); // try to deliver while invisible
        assertEquals("M1", thief(engine).carriedMaterialId);
        assertEquals(0, engine.viewFor("t").deliveredCount);
    }

    @Test
    public void policeOpponentCarryingFlagAndNoPrivateData() {
        MatchEngine engine = engine(visibleTaskMap(), TestMaps.rules(5400, 100, 45, 30));
        hold(engine, 1, 30); // pickup; no mark because markTicks=100

        PlayerView police = engine.viewFor("p");
        assertNull(police.thief);
        assertNotNull(police.opponent);
        assertTrue(police.opponent.carrying);
        assertTrue(origin(police, "M1").everPicked);
        assertEquals("M1", thief(engine).carriedMaterialId);
    }

    @Test
    public void moveThenStopSameStepCancelsWithoutDisplacement() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 12);
        double x = engine.viewFor("t").self.x;

        StepInput step = new StepInput(tick, Arrays.asList(
                new PlayerCommand("t", tick, 1, 0, true, null),
                new PlayerCommand("t", tick, 0, 0, true, null)),
                Collections.emptyList());
        engine.step(step);

        assertEquals(x, engine.viewFor("t").self.x, 0);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertNull(thief(engine).interaction.active);
        assertTrue(thief(engine).interaction.requiresRelease);
    }

    @Test
    public void dropReleasePressSameStepDoesNotStartInteraction() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 30); // pickup
        tick = release(engine, tick);
        tick = hold(engine, tick, 1); // start delivering D1

        StepInput step = new StepInput(tick, Arrays.asList(
                new PlayerCommand("t", tick, 0, 0, false, 1L),
                new PlayerCommand("t", tick, 0, 0, true, null)),
                Collections.emptyList());
        engine.step(step);

        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size());
        assertNull(thief(engine).interaction.active);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertTrue(thief(engine).interaction.requiresRelease);
    }

    @Test
    public void releaseLeaseWithSameInputSeqCancelsProgress() {
        MatchEngine engine = simpleTaskEngine();
        hold(engine, 1, 12);
        assertEquals(12, thief(engine).interaction.progressTicks);

        engine.step(new StepInput(13, Arrays.asList(
                new PlayerCommand("t", 12, 0, 0, false, null)),
                Collections.emptyList()));

        assertEquals(0, thief(engine).interaction.progressTicks);
        assertNull(thief(engine).interaction.active);
        assertFalse(thief(engine).interaction.requiresRelease);
    }

    @Test
    public void moveAndDropSameStepUsesPostMovePosition() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 30); // pickup
        tick = release(engine, tick);
        double x = engine.viewFor("t").self.x;

        engine.step(new StepInput(tick, Arrays.asList(
                new PlayerCommand("t", tick, 1, 0, false, 1L)),
                Collections.emptyList()));

        assertEquals(x + 0.1, engine.viewFor("t").self.x, 1e-9);
        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size());
        assertEquals(x + 0.1, thief(engine).groundMaterials.get(0).x, 1e-9);
    }

    @Test
    public void oldDropIdAfterRepickDoesNotDropOrCancel() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 30); // pickup
        tick = release(engine, tick);
        tick = drop(engine, tick, 1, false); // drop at feet
        tick = hold(engine, tick, 30); // re-pick
        assertEquals("M1", thief(engine).carriedMaterialId);
        tick = release(engine, tick);
        tick = hold(engine, tick, 1); // start delivering D1, progress 1

        engine.step(thiefCommand(tick, true, 1L)); // old drop id
        assertEquals(2, thief(engine).interaction.progressTicks);
        assertEquals("M1", thief(engine).carriedMaterialId);
        assertTrue(thief(engine).groundMaterials.isEmpty());
        assertFalse(thief(engine).interaction.requiresRelease);
    }

    @Test
    public void exitCandidateUsesDistanceThenId() {
        MatchEngine tie = engine(threeTaskMapWithExits(Arrays.asList(
                TestMaps.point("E2", 4.8, 5.5), // inserted first, but E1 has the lower id
                TestMaps.point("E1", 5.5, 6.2))), TestMaps.rules(5400));
        deliverAll(tie);
        assertEquals("E1", thief(tie).interaction.candidate.targetId);

        MatchEngine nearer = engine(threeTaskMapWithExits(Arrays.asList(
                TestMaps.point("E1", 5.5, 6.2), // distance 0.7
                TestMaps.point("E2", 5.7, 5.5))), TestMaps.rules(5400)); // distance 0.2
        deliverAll(nearer);
        assertEquals("E2", thief(nearer).interaction.candidate.targetId);
    }

    @Test
    public void invisibleBlocksEscapeAndImmuneAllowsIt() {
        MatchEngine engine = engine(threeTaskMap(), TestMaps.rules(5400));
        long tick = deliverAll(engine);
        assertTrue(engine.viewFor("t").exitsOpen);

        for (int i = 0; i < 40; i++) { // police north, out of the beam
            tick = policeMove(engine, tick, 0, 1);
        }
        tick = policeMove(engine, tick, 1, 0); // police east; beam starts
        engine.step(thiefCommand(tick, true, null)); // start escaping
        tick++;
        idle(engine, tick, 22); // beam reaches 24 at tick 250

        assertEquals(Protection.INVISIBLE, thief(engine).protection);
        assertNull(thief(engine).interaction.active);
        assertEquals(0, thief(engine).interaction.progressTicks);
        assertTrue(thief(engine).interaction.requiresRelease);
        assertNull(engine.result());

        tick = 251;
        tick = release(engine, tick);
        tick = hold(engine, tick, 30); // invisible: no escape
        assertNull(engine.result());
        assertEquals(Protection.INVISIBLE, thief(engine).protection);

        tick = release(engine, tick);
        tick = idle(engine, tick, 13); // reach tick 295, immune
        assertEquals(Protection.IMMUNE, thief(engine).protection);
        tick = hold(engine, tick, 30); // immune: escape allowed

        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.ESCAPED, result.reason);
        assertEquals(Role.THIEF, result.winner);
        assertEquals(3, result.deliveredCount);
    }

    @Test
    public void escapeWinsOverSameStepThiefDeparture() {
        MatchEngine engine = engine(threeTaskMap(), TestMaps.rules(5400));
        long tick = deliverAll(engine);
        tick = hold(engine, tick, 29); // escape progress 29
        engine.step(new StepInput(tick, Arrays.asList(
                new PlayerCommand("t", tick, 0, 0, true, null)),
                Arrays.asList(new Departure("t", DepartureReason.QUIT))));

        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.ESCAPED, result.reason);
        assertEquals(Role.THIEF, result.winner);
        assertEquals(3, result.deliveredCount);
        assertNull(result.departedPlayerId);
    }

    @Test
    public void threeDeliveriesOpenExitsAndEscape() {
        MatchEngine engine = engine(threeTaskMap(), TestMaps.rules(5400));
        long tick = 1;
        for (String expected : Arrays.asList("M1", "M2", "M3")) {
            tick = hold(engine, tick, 30);
            assertEquals(expected, thief(engine).carriedMaterialId);
            tick = release(engine, tick);
            tick = hold(engine, tick, 30);
            assertNull(thief(engine).carriedMaterialId);
            tick = release(engine, tick);
        }

        assertEquals(3, engine.viewFor("t").deliveredCount);
        assertTrue(engine.viewFor("t").exitsOpen);
        assertEquals(Arrays.asList("M1", "M2", "M3"), thief(engine).deliveredMaterialIds);

        hold(engine, tick, 30); // escape E1
        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.ESCAPED, result.reason);
        assertEquals(Role.THIEF, result.winner);
        assertEquals(3, result.deliveredCount);
        assertEquals(216L, result.finalTick);
    }

    @Test
    public void candidateUsesNearestThenIdWithNoExitsOpen() {
        MatchEngine engine = engine(TestMaps.taskMap(12, 10, 1.5, 1.5, 5.5, 5.5,
                Arrays.asList(
                        TestMaps.material("M1", 5.9, 5.5, "D1"),
                        TestMaps.material("M2", 5.1, 5.5, "D2")),
                Collections.emptyList(), Collections.emptyList()), TestMaps.rules(5400));
        engine.step(empty(1));
        assertEquals("M1", thief(engine).interaction.candidate.targetId);
        assertFalse(engine.viewFor("t").exitsOpen);
    }

    @Test
    public void blockedMovementStillCancelsInteraction() {
        MatchEngine engine = engine(TestMaps.taskMap(12, 10, 1.5, 1.5, 5.75, 5.5,
                Arrays.asList(new WallCell(6, 5)),
                Arrays.asList(TestMaps.material("M1", 5.75, 6.1, "D1")),
                Arrays.asList(TestMaps.point("D1", 5.1, 5.9)),
                Collections.emptyList()), TestMaps.rules(5400));
        long tick = hold(engine, 1, 12);
        assertEquals(12, thief(engine).interaction.progressTicks);

        engine.step(thiefMove(tick, 1, 0, true)); // the wall blocks the X step

        assertEquals(0, thief(engine).interaction.progressTicks);
        assertNull(thief(engine).interaction.active);
        assertTrue(thief(engine).interaction.requiresRelease);
        assertEquals(5.75, engine.viewFor("t").self.x, 1e-9);
    }

    @Test
    public void immuneAllowsDelivery() {
        MatchEngine engine = engine(visibleTaskMap(), TestMaps.rules(5400, 1, 0, 100));
        long tick = hold(engine, 1, 1); // first mark; this step cancels interaction
        assertEquals(Protection.IMMUNE, thief(engine).protection);
        tick = release(engine, tick);

        tick = hold(engine, tick, 30); // pickup while immune
        assertEquals("M1", thief(engine).carriedMaterialId);
        tick = release(engine, tick);

        tick = hold(engine, tick, 30); // deliver while immune
        assertEquals(1, engine.viewFor("t").deliveredCount);
        assertEquals(Protection.IMMUNE, thief(engine).protection);
    }

    @Test
    public void multipleDropIdsInOneStepDropOnlyOneMaterial() {
        MatchEngine engine = simpleTaskEngine();
        long tick = hold(engine, 1, 30); // pickup M1
        tick = release(engine, tick);

        engine.step(new StepInput(tick, Arrays.asList(
                new PlayerCommand("t", tick, 0, 0, false, 1L),
                new PlayerCommand("t", tick, 0, 0, false, 2L)),
                Collections.emptyList()));

        assertNull(thief(engine).carriedMaterialId);
        assertEquals(1, thief(engine).groundMaterials.size());

        engine.step(thiefCommand(tick + 1, false, 2L)); // consumed id must not drop again
        assertEquals(1, thief(engine).groundMaterials.size());
        assertEquals(0, thief(engine).interaction.progressTicks);
    }
}
