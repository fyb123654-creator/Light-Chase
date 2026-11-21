package com.lightchase.game;

import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.StepResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2FullMatchTest {

    private static StepResult step(MatchEngine engine, long tick, boolean held) {
        return engine.step(V2TestData.step(tick,
                List.of(V2TestData.police(tick, 0, 0, null, false),
                        V2TestData.thief(tick, 0, 0, held)),
                List.of()));
    }

    @Test
    public void fullRouteWithThreeDeliveriesUnlockTransitAndEscape() {
        MatchEngine engine = V2TestData.factory().create(V2TestMaps.fullMatch(),
                V2TestRules.fast(), V2TestData.init());

        step(engine, 1, true);   // card
        step(engine, 2, false);
        step(engine, 3, true);   // M1
        step(engine, 4, true);
        step(engine, 5, false);
        step(engine, 6, true);   // deliver M1
        step(engine, 7, true);
        step(engine, 8, false);
        step(engine, 9, true);   // M2
        step(engine, 10, true);
        step(engine, 11, false);
        step(engine, 12, true);  // deliver M2
        step(engine, 13, true);
        step(engine, 14, false);
        step(engine, 15, true);  // M3
        step(engine, 16, true);
        step(engine, 17, false);
        step(engine, 18, true);  // deliver M3 progress 1
        StepResult thirdDelivery = step(engine, 19, true); // deliver M3 completes
        step(engine, 20, false);

        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(3, thief.deliveredCount());
        assertTrue(thief.exitsOpen());
        assertEquals(0, thief.thief().groundMaterials().size());
        assertEquals("C1", thief.thief().card().cardId());
        assertEquals(3, thief.originStatus().size());
        assertTrue(thirdDelivery.visibilityChanged());

        StepResult unlock = step(engine, 21, true);
        thief = engine.viewFor(V2TestData.THIEF);
        assertNull(thief.thief().card());
        assertTrue(thief.portals().stream()
                .filter(p -> p.portalId().equals("P1")).findFirst().orElseThrow().unlocked());
        assertTrue(unlock.visibilityChanged());

        step(engine, 22, false);
        StepResult enter = step(engine, 23, true);
        assertEquals(1, enter.events().size());
        assertEquals("T1", enter.events().get(0).transitId());
        assertEquals(1, engine.viewFor(V2TestData.THIEF).transits().size());
        assertNull(engine.viewFor(V2TestData.POLICE).opponent());

        for (long tick = 24; tick <= 27; tick++) {
            step(engine, tick, false);
        }
        StepResult landing = step(engine, 28, false);
        assertTrue(landing.visibilityChanged());
        assertEquals(1.5, engine.viewFor(V2TestData.THIEF).self().x(), 1e-9);
        assertEquals(1.5, engine.viewFor(V2TestData.THIEF).self().y(), 1e-9);
        assertTrue(engine.viewFor(V2TestData.THIEF).transits().isEmpty());

        step(engine, 29, true);
        StepResult escaped = step(engine, 30, true);

        assertTrue(escaped.ended());
        assertEquals(ResultReason.ESCAPED, escaped.result().reason);
        assertEquals(Role.THIEF, escaped.result().winner);
        assertEquals(3, escaped.result().deliveredCount);
        assertEquals(30L, escaped.result().finalTick);
        assertEquals(3, engine.viewFor(V2TestData.THIEF).deliveredCount());
        assertNull(engine.viewFor(V2TestData.POLICE).thief());
        assertTrue(engine.viewFor(V2TestData.POLICE).transits().isEmpty());
    }
}
