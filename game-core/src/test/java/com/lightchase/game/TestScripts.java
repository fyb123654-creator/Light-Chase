package com.lightchase.game;

import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.PlayerCommand;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.StepInput;
import com.lightchase.contracts.ThiefPrivate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Shared deterministic scripts for A4/A5 tests. */
final class TestScripts {
    private TestScripts() {
    }

    static MapDefinition simpleTaskMap() {
        return TestMaps.taskMap(12, 10, 1.5, 1.5, 5.5, 5.5,
                Arrays.asList(TestMaps.material("M1", 5.9, 5.5, "D1")),
                Arrays.asList(TestMaps.point("D1", 5.5, 5.9)),
                Arrays.asList(TestMaps.point("E1", 6.1, 5.1), TestMaps.point("E2", 4.9, 5.1)));
    }

    static MapDefinition threeTaskMap() {
        return threeTaskMapAt(1.5, 1.5);
    }

    static MapDefinition threeTaskMapAt(double policeX, double policeY) {
        return threeTaskMapWithExitsAt(policeX, policeY, Arrays.asList(
                TestMaps.point("E1", 5.5, 6.2),
                TestMaps.point("E2", 4.8, 5.5)));
    }

    static MapDefinition threeTaskMapWithExits(List<MapPoint> exits) {
        return threeTaskMapWithExitsAt(1.5, 1.5, exits);
    }

    static MapDefinition threeTaskMapWithExitsAt(double policeX, double policeY, List<MapPoint> exits) {
        return TestMaps.taskMap(12, 10, policeX, policeY, 5.5, 5.5,
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

    static StepInput empty(long tick) {
        return new StepInput(tick, Collections.emptyList(), Collections.emptyList());
    }

    static StepInput thiefCommand(long tick, boolean held, Long dropId) {
        return new StepInput(tick,
                Arrays.asList(new PlayerCommand("t", tick, 0, 0, held, dropId)),
                Collections.emptyList());
    }

    static StepInput thiefMove(long tick, double x, double y, boolean held) {
        return new StepInput(tick,
                Arrays.asList(new PlayerCommand("t", tick, x, y, held, null)),
                Collections.emptyList());
    }

    static StepInput policeMove(long tick, double x, double y) {
        return new StepInput(tick,
                Arrays.asList(new PlayerCommand("p", tick, x, y, false, null)),
                Collections.emptyList());
    }

    static long hold(MatchEngine engine, long tick, int steps) {
        for (int i = 0; i < steps; i++) {
            engine.step(thiefCommand(tick, true, null));
            tick++;
        }
        return tick;
    }

    static long release(MatchEngine engine, long tick) {
        engine.step(thiefCommand(tick, false, null));
        return tick + 1;
    }

    static long drop(MatchEngine engine, long tick, long dropId, boolean held) {
        engine.step(thiefCommand(tick, held, dropId));
        return tick + 1;
    }

    static long idle(MatchEngine engine, long tick, int steps) {
        for (int i = 0; i < steps; i++) {
            engine.step(empty(tick));
            tick++;
        }
        return tick;
    }

    static long deliverAll(MatchEngine engine) {
        return deliverAllFrom(engine, 1);
    }

    static long deliverAllFrom(MatchEngine engine, long startTick) {
        long tick = startTick;
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

    static ThiefPrivate thief(MatchEngine engine) {
        PlayerView view = engine.viewFor("t");
        assertNotNull(view.thief);
        return view.thief;
    }
}
