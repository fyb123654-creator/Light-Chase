package com.lightchase.game;

import com.lightchase.contracts.Protection;
import com.lightchase.contracts.v2.ActionResult;
import com.lightchase.contracts.v2.ItemInstance;
import com.lightchase.contracts.v2.ItemType;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerAction;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.Position;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2NewMatchTest {

    @Test
    public void aNewEngineStartsWithNoneOfTheOldInventoryOrPortals() {
        MatchEngine old = V2TestData.factory().create(V2TestMaps.lab(), V2TestRules.fast(),
                V2TestData.init());
        old.step(V2TestData.step(1, List.of(
                V2TestData.police(1, 0, 0, null, false),
                V2TestData.thief(1, 0, 0, true)), List.of()));
        assertTrue(old.viewFor(V2TestData.THIEF).inventory().get(0) != null);

        MatchEngine fresh = V2TestData.factory().create(V2TestMaps.lab(), V2TestRules.fast(),
                V2TestData.init());
        PlayerView thief = fresh.viewFor(V2TestData.THIEF);
        PlayerView police = fresh.viewFor(V2TestData.POLICE);
        assertEquals(0L, thief.tick());
        assertNull(thief.inventory().get(0));
        assertNull(thief.inventory().get(1));
        assertNull(thief.thief().card());
        assertEquals(Protection.NORMAL, thief.thief().protection());
        assertTrue(thief.effects().isEmpty());
        assertTrue(thief.transits().isEmpty());
        assertEquals(5, thief.groundItems().size());
        assertEquals(0L, thief.portals().get(0).revision());
        assertEquals(0L, police.portals().get(1).revision());
    }

    @Test
    public void actionFailuresUseTheContractedCodesAndNeverConsume() {
        V2MatchState state = new V2MatchState(V2TestMaps.open(), V2TestRules.fast(),
                V2TestData.init());
        V2ActorState thief = state.actors.get(V2TestData.THIEF);
        thief.inventory[0] = new ItemInstance("L", ItemType.LIGHT_BOOST);
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, false)), state.actors);
        List<PlayerAction> forbidden = List.of(
                V2TestData.use(V2TestData.THIEF, 1, 0, "L"));
        state.validateActions(forbidden);
        state.processActions(forbidden, plan);
        assertEquals("ROLE_FORBIDDEN", state.takeActionResults().get(0).result().code());
        assertEquals("L", thief.inventory[0].itemId());

        thief.inventory[0] = new ItemInstance("S1", ItemType.SHIELD);
        state.transit = new V2TransitState("T1", "P1", V2TestData.THIEF,
                new Position(12.5, 6.5), new Position(1.5, 1.5), 0, 10);
        state.tick = 1;
        List<PlayerAction> inTransit = List.of(
                V2TestData.use(V2TestData.THIEF, 2, 0, "S1"));
        state.validateActions(inTransit);
        state.processActions(inTransit, plan);
        ActionResult result = state.takeActionResults().get(0).result();
        assertEquals("IN_TRANSIT", result.code());
        assertEquals("S1", thief.inventory[0].itemId());
    }

    @Test
    public void aQuietStepDoesNotReportVisibilityChanged() {
        MatchEngine engine = V2TestData.engine();
        var first = V2TestData.tick(engine, 1,
                V2TestData.police(1, 0, 0, null, false),
                V2TestData.thief(1, 0, 0, false));
        assertTrue(first.visibilityChanged());
        var second = V2TestData.tick(engine, 2,
                V2TestData.police(2, 0, 0, null, false),
                V2TestData.thief(2, 0, 0, false));
        org.junit.Assert.assertFalse(second.visibilityChanged());
    }

    @Test
    public void actionCacheEvictsOldIdsAndReportsStaleAction() {
        V2MatchState state = new V2MatchState(V2TestMaps.open(), V2TestRules.fast(),
                V2TestData.init());
        state.tick = 1;
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, false)), state.actors);
        for (long id = 1; id <= 129; id++) {
            List<PlayerAction> actions = List.of(
                    V2TestData.use(V2TestData.THIEF, id, 0, "missing"));
            state.validateActions(actions);
            state.processActions(actions, plan);
            assertEquals("EMPTY_SLOT", state.takeActionResults().get(0).result().code());
        }
        List<PlayerAction> old = List.of(
                V2TestData.use(V2TestData.THIEF, 1, 0, "missing"));
        state.validateActions(old);
        state.processActions(old, plan);
        assertEquals("STALE_ACTION", state.takeActionResults().get(0).result().code());
    }

    @Test
    public void matchEndedIsReportedWhenAProcessedActionArrivesAfterTheResult() {
        V2MatchState state = new V2MatchState(V2TestMaps.open(), V2TestRules.fast(),
                V2TestData.init());
        state.tick = 3;
        state.result = new com.lightchase.contracts.MatchResult("result", "m-v2", null,
                com.lightchase.contracts.ResultReason.SERVER_ABORTED, null, 0, 0, 0, 3);
        V2ActorState thief = state.actors.get(V2TestData.THIEF);
        thief.inventory[0] = new ItemInstance("S1", ItemType.SHIELD);
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, false)), state.actors);
        List<PlayerAction> actions = List.of(
                V2TestData.use(V2TestData.THIEF, 1, 0, "S1"));
        state.validateActions(actions);
        state.processActions(actions, plan);
        assertEquals("MATCH_ENDED", state.takeActionResults().get(0).result().code());
        assertEquals("S1", thief.inventory[0].itemId());
    }
}
