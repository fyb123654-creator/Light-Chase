package com.lightchase.game;

import com.lightchase.contracts.Protection;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.WallCell;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.StepResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2VisibilityTest {

    /** Moves the officer right six ticks, to exactly four cells from the thief. */
    private static MatchEngine officerInBeam() {
        MatchEngine engine = V2TestData.engine();
        for (long i = 1; i <= 6; i++) {
            V2TestData.tick(engine, i,
                    V2TestData.police(i, 1, 0, null, false),
                    V2TestData.thief(i, 0, 0, false));
        }
        return engine;
    }

    @Test
    public void beamMarksAfterConfiguredTicksThenProtectsInOrder() {
        MatchEngine engine = officerInBeam();
        PlayerView police = engine.viewFor(V2TestData.POLICE);
        assertEquals(8.5, police.self().x(), 1e-9);
        assertNotNull(police.opponent());
        assertEquals(0, police.marks());

        StepResult marked = V2TestData.tick(engine, 7,
                V2TestData.police(7, 0, 0, null, false),
                V2TestData.thief(7, 0, 0, false));
        police = engine.viewFor(V2TestData.POLICE);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(1, police.marks());
        assertNull(police.opponent());
        assertEquals(Protection.INVISIBLE, thief.thief().protection());
        assertEquals(2, thief.thief().protectionRemainingTicks());
        assertEquals(9L, thief.thief().invisibleUntilTick());
        assertTrue(marked.visibilityChanged());

        V2TestData.tick(engine, 8,
                V2TestData.police(8, 0, 0, null, false),
                V2TestData.thief(8, 0, 0, false));
        assertEquals(1, engine.viewFor(V2TestData.THIEF).thief().protectionRemainingTicks());

        V2TestData.tick(engine, 9,
                V2TestData.police(9, 0, 0, null, false),
                V2TestData.thief(9, 0, 0, false));
        thief = engine.viewFor(V2TestData.THIEF);
        police = engine.viewFor(V2TestData.POLICE);
        assertEquals(Protection.IMMUNE, thief.thief().protection());
        assertEquals(2, thief.thief().protectionRemainingTicks());
        assertNotNull(police.opponent());

        V2TestData.tick(engine, 10,
                V2TestData.police(10, 0, 0, null, false),
                V2TestData.thief(10, 0, 0, false));
        assertEquals(1, engine.viewFor(V2TestData.THIEF).thief().protectionRemainingTicks());
        assertEquals(1, engine.viewFor(V2TestData.POLICE).marks());

        V2TestData.tick(engine, 11,
                V2TestData.police(11, 0, 0, null, false),
                V2TestData.thief(11, 0, 0, false));
        assertEquals(Protection.NORMAL, engine.viewFor(V2TestData.THIEF).thief().protection());
        assertEquals(0, engine.viewFor(V2TestData.THIEF).thief().protectionRemainingTicks());
    }

    @Test
    public void wallBlocksTheBeamWithoutMovingTheActors() {
        MatchEngine engine = V2TestData.factory().create(
                V2TestMaps.open(List.of(new WallCell(10, 6))),
                V2TestRules.fast(), V2TestData.init());
        for (long i = 1; i <= 6; i++) {
            V2TestData.tick(engine, i,
                    V2TestData.police(i, 1, 0, null, false),
                    V2TestData.thief(i, 0, 0, false));
        }
        assertNull(engine.viewFor(V2TestData.POLICE).opponent());

        V2TestData.tick(engine, 7,
                V2TestData.police(7, 0, 0, null, false),
                V2TestData.thief(7, 0, 0, false));
        assertEquals(0, engine.viewFor(V2TestData.POLICE).marks());
    }

    @Test
    public void thirdValidMarkCaptures() {
        MatchEngine engine = officerInBeam();
        for (long i = 7; i <= 17; i++) {
            StepResult result = V2TestData.tick(engine, i,
                    V2TestData.police(i, 0, 0, null, false),
                    V2TestData.thief(i, 0, 0, false));
            if (i < 17) {
                assertFalse(result.ended());
            }
        }
        StepResult finalStep = engine.step(V2TestData.step(18,
                V2TestData.police(18, 0, 0, null, false),
                V2TestData.thief(18, 0, 0, false)));
        assertTrue(finalStep.ended());
        assertEquals(ResultReason.CAPTURED, finalStep.result().reason);
        assertEquals(3, finalStep.result().marks);
    }

    @Test
    public void thiefSeesPoliceInsideTheWideViewRectangle() {
        MatchEngine engine = officerInBeam();
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertNotNull(thief.opponent());
        assertEquals(0.0, thief.opponent().aimRad(), 1e-9);
        assertNotNull(thief.opponent().beam());
    }

    @Test
    public void timeoutWinsBeforeOtherOutcomesAndIsIdempotent() {
        MatchEngine engine = V2TestData.factory().create(V2TestMaps.open(),
                V2TestRules.duration(3), V2TestData.init());
        for (long i = 1; i <= 2; i++) {
            assertFalse(V2TestData.tick(engine, i,
                    V2TestData.police(i, 0, 0, null, false),
                    V2TestData.thief(i, 0, 0, false)).ended());
        }
        StepResult ended = V2TestData.tick(engine, 3,
                V2TestData.police(3, 0, 0, null, false),
                V2TestData.thief(3, 0, 0, false));
        assertTrue(ended.ended());
        assertEquals(ResultReason.TIMEOUT, ended.result().reason);
        assertEquals(3, ended.tick());

        StepResult again = V2TestData.tick(engine, 4);
        assertTrue(again.ended());
        assertEquals(3, again.tick());
        assertTrue(again.actionResults().isEmpty());
        assertTrue(again.events().isEmpty());
    }
}
