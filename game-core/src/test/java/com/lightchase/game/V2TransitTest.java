package com.lightchase.game;

import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.Motion;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.StepResult;
import com.lightchase.contracts.v2.TransitView;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2TransitTest {

    private static MatchEngine cardEngine() {
        return V2TestData.factory().create(V2TestMaps.cardLab(), V2TestRules.fast(),
                V2TestData.init());
    }

    private static StepResult step(MatchEngine engine, long tick, boolean held) {
        return engine.step(V2TestData.step(tick,
                List.of(V2TestData.police(tick, 0, 0, null, false),
                        V2TestData.thief(tick, 0, 0, held)),
                List.of()));
    }

    private static MatchEngine inTransit() {
        MatchEngine engine = cardEngine();
        step(engine, 1, true);
        step(engine, 2, false);
        step(engine, 3, true);
        step(engine, 4, false);
        StepResult enter = step(engine, 5, true);
        assertEquals(1, enter.events().size());
        return engine;
    }

    @Test
    public void transitStartsAtEntryAndMovesAlongTheStraightLine() {
        MatchEngine engine = inTransit();
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(Motion.TRANSIT, thief.self().motion());
        assertEquals(12.5, thief.self().x(), 1e-9);
        assertEquals(6.5, thief.self().y(), 1e-9);
        TransitView transit = thief.transits().get(0);
        assertEquals("T1", transit.transitId());
        assertEquals("P1", transit.portalId());
        assertEquals(5L, transit.startTick());
        assertEquals(10L, transit.endTick());

        StepResult moving = engine.step(V2TestData.step(6,
                List.of(V2TestData.police(6, 0, 0, null, false),
                        V2TestData.thief(6, 1, 0, true)), List.of()));
        assertTrue(moving.events().isEmpty());
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(10.3, thief.self().x(), 1e-9);
        assertEquals(5.5, thief.self().y(), 1e-9);

        PlayerView police = engine.viewFor(V2TestData.POLICE);
        assertNull(police.opponent());
        assertEquals(1, police.transits().size());
        assertEquals(thief.transits().get(0), police.transits().get(0));
    }

    @Test
    public void landingClearsOldInputAndRequiresRelease() {
        MatchEngine engine = inTransit();
        for (long tick = 6; tick <= 10; tick++) {
            step(engine, tick, true);
        }
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(Motion.GROUND, thief.self().motion());
        assertEquals(1.5, thief.self().x(), 1e-9);
        assertEquals(1.5, thief.self().y(), 1e-9);
        assertTrue(thief.transits().isEmpty());
        assertTrue(thief.interaction().requiresRelease());

        step(engine, 11, false);
        thief = engine.viewFor(V2TestData.THIEF);
        assertFalse(thief.interaction().requiresRelease());

        engine.step(V2TestData.step(12,
                List.of(V2TestData.police(12, 0, 0, null, false),
                        V2TestData.thief(12, 1, 0, false)), List.of()));
        assertEquals(2.5, engine.viewFor(V2TestData.THIEF).self().x(), 1e-6);
    }

    @Test
    public void timeoutDuringTransitClearsThePublicTransit() {
        MatchEngine engine = V2TestData.factory().create(V2TestMaps.cardLab(),
                V2TestRules.duration(8), V2TestData.init());
        step(engine, 1, true);
        step(engine, 2, false);
        step(engine, 3, true);
        step(engine, 4, false);
        step(engine, 5, true);
        step(engine, 6, false);
        step(engine, 7, false);
        StepResult ended = step(engine, 8, false);

        assertTrue(ended.ended());
        assertEquals(ResultReason.TIMEOUT, ended.result().reason);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertTrue(thief.transits().isEmpty());
        assertEquals(0, engine.viewFor(V2TestData.POLICE).transits().size());
    }

    @Test
    public void enterPortalRejectsASecondEntryWhileAlreadyInTransit() {
        MatchEngine engine = inTransit();
        PlayerView view = engine.viewFor(V2TestData.THIEF);
        assertEquals(1, view.transits().size());
        assertEquals(com.lightchase.contracts.v2.InteractionBlock.IN_TRANSIT,
                view.interaction().blockedReason());
    }

    @Test
    public void taskMaterialIsCarriedThroughTheTransitAndStillHeldOnLanding() {
        MatchEngine engine = V2TestData.factory().create(V2TestMaps.carryLab(),
                V2TestRules.fast(), V2TestData.init());
        step(engine, 1, true);   // C1
        step(engine, 2, false);
        step(engine, 3, true);   // M1
        step(engine, 4, true);
        step(engine, 5, false);
        step(engine, 6, true);   // unlock P1
        step(engine, 7, false);
        step(engine, 8, true);   // enter P1

        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals("M1", thief.thief().carriedMaterialId());
        assertEquals(1, thief.transits().size());

        for (long tick = 9; tick <= 13; tick++) {
            step(engine, tick, false);
        }
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals("M1", thief.thief().carriedMaterialId());
        assertTrue(thief.transits().isEmpty());
        assertEquals(1.5, thief.self().x(), 1e-9);
        assertEquals(2, thief.thief().groundMaterials().size());
    }

    @Test
    public void abortDuringTransitClearsThePublicTransit() {
        MatchEngine engine = inTransit();
        var result = engine.abort();
        assertEquals(ResultReason.SERVER_ABORTED, result.reason);
        assertTrue(engine.viewFor(V2TestData.THIEF).transits().isEmpty());
        assertTrue(engine.viewFor(V2TestData.POLICE).transits().isEmpty());
        assertTrue(engine.step(V2TestData.step(6,
                List.of(V2TestData.police(6, 0, 0, null, false),
                        V2TestData.thief(6, 0, 0, false)), List.of())).ended());
    }

    @Test
    public void departureDuringTransitClearsThePublicTransit() {
        MatchEngine engine = inTransit();
        StepResult result = engine.step(V2TestData.step(6,
                List.of(V2TestData.police(6, 0, 0, null, false),
                        V2TestData.thief(6, 0, 0, false)),
                List.of(),
                List.of(V2TestData.departure(V2TestData.THIEF,
                        com.lightchase.contracts.DepartureReason.QUIT))));
        assertTrue(result.ended());
        assertEquals(ResultReason.QUIT, result.result().reason);
        assertEquals(com.lightchase.contracts.Role.POLICE, result.result().winner);
        assertTrue(engine.viewFor(V2TestData.THIEF).transits().isEmpty());
    }
}
