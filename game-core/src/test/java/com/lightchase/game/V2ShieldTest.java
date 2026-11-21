package com.lightchase.game;

import com.lightchase.contracts.Protection;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.v2.ActionResult;
import com.lightchase.contracts.v2.EffectType;
import com.lightchase.contracts.v2.EffectView;
import com.lightchase.contracts.v2.InteractionTarget;
import com.lightchase.contracts.v2.InteractionType;
import com.lightchase.contracts.v2.ItemInstance;
import com.lightchase.contracts.v2.ItemType;
import com.lightchase.contracts.v2.PlayerAction;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.RuleConfig;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2ShieldTest {

    private static V2MatchState state(RuleConfig rules) {
        V2MatchState state = new V2MatchState(V2TestMaps.open(), rules, V2TestData.init());
        state.tick = 300;
        state.marks = 1;
        return state;
    }

    private static V2ActorState thief(V2MatchState state) {
        return state.actors.get(V2TestData.THIEF);
    }

    private static ActionResult useShield(V2MatchState state, long actionId, int slot,
                                         boolean held) {
        V2ActorState thief = thief(state);
        thief.inventory[slot] = new ItemInstance("S" + (slot + 1), ItemType.SHIELD);
        List<PlayerAction> actions = List.of(
                V2TestData.use(V2TestData.THIEF, actionId, slot, "S" + (slot + 1)));
        state.validateActions(actions);
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, held)), state.actors);
        state.processActions(actions, plan);
        return state.takeActionResults().get(0).result();
    }

    @Test
    public void shieldRefreshesToTheLongerRemainingInvisibilityAndKeepsImmuneDeadline() {
        V2MatchState state = state(V2TestRules.shield(90, 45, 30));
        state.invisibleUntilTick = 450;
        state.markImmuneUntilTick = 420;

        ActionResult result = useShield(state, 1, 0, false);

        assertTrue(result.success());
        assertEquals(450L, state.invisibleUntilTick);
        assertEquals(420L, state.markImmuneUntilTick);
        assertEquals(1, state.marks);
        PlayerView view = new V2ViewProjector(state).project(V2TestData.THIEF);
        assertEquals(Protection.INVISIBLE, view.thief().protection());
        assertEquals(150, view.thief().protectionRemainingTicks());
        assertNull(view.inventory().get(0));
        assertTrue(hasEffect(view.effects(), EffectType.SHIELD, 450L));
    }

    @Test
    public void shieldExtendsShorterInvisibilityWithoutAddingImmuneTail() {
        V2MatchState state = state(V2TestRules.shield(90, 45, 30));
        state.invisibleUntilTick = 310;
        state.markImmuneUntilTick = 330;
        state.beamProgress = 1;

        useShield(state, 1, 0, false);

        assertEquals(390L, state.invisibleUntilTick);
        assertEquals(330L, state.markImmuneUntilTick);
        assertEquals(0, state.beamProgress);
    }

    @Test
    public void shieldDuringImmuneDoesNotExtendTheOriginalImmuneTail() {
        V2MatchState state = state(V2TestRules.shield(90, 45, 30));
        state.invisibleUntilTick = 300;
        state.markImmuneUntilTick = 400;
        assertEquals(Protection.IMMUNE, state.protection());

        useShield(state, 1, 0, false);

        assertEquals(390L, state.invisibleUntilTick);
        assertEquals(400L, state.markImmuneUntilTick);
        assertEquals(Protection.INVISIBLE, state.protection());
        state.tick = 390;
        assertEquals(Protection.IMMUNE, state.protection());
        state.tick = 400;
        assertEquals(Protection.NORMAL, state.protection());
    }

    @Test
    public void shieldCancelsInteractionAndConsumesOnlyTheSelectedSlot() {
        V2MatchState state = state(V2TestRules.shield(90, 45, 30));
        V2ActorState thief = thief(state);
        thief.inventory[1] = new ItemInstance("S2", ItemType.SHIELD);
        thief.interaction.active = new InteractionTarget(InteractionType.PICKUP_ITEM, "TS", null);
        thief.interaction.progressTicks = 2;
        thief.interaction.requiresRelease = false;

        ActionResult result = useShield(state, 1, 0, true);

        assertTrue(result.success());
        assertNull(thief.inventory[0]);
        assertEquals("S2", thief.inventory[1].itemId());
        assertNull(thief.interaction.active);
        assertEquals(0, thief.interaction.progressTicks);
        assertTrue(thief.interaction.requiresRelease);
    }

    @Test
    public void secondShieldKeepsTheMaximumDeadline() {
        V2MatchState state = state(V2TestRules.shield(90, 45, 30));
        state.invisibleUntilTick = 450;
        state.markImmuneUntilTick = 420;

        useShield(state, 1, 0, false);
        state.tick = 350;
        useShield(state, 2, 1, false);

        assertEquals(450L, state.invisibleUntilTick);
        assertEquals(420L, state.markImmuneUntilTick);
        assertNull(thief(state).inventory[0]);
        assertNull(thief(state).inventory[1]);
        PlayerView view = new V2ViewProjector(state).project(V2TestData.THIEF);
        assertTrue(hasEffect(view.effects(), EffectType.SHIELD, 450L));
    }

    @Test
    public void shieldEqualInMagnitudeLeavesTheDeadlineUnchanged() {
        V2MatchState state = state(V2TestRules.shield(90, 45, 30));
        state.invisibleUntilTick = 390;
        state.markImmuneUntilTick = 330;

        useShield(state, 1, 0, false);

        assertEquals(390L, state.invisibleUntilTick);
        assertEquals(330L, state.markImmuneUntilTick);
        assertEquals(Protection.INVISIBLE, state.protection());
        state.tick = 390;
        assertEquals(Protection.NORMAL, state.protection());
    }

    @Test
    public void shieldEffectDisappearsWhenInvisibilityEnds() {
        V2MatchState state = state(V2TestRules.shield(90, 45, 30));
        useShield(state, 1, 0, false);
        state.tick = 451;
        PlayerView view = new V2ViewProjector(state).project(V2TestData.THIEF);
        assertEquals(Protection.NORMAL, view.thief().protection());
        assertFalse(hasEffect(view.effects(), EffectType.SHIELD, 390L));
        assertTrue(view.effects().isEmpty());
    }

    private static boolean hasEffect(List<EffectView> effects, EffectType type, long until) {
        for (EffectView effect : effects) {
            if (effect.type() == type && effect.untilTick() == until) return true;
        }
        return false;
    }
}
