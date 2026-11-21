package com.lightchase.game;

import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.WallCell;
import com.lightchase.contracts.v2.GroundItem;
import com.lightchase.contracts.v2.ItemType;
import com.lightchase.contracts.v2.MapDefinition;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.RuleConfig;
import com.lightchase.data.V2DataFiles;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public class V2DataTest {

    @Test
    public void bundledProductionConfigCreatesAndStepsAnEngine() {
        V2DataFiles data = V2DataFiles.bundled();
        MatchEngine engine = V2TestData.factory().create(data.map, data.rules,
                V2TestData.init());

        PlayerView police = engine.viewFor(V2TestData.POLICE);
        assertNotNull(police.self().beam());
        assertEquals(2, police.portals().size());
        assertEquals(0L, police.tick());

        engine.step(V2TestData.step(1,
                List.of(V2TestData.police(1, 0, 0, null, false),
                        V2TestData.thief(1, 0, 0, false)), List.of()));
        assertEquals(1L, engine.viewFor(V2TestData.THIEF).tick());
    }

    @Test
    public void rejectsMapVersionOne() {
        MapDefinition base = V2TestMaps.open();
        MapDefinition bad = new MapDefinition(base.mapId(), 1, base.width(), base.height(),
                base.walls(), base.spawns(), base.materials(), base.deliveries(), base.exits(),
                base.itemSpawns(), base.cardSpawns(), base.portals());
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(bad, V2TestRules.fast(), V2TestData.init()));
    }

    @Test
    public void rejectsAnItemWithTheWrongOwnerRole() {
        MapDefinition base = V2TestMaps.open();
        List<GroundItem> items = new ArrayList<>(base.itemSpawns());
        items.add(new GroundItem("BAD", ItemType.LIGHT_BOOST, Role.THIEF, 3.5, 3.5));
        MapDefinition bad = new MapDefinition(base.mapId(), base.mapVersion(), base.width(),
                base.height(), base.walls(), base.spawns(), base.materials(), base.deliveries(),
                base.exits(), items, base.cardSpawns(), base.portals());
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(bad, V2TestRules.fast(), V2TestData.init()));
    }

    @Test
    public void rejectsADuplicateInteractiveId() {
        MapDefinition base = V2TestMaps.open();
        List<GroundItem> items = new ArrayList<>(base.itemSpawns());
        GroundItem first = items.get(0);
        items.add(new GroundItem(first.itemId(), first.type(), first.ownerRole(),
                first.x(), first.y()));
        MapDefinition bad = new MapDefinition(base.mapId(), base.mapVersion(), base.width(),
                base.height(), base.walls(), base.spawns(), base.materials(), base.deliveries(),
                base.exits(), items, base.cardSpawns(), base.portals());
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(bad, V2TestRules.fast(), V2TestData.init()));
    }

    @Test
    public void rejectsAnIsolatedGroundItem() {
        List<WallCell> walls = List.of(
                new WallCell(9, 4), new WallCell(10, 4), new WallCell(11, 4),
                new WallCell(9, 5), new WallCell(11, 5),
                new WallCell(9, 6), new WallCell(10, 6), new WallCell(11, 6));
        MapDefinition bad = V2TestMaps.open(walls);
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(bad, V2TestRules.fast(), V2TestData.init()));
    }

    @Test
    public void rejectsInvalidRuleValues() {
        RuleConfig base = V2TestRules.fast();
        RuleConfig bad = new RuleConfig(base.speed(), base.viewHeight(), base.viewAspect(),
                base.beamRange(), base.beamAngleDeg(), base.markTicks(), base.invisibleTicks(),
                base.immuneTicks(), base.interactRange(), base.interactTicks(), base.maxMarks(),
                base.durationTicks(), base.tickRate(), base.countdownTicks(), 0.5,
                base.speedBoostMultiplier(), base.lightBoostMultiplier(), base.speedBoostTicks(),
                base.lightBoostTicks(), base.shieldTicks(), base.transitTicks(),
                base.itemPickupTicks(), base.cardPickupTicks(), base.portalUnlockTicks(),
                base.portalEnterTicks());
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(V2TestMaps.open(), bad, V2TestData.init()));

        RuleConfig badMultiplier = new RuleConfig(base.speed(), base.viewHeight(),
                base.viewAspect(), base.beamRange(), base.beamAngleDeg(), base.markTicks(),
                base.invisibleTicks(), base.immuneTicks(), base.interactRange(), base.interactTicks(),
                base.maxMarks(), base.durationTicks(), base.tickRate(), base.countdownTicks(),
                base.radius(), 1.0, base.lightBoostMultiplier(), base.speedBoostTicks(),
                base.lightBoostTicks(), base.shieldTicks(), base.transitTicks(),
                base.itemPickupTicks(), base.cardPickupTicks(), base.portalUnlockTicks(),
                base.portalEnterTicks());
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(V2TestMaps.open(), badMultiplier,
                        V2TestData.init()));
    }

    @Test
    public void rejectsDuplicateOrWrongPortalMappings() {
        MapDefinition base = V2TestMaps.open();
        List<com.lightchase.contracts.v2.GroundCard> wrongPortal = List.of(
                new com.lightchase.contracts.v2.GroundCard("C1", "PX", 12.5, 7.5),
                new com.lightchase.contracts.v2.GroundCard("C2", "P2", 2.5, 7.5));
        MapDefinition bad = new MapDefinition(base.mapId(), base.mapVersion(), base.width(),
                base.height(), base.walls(), base.spawns(), base.materials(), base.deliveries(),
                base.exits(), base.itemSpawns(), wrongPortal, base.portals());
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(bad, V2TestRules.fast(), V2TestData.init()));

        List<com.lightchase.contracts.v2.GroundCard> duplicatePortal = List.of(
                new com.lightchase.contracts.v2.GroundCard("C1", "P1", 12.5, 7.5),
                new com.lightchase.contracts.v2.GroundCard("C2", "P1", 2.5, 7.5));
        MapDefinition bad2 = new MapDefinition(base.mapId(), base.mapVersion(), base.width(),
                base.height(), base.walls(), base.spawns(), base.materials(), base.deliveries(),
                base.exits(), base.itemSpawns(), duplicatePortal, base.portals());
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(bad2, V2TestRules.fast(), V2TestData.init()));
    }

    @Test
    public void rejectsDuplicatePortalEndpoints() {
        MapDefinition base = V2TestMaps.open();
        List<com.lightchase.contracts.v2.PortalDefinition> portals = List.of(
                base.portals().get(0),
                new com.lightchase.contracts.v2.PortalDefinition("P2",
                        base.portals().get(0).entry(),
                        new com.lightchase.contracts.v2.Position(14.5, 10.5)));
        MapDefinition bad = new MapDefinition(base.mapId(), base.mapVersion(), base.width(),
                base.height(), base.walls(), base.spawns(), base.materials(), base.deliveries(),
                base.exits(), base.itemSpawns(), base.cardSpawns(), portals);
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(bad, V2TestRules.fast(), V2TestData.init()));
    }

    @Test
    public void rejectsBlankOrDuplicateMatchInit() {
        assertThrows(IllegalArgumentException.class, () -> V2TestData.factory().create(
                V2TestMaps.open(), V2TestRules.fast(), new MatchInit("", "p", "t")));
        assertThrows(IllegalArgumentException.class, () -> V2TestData.factory().create(
                V2TestMaps.open(), V2TestRules.fast(), new MatchInit("m", "p", "p")));
    }

    @Test
    public void rejectsNullMapRulesOrInit() {
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(null, V2TestRules.fast(), V2TestData.init()));
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(V2TestMaps.open(), null, V2TestData.init()));
        assertThrows(IllegalArgumentException.class,
                () -> V2TestData.factory().create(V2TestMaps.open(), V2TestRules.fast(), null));
    }
}
