package com.lightchase.game;

import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.Protection;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.StepResult;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FullMatchTest {
    @Test
    public void thiefDropsRepicksDeliversAllAndEscapes() {
        MatchEngine engine = new V1EngineFactory().create(
                TestScripts.threeTaskMap(), TestMaps.rules(5400), TestMaps.init());

        long tick = TestScripts.hold(engine, 1, 30); // pickup M1
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.drop(engine, tick, 1, false); // drop M1
        tick = TestScripts.hold(engine, tick, 30); // re-pick M1
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // deliver D1
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // pickup M2
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // deliver D2
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // pickup M3
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // deliver D3
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // escape

        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals("m", result.matchId);
        assertEquals(ResultReason.ESCAPED, result.reason);
        assertEquals(Role.THIEF, result.winner);
        assertEquals(0, result.marks);
        assertEquals(3, result.deliveredCount);
        assertEquals(248L, result.elapsedTicks);
        assertEquals(248L, result.finalTick);
        assertEquals("m:result", result.resultId);
        assertNull(result.departedPlayerId);

        PlayerView thief = engine.viewFor("t");
        assertEquals(3, thief.deliveredCount);
        assertTrue(thief.exitsOpen);
        assertNull(thief.thief.carriedMaterialId);
        assertTrue(thief.thief.groundMaterials.isEmpty());
        assertEquals(Arrays.asList("M1", "M2", "M3"), thief.thief.deliveredMaterialIds);
        assertEquals(248L, thief.tick);

        PlayerView police = engine.viewFor("p");
        assertEquals(thief.tick, police.tick);
        assertEquals(result.finalTick, police.tick);
        assertEquals(3, police.deliveredCount);
        assertTrue(police.exitsOpen);
        assertNull(police.thief);
        assertEquals(3, police.originStatus.size());
        for (int i = 0; i < police.originStatus.size(); i++) {
            assertTrue(police.originStatus.get(i).everPicked);
        }

        StepResult again = engine.step(TestScripts.empty(249));
        assertTrue(again.ended);
        assertSame(result, again.result);
        assertEquals(248L, again.tick);
        assertSame(result, engine.result());
        assertSame(result, engine.abort());
        assertFalse(again.visibilityChanged);
    }

    @Test
    public void fullMatchWithTwoMarksDropRepickDeliverAndEscape() {
        MatchEngine engine = new V1EngineFactory().create(
                TestScripts.threeTaskMapAt(2.5, 5.5),
                TestMaps.rules(5400, 1, 45, 30),
                TestMaps.init());

        engine.step(TestScripts.empty(1)); // first mark at tick 1
        long tick = TestScripts.idle(engine, 2, 75); // 2..76; second mark at 76
        assertEquals(2, engine.viewFor("p").marks);
        assertEquals(Protection.INVISIBLE, TestScripts.thief(engine).protection);

        tick = TestScripts.idle(engine, tick, 73); // 77..149: invisible then immune
        engine.step(TestScripts.policeMove(tick++, 0, 1)); // 150: turn away while immune
        engine.step(TestScripts.policeMove(tick++, 0, 0)); // 151: normal, beam is off
        assertEquals(Protection.NORMAL, TestScripts.thief(engine).protection);

        tick = TestScripts.hold(engine, tick, 30); // pickup M1
        assertEquals("M1", TestScripts.thief(engine).carriedMaterialId);
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.drop(engine, tick, 1, false); // drop M1 at the thief's feet
        assertNull(TestScripts.thief(engine).carriedMaterialId);
        assertEquals(3, TestScripts.thief(engine).groundMaterials.size()); // M1 back on the ground
        tick = TestScripts.hold(engine, tick, 30); // re-pick M1
        assertEquals("M1", TestScripts.thief(engine).carriedMaterialId);
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // deliver D1
        assertEquals(1, engine.viewFor("t").deliveredCount);
        tick = TestScripts.release(engine, tick);

        tick = TestScripts.hold(engine, tick, 30); // pickup M2
        assertEquals("M2", TestScripts.thief(engine).carriedMaterialId);
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // deliver D2
        assertEquals(2, engine.viewFor("t").deliveredCount);
        tick = TestScripts.release(engine, tick);

        tick = TestScripts.hold(engine, tick, 30); // pickup M3
        assertEquals("M3", TestScripts.thief(engine).carriedMaterialId);
        tick = TestScripts.release(engine, tick);
        tick = TestScripts.hold(engine, tick, 30); // deliver D3
        assertEquals(3, engine.viewFor("t").deliveredCount);
        assertTrue(engine.viewFor("t").exitsOpen);
        tick = TestScripts.release(engine, tick);

        tick = TestScripts.hold(engine, tick, 30); // escape through E1

        MatchResult result = engine.result();
        assertNotNull(result);
        assertEquals(ResultReason.ESCAPED, result.reason);
        assertEquals(Role.THIEF, result.winner);
        assertEquals(2, result.marks);
        assertEquals(3, result.deliveredCount);
        assertEquals(tick - 1, result.finalTick);
        assertNull(result.departedPlayerId);

        PlayerView thief = engine.viewFor("t");
        assertEquals(3, thief.deliveredCount);
        assertTrue(thief.exitsOpen);
        assertNull(thief.thief.carriedMaterialId);
        assertTrue(thief.thief.groundMaterials.isEmpty());
        assertEquals(Arrays.asList("M1", "M2", "M3"), thief.thief.deliveredMaterialIds);
        assertEquals(2, thief.marks);

        PlayerView police = engine.viewFor("p");
        assertEquals(thief.tick, police.tick);
        assertEquals(result.finalTick, police.tick);
        assertEquals(2, police.marks);
        assertEquals(3, police.deliveredCount);
        assertNull(police.thief);

        StepResult again = engine.step(TestScripts.empty(tick));
        assertTrue(again.ended);
        assertSame(result, again.result);
    }
}
