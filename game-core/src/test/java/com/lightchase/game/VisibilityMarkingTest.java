package com.lightchase.game;

import com.lightchase.contracts.MapDefinition;
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
import com.lightchase.contracts.ThiefPrivate;
import com.lightchase.contracts.WallCell;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VisibilityMarkingTest {
    private static MatchEngine engine(MapDefinition map) {
        return engine(map, TestMaps.rules(5400));
    }

    private static MatchEngine engine(MapDefinition map, RuleConfig rules) {
        return new V1EngineFactory().create(map, rules, TestMaps.init());
    }

    private static MapDefinition openMap() {
        return TestMaps.open(10, 10, 5.5, 5.5, 7.5, 5.5);
    }

    private static MatchEngine openEngine() {
        return engine(openMap());
    }

    private static StepInput empty(long tick) {
        return new StepInput(tick, Collections.emptyList(), Collections.emptyList());
    }

    private static StepInput one(long tick, String player, double x, double y) {
        return new StepInput(tick,
                Arrays.asList(new PlayerCommand(player, tick, x, y, false, null)),
                Collections.emptyList());
    }

    private static void run(MatchEngine engine, long fromTick, int count) {
        for (int i = 0; i < count; i++) {
            engine.step(empty(fromTick + i));
        }
    }

    private static ThiefPrivate thiefView(MatchEngine engine) {
        PlayerView view = engine.viewFor("t");
        assertNotNull(view.thief);
        return view.thief;
    }

    @Test
    public void tickZeroVisibilityMatchesGeometry() {
        MatchEngine engine = openEngine();
        PlayerView police = engine.viewFor("p");
        PlayerView thief = engine.viewFor("t");

        assertEquals(0L, police.tick);
        assertNotNull(police.opponent);
        assertEquals(Role.THIEF, police.opponent.role);
        assertFalse(police.opponent.carrying);
        assertNotNull(thief.opponent);
        assertEquals(Role.POLICE, thief.opponent.role);
    }

    @Test
    public void wallBlocksPoliceBeamButNotThiefView() {
        MatchEngine engine = engine(TestMaps.withWalls(10, 10, 5.5, 5.5, 7.5, 5.5,
                Arrays.asList(new WallCell(6, 5))));

        assertNull(engine.viewFor("p").opponent);
        assertNotNull(engine.viewFor("t").opponent);

        run(engine, 1, 24);
        assertEquals(0, engine.viewFor("p").marks);
        assertEquals(0, engine.viewFor("t").marks);
        assertNull(engine.viewFor("p").opponent);
    }

    @Test
    public void outOfRangeAndOutOfAngleAreNotVisibleToPolice() {
        MatchEngine far = engine(TestMaps.open(12, 10, 5.5, 5.5, 9.6, 5.5));
        assertNull(far.viewFor("p").opponent);
        assertNotNull(far.viewFor("t").opponent);
        run(far, 1, 24);
        assertEquals(0, far.viewFor("p").marks);

        MatchEngine angle = engine(TestMaps.open(10, 10, 5.5, 5.5, 5.5, 7.5));
        assertNull(angle.viewFor("p").opponent);
        assertNotNull(angle.viewFor("t").opponent);
        run(angle, 1, 24);
        assertEquals(0, angle.viewFor("p").marks);
    }

    @Test
    public void zeroDistanceIsInsideBeam() {
        MatchEngine engine = engine(TestMaps.open(10, 10, 5.5, 5.5, 5.5, 5.5));
        assertNotNull(engine.viewFor("p").opponent);
        run(engine, 1, 24);
        assertEquals(1, engine.viewFor("p").marks);
    }

    @Test
    public void thiefRectangleBoundaryShowsAndHidesOpponent() {
        double halfWidth = 8.0 * 16 / 9 / 2;
        double halfHeight = 4.0;
        double thiefX = 12;
        double thiefY = 9;

        MatchEngine insideHorizontal = engine(TestMaps.open(24, 18,
                thiefX + halfWidth, thiefY, thiefX, thiefY));
        assertNotNull(insideHorizontal.viewFor("t").opponent);
        MatchEngine outsideHorizontal = engine(TestMaps.open(24, 18,
                thiefX + halfWidth + 0.01, thiefY, thiefX, thiefY));
        assertNull(outsideHorizontal.viewFor("t").opponent);

        MatchEngine insideVertical = engine(TestMaps.open(24, 18,
                thiefX, thiefY + halfHeight, thiefX, thiefY));
        assertNotNull(insideVertical.viewFor("t").opponent);
        MatchEngine outsideVertical = engine(TestMaps.open(24, 18,
                thiefX, thiefY + halfHeight + 0.01, thiefX, thiefY));
        assertNull(outsideVertical.viewFor("t").opponent);
    }

    @Test
    public void policeBeamDistanceBoundaryIsInclusive() {
        MatchEngine inside = engine(TestMaps.open(24, 18, 5.5, 5.5, 9.5, 5.5));
        assertNotNull(inside.viewFor("p").opponent);

        MatchEngine outside = engine(TestMaps.open(24, 18, 5.5, 5.5, 9.51, 5.5));
        assertNull(outside.viewFor("p").opponent);
    }

    @Test
    public void policeBeamAngleBoundaryIsInclusive() {
        double distance = 2.0;
        double insideAngle = Math.toRadians(30);
        double outsideAngle = Math.toRadians(30.5);

        MatchEngine inside = engine(TestMaps.open(24, 18, 5.5, 5.5,
                5.5 + distance * Math.cos(insideAngle),
                5.5 + distance * Math.sin(insideAngle)));
        assertNotNull(inside.viewFor("p").opponent);

        MatchEngine outside = engine(TestMaps.open(24, 18, 5.5, 5.5,
                5.5 + distance * Math.cos(outsideAngle),
                5.5 + distance * Math.sin(outsideAngle)));
        assertNull(outside.viewFor("p").opponent);
    }

    @Test
    public void thiefOpponentFlipsWhenPoliceLeavesRectangle() {
        MatchEngine engine = engine(TestMaps.open(24, 18, 12, 12, 12, 9));
        assertNotNull(engine.viewFor("t").opponent);

        for (int tick = 1; tick <= 10; tick++) {
            engine.step(one(tick, "p", 0, 1)); // y=13.0 is still the inclusive boundary
        }
        assertNotNull(engine.viewFor("t").opponent);

        StepResult flip = engine.step(one(11, "p", 0, 1)); // y=13.1, outside
        assertTrue(flip.visibilityChanged);
        assertNull(engine.viewFor("t").opponent);

        StepResult steady = engine.step(one(12, "p", 0, 1)); // stays outside
        assertFalse(steady.visibilityChanged);
        assertNull(engine.viewFor("t").opponent);
    }

    @Test
    public void normalProtectionDoesNotRevealOutOfBeamThief() {
        MatchEngine engine = openEngine();
        run(engine, 1, 24); // mark: invisible
        engine.step(one(25, "p", 0, 1)); // turn north, beam leaves the thief
        engine.step(one(26, "p", 0, 0)); // stop
        run(engine, 27, 72); // 27..98, invisible/immune out of beam
        engine.step(empty(99)); // protection is NORMAL, but beam is still off

        assertEquals(Protection.NORMAL, thiefView(engine).protection);
        assertEquals(0, thiefView(engine).protectionRemainingTicks);
        assertNull(engine.viewFor("p").opponent);
    }

    @Test
    public void markOnlyAt24thContinuousStep() {
        MatchEngine engine = openEngine();
        run(engine, 1, 23);
        assertEquals(0, engine.viewFor("p").marks);
        assertNotNull(engine.viewFor("p").opponent);

        StepResult result = engine.step(empty(24));
        assertTrue(result.visibilityChanged);
        assertEquals(1, engine.viewFor("p").marks);
        assertNull(engine.viewFor("p").opponent);
        assertEquals(Protection.INVISIBLE, thiefView(engine).protection);
        assertEquals(45, thiefView(engine).protectionRemainingTicks);
    }

    @Test
    public void zeroInvisibleTicksStartsAtImmune() {
        MatchEngine engine = engine(openMap(), TestMaps.rules(5400, 24, 0, 30));
        run(engine, 1, 24);
        assertEquals(1, engine.viewFor("p").marks);
        assertEquals(Protection.IMMUNE, thiefView(engine).protection);
        assertEquals(30, thiefView(engine).protectionRemainingTicks);
        assertNotNull(engine.viewFor("p").opponent);

        run(engine, 25, 29); // 25..53
        assertEquals(Protection.IMMUNE, thiefView(engine).protection);
        assertEquals(1, thiefView(engine).protectionRemainingTicks);
        assertEquals(1, engine.viewFor("p").marks);

        engine.step(empty(54));
        assertEquals(Protection.NORMAL, thiefView(engine).protection);
        assertEquals(0, thiefView(engine).protectionRemainingTicks);
        assertEquals(1, engine.viewFor("p").marks);
    }

    @Test
    public void zeroImmuneTicksLeavesInvisibleThenNormal() {
        MatchEngine engine = engine(openMap(), TestMaps.rules(5400, 24, 45, 0));
        run(engine, 1, 24);
        assertEquals(Protection.INVISIBLE, thiefView(engine).protection);
        assertEquals(45, thiefView(engine).protectionRemainingTicks);

        run(engine, 25, 44); // 25..68
        assertEquals(Protection.INVISIBLE, thiefView(engine).protection);
        assertEquals(1, thiefView(engine).protectionRemainingTicks);
        assertNull(engine.viewFor("p").opponent);

        engine.step(empty(69));
        assertEquals(Protection.NORMAL, thiefView(engine).protection);
        assertEquals(0, thiefView(engine).protectionRemainingTicks);
        assertEquals(1, engine.viewFor("p").marks);
    }

    @Test
    public void bothProtectionTicksZeroStayNormalAndMarkOncePerStep() {
        MatchEngine engine = engine(openMap(), TestMaps.rules(5400, 24, 0, 0));
        run(engine, 1, 24);
        assertEquals(1, engine.viewFor("p").marks);
        assertEquals(Protection.NORMAL, thiefView(engine).protection);
        assertEquals(0, thiefView(engine).protectionRemainingTicks);
        assertNotNull(engine.viewFor("p").opponent);

        engine.step(empty(25));
        assertEquals(1, engine.viewFor("p").marks); // no second mark in the same/next step
        run(engine, 26, 23); // 26..48, a fresh full 24-step run
        assertEquals(2, engine.viewFor("p").marks);
    }

    @Test
    public void protectionBoundariesAndThirdMark() {
        MatchEngine engine = openEngine();
        run(engine, 1, 24);
        assertEquals(1, engine.viewFor("p").marks);
        assertEquals(Protection.INVISIBLE, thiefView(engine).protection);
        assertEquals(45, thiefView(engine).protectionRemainingTicks);

        run(engine, 25, 44); // ticks 25..68
        assertEquals(Protection.INVISIBLE, thiefView(engine).protection);
        assertEquals(1, thiefView(engine).protectionRemainingTicks);
        assertNull(engine.viewFor("p").opponent);

        engine.step(empty(69)); // k+45
        assertEquals(Protection.IMMUNE, thiefView(engine).protection);
        assertEquals(30, thiefView(engine).protectionRemainingTicks);
        assertEquals(1, engine.viewFor("p").marks);
        assertNotNull(engine.viewFor("p").opponent);

        run(engine, 70, 29); // ticks 70..98
        assertEquals(Protection.IMMUNE, thiefView(engine).protection);
        assertEquals(1, thiefView(engine).protectionRemainingTicks);
        assertEquals(1, engine.viewFor("p").marks);

        engine.step(empty(99)); // k+75
        assertEquals(Protection.NORMAL, thiefView(engine).protection);
        assertEquals(0, thiefView(engine).protectionRemainingTicks);
        assertEquals(1, engine.viewFor("p").marks);

        // Normal accumulation after protection: second mark at tick 122.
        run(engine, 100, 23); // 100..122
        assertEquals(2, engine.viewFor("p").marks);
        assertEquals(Protection.INVISIBLE, thiefView(engine).protection);

        // Invisible 123..166, immune 167..196, normal at 197.
        run(engine, 123, 75);
        assertEquals(Protection.NORMAL, thiefView(engine).protection);
        assertEquals(2, engine.viewFor("p").marks);

        // Third continuous 24 steps from tick 197: mark at 220, immediate capture.
        run(engine, 198, 23);
        assertEquals(3, engine.viewFor("p").marks);
        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.CAPTURED, result.reason);
        assertEquals(Role.POLICE, result.winner);
        assertEquals(3, result.marks);
        assertEquals(Protection.NORMAL, thiefView(engine).protection);
        assertEquals(0, thiefView(engine).protectionRemainingTicks);
    }

    @Test
    public void markProgressResetsAfterLeavingBeam() {
        MatchEngine engine = openEngine();
        run(engine, 1, 23);
        assertEquals(0, engine.viewFor("p").marks);

        // Turn police north: beam leaves the thief and the 23-step progress resets.
        engine.step(one(24, "p", 0, 1));
        engine.step(one(25, "p", 0, 0)); // stop moving north
        run(engine, 26, 22); // 26..47, still out of beam
        assertEquals(0, engine.viewFor("p").marks);

        // Turn east again; this step starts a fresh 24-step accumulation.
        engine.step(one(48, "p", 1, 0));
        engine.step(one(49, "p", 0, 0)); // stop moving east
        run(engine, 50, 21); // 50..70, progress 23
        assertEquals(0, engine.viewFor("p").marks);

        StepResult result = engine.step(empty(71)); // 24th step of the new run
        assertTrue(result.visibilityChanged);
        assertEquals(1, engine.viewFor("p").marks);
    }

    @Test
    public void visibilityChangedOnInvisibleAndImmuneSteps() {
        MatchEngine engine = openEngine();
        assertTrue(engine.step(empty(1)).visibilityChanged);
        assertFalse(engine.step(empty(2)).visibilityChanged);
        run(engine, 3, 21); // 3..23: police keeps seeing the thief
        assertTrue(engine.step(empty(24)).visibilityChanged); // visible -> invisible

        run(engine, 25, 44); // 25..68: invisible, no flip
        assertTrue(engine.step(empty(69)).visibilityChanged); // invisible -> immune visible

        run(engine, 70, 29); // 70..98: immune visible, no flip
        assertFalse(engine.step(empty(99)).visibilityChanged); // normal, still visible
    }

    @Test
    public void policeViewHasNoPrivateThiefState() {
        MatchEngine engine = openEngine();
        run(engine, 1, 24);

        PlayerView police = engine.viewFor("p");
        assertEquals(1, police.marks);
        assertNull(police.thief);
        assertNull(police.opponent);

        PlayerView thief = engine.viewFor("t");
        assertNotNull(thief.thief);
        assertEquals(Protection.INVISIBLE, thief.thief.protection);
        assertNotNull(thief.opponent);
        assertEquals(Role.POLICE, thief.opponent.role);
    }
}
