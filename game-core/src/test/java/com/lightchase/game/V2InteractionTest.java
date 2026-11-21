package com.lightchase.game;

import com.lightchase.contracts.v2.InteractionBlock;
import com.lightchase.contracts.v2.InteractionType;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.Motion;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.PortalState;
import com.lightchase.contracts.v2.StepResult;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class V2InteractionTest {

    private static MatchEngine taskEngine() {
        return V2TestData.factory().create(V2TestMaps.taskLab(), V2TestRules.fast(),
                V2TestData.init());
    }

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

    @Test
    public void taskPickupThenDeliveryRequiresSeparateHoldAndRelease() {
        MatchEngine engine = taskEngine();

        step(engine, 1, true);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(InteractionType.PICKUP, thief.interaction().active().type());
        assertEquals(1, thief.interaction().progressTicks());
        assertEquals(2, thief.interaction().requiredTicks());
        assertNull(thief.thief().carriedMaterialId());

        step(engine, 2, true);
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals("M1", thief.thief().carriedMaterialId());
        assertNull(thief.interaction().active());
        assertTrue(thief.interaction().requiresRelease());

        step(engine, 3, false);
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(InteractionType.DELIVER, thief.interaction().candidate().type());
        assertFalse(thief.interaction().requiresRelease());

        step(engine, 4, true);
        step(engine, 5, true);
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(1, thief.deliveredCount());
        assertNull(thief.thief().carriedMaterialId());
        assertEquals(5, thief.groundItems().size());
        assertEquals(2, thief.thief().groundMaterials().size());
    }

    @Test
    public void movementCancelsProgressAndStillRequiresRelease() {
        MatchEngine engine = taskEngine();
        step(engine, 1, true);
        engine.step(V2TestData.step(2,
                List.of(V2TestData.police(2, 0, 0, null, false),
                        V2TestData.thief(2, -1, 0, true)), List.of()));

        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertNull(thief.thief().carriedMaterialId());
        assertNull(thief.interaction().active());
        assertEquals(0, thief.interaction().progressTicks());
        assertTrue(thief.interaction().requiresRelease());
        assertEquals(InteractionBlock.MOVING, thief.interaction().blockedReason());
    }

    @Test
    public void droppingMaterialCancelsInteractionAndLeavesLootAtTheFeet() {
        MatchEngine engine = taskEngine();
        step(engine, 1, true);
        step(engine, 2, true);
        step(engine, 3, false);

        engine.step(V2TestData.step(4,
                List.of(V2TestData.police(4, 0, 0, null, false),
                        V2TestData.command(V2TestData.THIEF, 4, 0, 0, null, false, 1L)),
                List.of()));
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertNull(thief.thief().carriedMaterialId());
        assertEquals(3, thief.thief().groundMaterials().size());
        assertEquals("M1", thief.thief().groundMaterials().get(0).materialId);
        assertTrue(thief.originStatus().get(0).everPicked);
        assertEquals(0, thief.interaction().progressTicks());
    }

    @Test
    public void unlockThenEnterStartsPublicTransitAndLandsAtExit() {
        MatchEngine engine = cardEngine();
        step(engine, 1, true);
        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals("C1", thief.thief().card().cardId());
        assertTrue(thief.interaction().requiresRelease());

        step(engine, 2, false);
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(InteractionType.UNLOCK_PORTAL, thief.interaction().candidate().type());

        StepResult unlock = step(engine, 3, true);
        thief = engine.viewFor(V2TestData.THIEF);
        assertNull(thief.thief().card());
        PortalState p1 = thief.portals().stream()
                .filter(p -> p.portalId().equals("P1")).findFirst().orElseThrow();
        assertTrue(p1.unlocked());
        assertEquals(1L, p1.revision());
        assertEquals(InteractionBlock.RELEASE_REQUIRED, thief.interaction().blockedReason());
        assertTrue(unlock.visibilityChanged());

        step(engine, 4, false);
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(InteractionType.ENTER_PORTAL, thief.interaction().candidate().type());

        StepResult enter = step(engine, 5, true);
        assertEquals(1, enter.events().size());
        assertEquals("T1", enter.events().get(0).transitId());
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(Motion.TRANSIT, thief.self().motion());
        assertEquals(1, thief.transits().size());
        assertEquals("T1", thief.transits().get(0).transitId());
        assertNull(engine.viewFor(V2TestData.POLICE).opponent());

        for (long tick = 6; tick <= 10; tick++) {
            step(engine, tick, false);
        }
        thief = engine.viewFor(V2TestData.THIEF);
        assertEquals(Motion.GROUND, thief.self().motion());
        assertEquals(1.5, thief.self().x(), 1e-9);
        assertEquals(1.5, thief.self().y(), 1e-9);
        assertTrue(thief.transits().isEmpty());
    }

    @Test
    public void wrongCardDoesNotConsumeOrUnlock() {
        MatchEngine engine = V2TestData.factory().create(V2TestMaps.wrongCardLab(),
                V2TestRules.fast(), V2TestData.init());
        step(engine, 1, true);
        step(engine, 2, false);
        step(engine, 3, true);

        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals("C2", thief.thief().card().cardId());
        assertFalse(thief.portals().stream()
                .filter(p -> p.portalId().equals("P1")).findFirst().orElseThrow().unlocked());
        assertEquals(InteractionBlock.WRONG_CARD, thief.interaction().blockedReason());
    }

    @Test
    public void cardSlotFullBlocksSecondCardButKeepsItOnTheGround() {
        MatchEngine engine = V2TestData.factory().create(V2TestMaps.fullCardLab(),
                V2TestRules.fast(), V2TestData.init());
        step(engine, 1, true);
        step(engine, 2, false);

        PlayerView thief = engine.viewFor(V2TestData.THIEF);
        assertEquals("C1", thief.thief().card().cardId());
        assertEquals(1, thief.groundCards().size());
        assertEquals(InteractionBlock.CARD_SLOT_FULL, thief.interaction().blockedReason());
    }

    @Test
    public void policeCannotPickUpACard() {
        V2MatchState state = new V2MatchState(V2TestMaps.cardLab(), V2TestRules.fast(),
                V2TestData.init());
        state.police.x = 12.5;
        state.police.y = 6.5;
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)), state.actors);
        state.updateInteractions(plan);
        PlayerView police = new V2ViewProjector(state).project(V2TestData.POLICE);
        assertEquals(InteractionBlock.ROLE_FORBIDDEN, police.interaction().blockedReason());
        assertNull(police.interaction().candidate());
    }

    @Test
    public void invisibleBlocksAllInteractionButImmuneAllowsIt() {
        V2MatchState invisible = new V2MatchState(V2TestMaps.taskLab(), V2TestRules.fast(),
                V2TestData.init());
        invisible.tick = 50;
        invisible.invisibleUntilTick = 100;
        invisible.markImmuneUntilTick = 200;
        V2InputMerger.Plan press = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, true)), invisible.actors);
        invisible.updateInteractions(press);
        PlayerView thief = new V2ViewProjector(invisible).project(V2TestData.THIEF);
        assertEquals(InteractionBlock.INVISIBLE, thief.interaction().blockedReason());
        assertNull(thief.interaction().candidate());
        assertNull(thief.interaction().active());

        V2MatchState immune = new V2MatchState(V2TestMaps.taskLab(), V2TestRules.fast(),
                V2TestData.init());
        immune.tick = 150;
        immune.invisibleUntilTick = 100;
        immune.markImmuneUntilTick = 200;
        V2InputMerger.Plan immunePress = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, true)), immune.actors);
        immune.updateInteractions(immunePress);
        thief = new V2ViewProjector(immune).project(V2TestData.THIEF);
        assertEquals(InteractionBlock.NONE, thief.interaction().blockedReason());
        assertEquals(1, thief.interaction().progressTicks());
        assertEquals(InteractionType.PICKUP, thief.interaction().active().type());
    }

    @Test
    public void aTargetThatDisappearsMidHoldReportsTargetGone() {
        V2MatchState state = new V2MatchState(V2TestMaps.taskLab(), V2TestRules.fast(),
                V2TestData.init());
        state.tick = 1;
        V2InputMerger.Plan press = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, true)), state.actors);
        state.updateInteractions(press);
        state.materials.get("M1").status = MaterialState.Status.DELIVERED;
        state.tick = 2;
        state.updateInteractions(press);
        PlayerView thief = new V2ViewProjector(state).project(V2TestData.THIEF);
        assertEquals(InteractionBlock.TARGET_GONE, thief.interaction().blockedReason());
        assertNull(thief.interaction().active());
    }

    @Test
    public void fullItemSlotsDoNotBlockCardPickup() {
        V2MatchState state = new V2MatchState(V2TestMaps.fullCardLab(), V2TestRules.fast(),
                V2TestData.init());
        V2ActorState thief = state.actors.get(V2TestData.THIEF);
        thief.inventory[0] = new com.lightchase.contracts.v2.ItemInstance("S1",
                com.lightchase.contracts.v2.ItemType.SHIELD);
        thief.inventory[1] = new com.lightchase.contracts.v2.ItemInstance("S2",
                com.lightchase.contracts.v2.ItemType.SHIELD);
        state.tick = 1;
        V2InputMerger.Plan press = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, true)), state.actors);
        state.updateInteractions(press);
        PlayerView view = new V2ViewProjector(state).project(V2TestData.THIEF);
        assertEquals("C1", view.thief().card().cardId());
        assertEquals("S1", view.inventory().get(0).itemId());
    }

    @Test
    public void beingMarkedCancelsAnInteraction() {
        V2MatchState state = new V2MatchState(V2TestMaps.taskLab(), V2TestRules.fast(),
                V2TestData.init());
        state.tick = 1;
        V2InputMerger.Plan press = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, true)), state.actors);
        state.updateInteractions(press);
        assertEquals(1, state.actors.get(V2TestData.THIEF).interaction.progressTicks);

        state.tick = 2;
        state.markedThisStep = true;
        state.updateInteractions(press);
        PlayerView view = new V2ViewProjector(state).project(V2TestData.THIEF);
        assertNull(view.interaction().active());
        assertEquals(0, view.interaction().progressTicks());
        assertEquals(InteractionBlock.RELEASE_REQUIRED, view.interaction().blockedReason());
    }

    @Test
    public void dropDuringTransitConsumesThePulseButKeepsTheMaterial() {
        V2MatchState state = new V2MatchState(V2TestMaps.carryLab(), V2TestRules.fast(),
                V2TestData.init());
        V2ActorState thief = state.actors.get(V2TestData.THIEF);
        state.carrying = state.materials.get("M1");
        state.materials.get("M1").status = MaterialState.Status.CARRIED;
        state.transit = new V2TransitState("T1", "P1", V2TestData.THIEF,
                new com.lightchase.contracts.v2.Position(12.5, 6.5),
                new com.lightchase.contracts.v2.Position(1.5, 1.5), 0, 10);
        state.tick = 1;
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.command(V2TestData.THIEF, 1, 0, 0, null, false, 1L)),
                state.actors);
        state.applyDrop(plan);
        assertEquals("M1", state.carrying.id);
        assertEquals(1L, thief.lastDropId);
    }

    @Test
    public void dropIsAllowedWhileInvisible() {
        V2MatchState state = new V2MatchState(V2TestMaps.taskLab(), V2TestRules.fast(),
                V2TestData.init());
        V2ActorState thief = state.actors.get(V2TestData.THIEF);
        state.tick = 50;
        state.invisibleUntilTick = 100;
        state.markImmuneUntilTick = 130;
        state.carrying = state.materials.get("M1");
        state.materials.get("M1").status = MaterialState.Status.CARRIED;
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.command(V2TestData.THIEF, 1, 0, 0, null, false, 1L)),
                state.actors);
        state.applyDrop(plan);
        assertNull(state.carrying);
        assertEquals(MaterialState.Status.GROUND, state.materials.get("M1").status);
        assertEquals(12.5, state.materials.get("M1").x, 1e-9);
    }

    @Test
    public void noCardAtALockedPortalReportsCardRequired() {
        V2MatchState state = new V2MatchState(V2TestMaps.cardLab(), V2TestRules.fast(),
                V2TestData.init());
        state.groundCards.remove("C1");
        state.actors.get(V2TestData.THIEF).card = null;
        state.tick = 1;
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.thief(1, 0, 0, false)), state.actors);
        state.updateInteractions(plan);
        PlayerView thief = new V2ViewProjector(state).project(V2TestData.THIEF);
        assertEquals(InteractionBlock.CARD_REQUIRED, thief.interaction().blockedReason());
        assertNull(thief.interaction().candidate());
    }

    @Test
    public void policeAtAPortalReportsRoleForbidden() {
        V2MatchState state = new V2MatchState(V2TestMaps.cardLab(), V2TestRules.fast(),
                V2TestData.init());
        state.police.x = 12.5;
        state.police.y = 6.5;
        state.tick = 1;
        V2InputMerger.Plan plan = V2InputMerger.merge(
                List.of(V2TestData.police(1, 0, 0, null, false)), state.actors);
        state.updateInteractions(plan);
        PlayerView police = new V2ViewProjector(state).project(V2TestData.POLICE);
        assertEquals(InteractionBlock.ROLE_FORBIDDEN, police.interaction().blockedReason());
    }
}
