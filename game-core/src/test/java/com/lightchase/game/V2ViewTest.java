package com.lightchase.game;

import com.lightchase.contracts.Protection;
import com.lightchase.contracts.v2.EffectType;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerView;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2ViewTest {

    private static StepResultHolder labPair() {
        MatchEngine engine = V2TestData.factory().create(V2TestMaps.lab(), V2TestRules.fast(),
                V2TestData.init());
        engine.step(V2TestData.step(1, List.of(
                V2TestData.police(1, 0, 0, null, false),
                V2TestData.thief(1, 0, 0, true)), List.of()));
        engine.step(V2TestData.step(2, List.of(
                V2TestData.police(2, 0, 0, null, false),
                V2TestData.thief(2, 0, 0, false)), List.of()));
        engine.step(V2TestData.step(3, List.of(
                V2TestData.police(3, 0, 0, null, true),
                V2TestData.thief(3, 0, 0, false)), List.of()));
        return new StepResultHolder(engine);
    }

    private static final class StepResultHolder {
        final MatchEngine engine;

        StepResultHolder(MatchEngine engine) {
            this.engine = engine;
        }
    }

    @Test
    public void publicListsAreIdenticalAndPrivateFieldsStayOnTheirOwner() {
        MatchEngine engine = labPair().engine;
        PlayerView police = engine.viewFor(V2TestData.POLICE);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);

        assertEquals(police.groundItems(), thief.groundItems());
        assertEquals(police.groundCards(), thief.groundCards());
        assertEquals(police.portals(), thief.portals());
        assertEquals(police.originStatus().size(), thief.originStatus().size());
        for (int i = 0; i < police.originStatus().size(); i++) {
            assertEquals(police.originStatus().get(i).materialId,
                    thief.originStatus().get(i).materialId);
            assertEquals(police.originStatus().get(i).everPicked,
                    thief.originStatus().get(i).everPicked);
        }

        assertNull(police.thief());
        assertNotNull(thief.thief());
        assertEquals("PL", police.inventory().get(0).itemId());
        assertEquals("S1", thief.inventory().get(0).itemId());
        assertEquals(0L, police.serverTimeMs());
        assertNull(thief.self().aimRad());
        assertNotNull(police.self().aimRad());
    }

    @Test
    public void ownEffectsDoNotLeakToTheOpponent() {
        MatchEngine engine = labPair().engine;
        engine.step(V2TestData.step(4, List.of(
                V2TestData.police(4, 0, 0, null, false),
                V2TestData.thief(4, 0, 0, false)),
                List.of(V2TestData.use(V2TestData.THIEF, 1, 0, "S1"))));
        engine.step(V2TestData.step(5, List.of(
                V2TestData.police(5, 0, 0, null, false),
                V2TestData.thief(5, 0, 0, false)),
                List.of(V2TestData.use(V2TestData.POLICE, 2, 0, "PL"))));

        PlayerView police = engine.viewFor(V2TestData.POLICE);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(Protection.INVISIBLE, thief.thief().protection());
        assertFalse(hasEffect(police.effects(), EffectType.SHIELD));
        assertTrue(hasEffect(thief.effects(), EffectType.SHIELD));
        assertTrue(hasEffect(police.effects(), EffectType.LIGHT_BOOST));
        assertFalse(hasEffect(thief.effects(), EffectType.LIGHT_BOOST));
    }

    @Test
    public void visibleOpponentCarriesOnlyTheFieldsItsRoleMaySee() {
        MatchEngine engine = V2TestData.engine();
        for (long tick = 1; tick <= 6; tick++) {
            engine.step(V2TestData.step(tick, List.of(
                    V2TestData.police(tick, 1, 0, null, false),
                    V2TestData.thief(tick, 0, 0, false)), List.of()));
        }
        PlayerView police = engine.viewFor(V2TestData.POLICE);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);

        assertNotNull(police.opponent());
        assertEquals(com.lightchase.contracts.Role.THIEF, police.opponent().role());
        assertNull(police.opponent().aimRad());
        assertNull(police.opponent().beam());
        assertNotNull(thief.opponent());
        assertEquals(com.lightchase.contracts.Role.POLICE, thief.opponent().role());
        assertNotNull(thief.opponent().aimRad());
        assertNotNull(thief.opponent().beam());

        engine.step(V2TestData.step(7, List.of(
                V2TestData.police(7, 0, 0, null, false),
                V2TestData.thief(7, 0, 0, false)), List.of()));
        police = engine.viewFor(V2TestData.POLICE);
        thief = engine.viewFor(V2TestData.THIEF);
        assertNull(police.opponent());
        assertNotNull(thief.opponent());
        assertEquals(Protection.INVISIBLE, thief.thief().protection());
    }

    @Test
    public void removedGroundObjectsDisappearFromBothViews() {
        MatchEngine engine = labPair().engine;
        PlayerView police = engine.viewFor(V2TestData.POLICE);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(3, police.groundItems().size());
        assertEquals(3, thief.groundItems().size());
        assertTrue(police.groundItems().stream().noneMatch(i -> i.itemId().equals("S1")));
        assertTrue(thief.groundItems().stream().noneMatch(i -> i.itemId().equals("PL")));
    }

    private static boolean hasEffect(List<com.lightchase.contracts.v2.EffectView> effects,
                                     EffectType type) {
        for (com.lightchase.contracts.v2.EffectView effect : effects) {
            if (effect.type() == type) return true;
        }
        return false;
    }
}
