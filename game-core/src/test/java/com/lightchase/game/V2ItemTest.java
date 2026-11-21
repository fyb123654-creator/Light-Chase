package com.lightchase.game;

import com.lightchase.contracts.Protection;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.v2.ActionResult;
import com.lightchase.contracts.v2.EffectType;
import com.lightchase.contracts.v2.EffectView;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerActionResult;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.RuleConfig;
import com.lightchase.contracts.v2.StepResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2ItemTest {

    private static MatchEngine lab() {
        return V2TestData.factory().create(V2TestMaps.lab(), V2TestRules.fast(),
                V2TestData.init());
    }

    private static MatchEngine speedLab() {
        return V2TestData.factory().create(V2TestMaps.speedLab(), V2TestRules.fast(),
                V2TestData.init());
    }

    private static StepResult step(MatchEngine engine, long tick, long policeSeq, long thiefSeq,
                                   boolean thiefHeld, List<com.lightchase.contracts.v2.PlayerAction> actions) {
        return engine.step(V2TestData.step(tick,
                List.of(V2TestData.police(policeSeq, 0, 0, null, false),
                        V2TestData.thief(thiefSeq, 0, 0, thiefHeld)),
                actions));
    }

    private static StepResult policeStep(MatchEngine engine, long tick, long policeSeq,
                                         boolean policeHeld,
                                         List<com.lightchase.contracts.v2.PlayerAction> actions) {
        return engine.step(V2TestData.step(tick,
                List.of(V2TestData.police(policeSeq, 0, 0, null, policeHeld),
                        V2TestData.thief(tick, 0, 0, false)),
                actions));
    }

    @Test
    public void twoSlotsAcceptSameTypeAndFullInventoryLeavesGroundItems() {
        MatchEngine engine = lab();
        step(engine, 1, 1, 1, true, List.of());
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals("S1", thief.inventory().get(0).itemId());
        assertNull(thief.inventory().get(1));
        assertEquals(4, thief.groundItems().size());

        step(engine, 2, 2, 2, false, List.of());
        step(engine, 3, 3, 3, true, List.of());
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals("S1", thief.inventory().get(0).itemId());
        assertEquals("S2", thief.inventory().get(1).itemId());
        assertEquals(3, thief.groundItems().size());

        step(engine, 4, 4, 4, false, List.of());
        step(engine, 5, 5, 5, true, List.of());
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(3, thief.groundItems().size());
        assertEquals(com.lightchase.contracts.v2.InteractionBlock.INVENTORY_FULL,
                thief.interaction().blockedReason());
    }

    @Test
    public void usingOneSlotConsumesOnlyThatSlotAndAppliesShield() {
        MatchEngine engine = lab();
        step(engine, 1, 1, 1, true, List.of());
        step(engine, 2, 2, 2, false, List.of());
        step(engine, 3, 3, 3, true, List.of());
        step(engine, 4, 4, 4, false, List.of());

        StepResult result = step(engine, 5, 5, 5, false,
                List.of(V2TestData.use(V2TestData.THIEF, 1, 0, "S1")));
        assertEquals(1, result.actionResults().size());
        ActionResult action = result.actionResults().get(0).result();
        assertTrue(action.success());
        assertEquals("OK", action.code());
        assertEquals(5L, action.appliedTick());

        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertNull(thief.inventory().get(0));
        assertEquals("S2", thief.inventory().get(1).itemId());
        assertEquals(Protection.INVISIBLE, thief.thief().protection());
        assertEquals(10, thief.thief().protectionRemainingTicks());
        assertEquals(15L, thief.thief().invisibleUntilTick());
        assertTrue(hasEffect(thief.effects(), EffectType.SHIELD, 15L));
    }

    @Test
    public void wrongSlotOrInstanceDoesNotConsumeAndStaleIdIsRejected() {
        MatchEngine engine = lab();
        step(engine, 1, 1, 1, true, List.of());
        step(engine, 2, 2, 2, false, List.of());
        step(engine, 3, 3, 3, true, List.of());
        step(engine, 4, 4, 4, false, List.of());

        StepResult mismatch = step(engine, 5, 5, 5, false,
                List.of(V2TestData.use(V2TestData.THIEF, 1, 1, "S1")));
        assertEquals("ITEM_INSTANCE_MISMATCH", mismatch.actionResults().get(0).result().code());
        assertEquals("S2", engine.viewFor(V2TestData.THIEF).inventory().get(1).itemId());

        StepResult firstUse = step(engine, 6, 6, 6, false,
                List.of(V2TestData.use(V2TestData.THIEF, 2, 0, "S1")));
        assertTrue(firstUse.actionResults().get(0).result().success());

        StepResult empty = step(engine, 7, 7, 7, false,
                List.of(V2TestData.use(V2TestData.THIEF, 3, 0, "S1")));
        assertEquals("EMPTY_SLOT", empty.actionResults().get(0).result().code());

        StepResult success = step(engine, 8, 8, 8, false,
                List.of(V2TestData.use(V2TestData.THIEF, 4, 1, "S2")));
        assertTrue(success.actionResults().get(0).result().success());
        assertNull(engine.viewFor(V2TestData.THIEF).inventory().get(1));

        StepResult stale = step(engine, 9, 9, 9, false,
                List.of(V2TestData.use(V2TestData.THIEF, 4, 1, "S1")));
        assertEquals("STALE_ACTION", stale.actionResults().get(0).result().code());
    }

    @Test
    public void duplicateActionReturnsCachedResultWithoutConsumingAgain() {
        MatchEngine engine = lab();
        step(engine, 1, 1, 1, true, List.of());
        step(engine, 2, 2, 2, false, List.of());
        StepResult first = step(engine, 3, 3, 3, false,
                List.of(V2TestData.use(V2TestData.THIEF, 1, 0, "S1")));
        assertTrue(first.actionResults().get(0).result().success());

        StepResult duplicate = step(engine, 4, 4, 4, false,
                List.of(V2TestData.use(V2TestData.THIEF, 1, 0, "S1")));
        assertTrue(duplicate.actionResults().get(0).result().success());
        assertEquals("OK", duplicate.actionResults().get(0).result().code());
        assertNull(engine.viewFor(V2TestData.THIEF).inventory().get(0));
    }

    @Test
    public void lightBoostChangesReportedBeamAndExpires() {
        MatchEngine engine = lab();
        policeStep(engine, 1, 1, true, List.of());
        policeStep(engine, 2, 2, false, List.of());
        StepResult result = policeStep(engine, 3, 3, false,
                List.of(V2TestData.use(V2TestData.POLICE, 1, 0, "PL")));
        assertEquals("OK", result.actionResults().get(0).result().code());
        PlayerView police = engine.viewFor(V2TestData.POLICE);
        assertEquals(6.0, police.self().beam().range(), 1e-9);
        assertTrue(hasEffect(police.effects(), EffectType.LIGHT_BOOST, 13L));

        StepResult expiry = null;
        for (long tick = 4; tick <= 13; tick++) {
            expiry = step(engine, tick, tick, tick, false, List.of());
        }
        assertTrue(expiry.visibilityChanged());
        assertEquals(4.0, engine.viewFor(V2TestData.POLICE).self().beam().range(), 1e-9);
    }

    @Test
    public void speedBoostChangesMovementDistance() {
        MatchEngine engine = lab();
        policeStep(engine, 1, 1, true, List.of());
        policeStep(engine, 2, 2, false, List.of());
        policeStep(engine, 3, 3, true, List.of());
        policeStep(engine, 4, 4, false, List.of());
        StepResult use = policeStep(engine, 5, 5, false,
                List.of(V2TestData.use(V2TestData.POLICE, 1, 1, "PS")));
        assertEquals("OK", use.actionResults().get(0).result().code());

        engine.step(V2TestData.step(6,
                List.of(V2TestData.police(6, 1, 0, null, false),
                        V2TestData.thief(6, 0, 0, false)), List.of()));
        assertEquals(4.0, engine.viewFor(V2TestData.POLICE).self().x(), 1e-6);
    }

    @Test
    public void speedAndLightCanBeActiveTogether() {
        MatchEngine engine = lab();
        policeStep(engine, 1, 1, true, List.of());
        policeStep(engine, 2, 2, false, List.of());
        policeStep(engine, 3, 3, true, List.of());
        policeStep(engine, 4, 4, false, List.of());
        policeStep(engine, 5, 5, false,
                List.of(V2TestData.use(V2TestData.POLICE, 1, 0, "PL")));
        policeStep(engine, 6, 6, false,
                List.of(V2TestData.use(V2TestData.POLICE, 2, 1, "PS")));

        PlayerView police = engine.viewFor(V2TestData.POLICE);
        assertEquals(6.0, police.self().beam().range(), 1e-9);
        assertTrue(hasEffect(police.effects(), EffectType.LIGHT_BOOST, 15L));
        assertTrue(hasEffect(police.effects(), EffectType.SPEED_BOOST, 16L));
        assertNull(police.inventory().get(0));
        assertNull(police.inventory().get(1));

        engine.step(V2TestData.step(7,
                List.of(V2TestData.police(7, 1, 0, null, false),
                        V2TestData.thief(7, 0, 0, false)), List.of()));
        assertEquals(4.0, engine.viewFor(V2TestData.POLICE).self().x(), 1e-6);
    }

    @Test
    public void sameTypeEffectActiveIsRejectedAndKeepsTheSlot() {
        MatchEngine engine = speedLab();
        step(engine, 1, 1, 1, true, List.of());
        step(engine, 2, 2, 2, false, List.of());
        step(engine, 3, 3, 3, false,
                List.of(V2TestData.use(V2TestData.THIEF, 1, 0, "T1")));
        step(engine, 4, 4, 4, true, List.of());
        step(engine, 5, 5, 5, false, List.of());

        StepResult rejected = step(engine, 6, 6, 6, false,
                List.of(V2TestData.use(V2TestData.THIEF, 2, 0, "T2")));
        assertEquals("EFFECT_ACTIVE", rejected.actionResults().get(0).result().code());
        assertEquals("T2", engine.viewFor(V2TestData.THIEF).inventory().get(0).itemId());
    }

    private static boolean hasEffect(List<EffectView> effects, EffectType type, long until) {
        for (EffectView effect : effects) {
            if (effect.type() == type && effect.untilTick() == until) return true;
        }
        return false;
    }
}
