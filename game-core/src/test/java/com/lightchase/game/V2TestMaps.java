package com.lightchase.game;

import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MaterialPoint;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.WallCell;
import com.lightchase.contracts.v2.GroundCard;
import com.lightchase.contracts.v2.GroundItem;
import com.lightchase.contracts.v2.ItemType;
import com.lightchase.contracts.v2.MapDefinition;
import com.lightchase.contracts.v2.PortalDefinition;
import com.lightchase.contracts.v2.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, fully reachable v2 test maps. Test-only, never production data. */
final class V2TestMaps {

    private V2TestMaps() {
    }

    static MapDefinition open() {
        return open(new ArrayList<>());
    }

    static MapDefinition open(List<WallCell> walls) {
        Map<Role, SpawnPose> spawns = new LinkedHashMap<>();
        spawns.put(Role.POLICE, new SpawnPose(2.5, 6.5, 0.0));
        spawns.put(Role.THIEF, new SpawnPose(12.5, 6.5, Math.PI));

        List<MaterialPoint> materials = List.of(
                new MaterialPoint("M1", 4.5, 3.5, "D1"),
                new MaterialPoint("M2", 4.5, 9.5, "D2"),
                new MaterialPoint("M3", 7.5, 1.5, "D3"));
        List<MapPoint> deliveries = List.of(
                new MapPoint("D1", 11.5, 3.5),
                new MapPoint("D2", 11.5, 9.5),
                new MapPoint("D3", 7.5, 10.5));
        List<MapPoint> exits = List.of(
                new MapPoint("E1", 1.5, 10.5),
                new MapPoint("E2", 14.5, 1.5));
        List<GroundItem> items = List.of(
                new GroundItem("PL", ItemType.LIGHT_BOOST, Role.POLICE, 3.5, 5.5),
                new GroundItem("PS", ItemType.SPEED_BOOST, Role.POLICE, 3.5, 4.5),
                new GroundItem("TS", ItemType.SPEED_BOOST, Role.THIEF, 11.5, 5.5),
                new GroundItem("S1", ItemType.SHIELD, Role.THIEF, 10.5, 5.5),
                new GroundItem("S2", ItemType.SHIELD, Role.THIEF, 9.5, 5.5));
        List<GroundCard> cards = List.of(
                new GroundCard("C1", "P1", 12.5, 7.5),
                new GroundCard("C2", "P2", 2.5, 7.5));
        List<PortalDefinition> portals = List.of(
                new PortalDefinition("P1", new Position(13.5, 6.5), new Position(1.5, 1.5)),
                new PortalDefinition("P2", new Position(1.5, 6.5), new Position(14.5, 10.5)));

        return new MapDefinition("test-v2", 2, 16, 12, walls, spawns,
                materials, deliveries, exits, items, cards, portals);
    }

    /** Items on the spawn points, so a one-tick press can exercise slots and action results. */
    static MapDefinition lab() {
        MapDefinition base = open();
        List<GroundItem> items = List.of(
                new GroundItem("S1", ItemType.SHIELD, Role.THIEF, 12.5, 6.5),
                new GroundItem("S2", ItemType.SHIELD, Role.THIEF, 12.5, 6.5),
                new GroundItem("TS", ItemType.SPEED_BOOST, Role.THIEF, 12.5, 6.5),
                new GroundItem("PL", ItemType.LIGHT_BOOST, Role.POLICE, 2.5, 6.5),
                new GroundItem("PS", ItemType.SPEED_BOOST, Role.POLICE, 2.5, 6.5));
        return new MapDefinition(base.mapId(), base.mapVersion(), base.width(), base.height(),
                base.walls(), base.spawns(), base.materials(), base.deliveries(), base.exits(),
                items, base.cardSpawns(), base.portals());
    }

    /** Two thief speed items on the spawn, for the same-type-effect rejection test. */
    static MapDefinition speedLab() {
        MapDefinition base = open();
        List<GroundItem> items = List.of(
                new GroundItem("T1", ItemType.SPEED_BOOST, Role.THIEF, 12.5, 6.5),
                new GroundItem("T2", ItemType.SPEED_BOOST, Role.THIEF, 12.5, 6.5));
        return new MapDefinition(base.mapId(), base.mapVersion(), base.width(), base.height(),
                base.walls(), base.spawns(), base.materials(), base.deliveries(), base.exits(),
                items, base.cardSpawns(), base.portals());
    }

    /** Material, its delivery point and an exit on the thief spawn. */
    static MapDefinition taskLab() {
        MapDefinition base = open();
        List<MaterialPoint> materials = List.of(
                new MaterialPoint("M1", 12.5, 6.5, "D1"),
                new MaterialPoint("M2", 4.5, 9.5, "D2"),
                new MaterialPoint("M3", 7.5, 1.5, "D3"));
        List<MapPoint> deliveries = List.of(
                new MapPoint("D1", 12.5, 6.5),
                new MapPoint("D2", 11.5, 9.5),
                new MapPoint("D3", 7.5, 10.5));
        List<MapPoint> exits = List.of(
                new MapPoint("E1", 12.5, 6.5),
                new MapPoint("E2", 14.5, 1.5));
        return new MapDefinition(base.mapId(), base.mapVersion(), base.width(), base.height(),
                base.walls(), base.spawns(), materials, deliveries, exits,
                base.itemSpawns(), base.cardSpawns(), base.portals());
    }

    /** A card and its matching portal entry on the thief spawn. */
    static MapDefinition cardLab() {
        MapDefinition base = open();
        List<GroundCard> cards = List.of(
                new GroundCard("C1", "P1", 12.5, 6.5),
                new GroundCard("C2", "P2", 2.5, 7.5));
        List<PortalDefinition> portals = List.of(
                new PortalDefinition("P1", new Position(12.5, 6.5), new Position(1.5, 1.5)),
                new PortalDefinition("P2", new Position(1.5, 6.5), new Position(14.5, 10.5)));
        return new MapDefinition(base.mapId(), base.mapVersion(), base.width(), base.height(),
                base.walls(), base.spawns(), base.materials(), base.deliveries(), base.exits(),
                base.itemSpawns(), cards, portals);
    }

    /** A card for P2 on the thief spawn, but P1's entry is also there. */
    static MapDefinition wrongCardLab() {
        MapDefinition base = open();
        List<GroundCard> cards = List.of(
                new GroundCard("C1", "P1", 2.5, 7.5),
                new GroundCard("C2", "P2", 12.5, 6.5));
        List<PortalDefinition> portals = List.of(
                new PortalDefinition("P1", new Position(12.5, 6.5), new Position(1.5, 1.5)),
                new PortalDefinition("P2", new Position(1.5, 6.5), new Position(14.5, 10.5)));
        return new MapDefinition(base.mapId(), base.mapVersion(), base.width(), base.height(),
                base.walls(), base.spawns(), base.materials(), base.deliveries(), base.exits(),
                base.itemSpawns(), cards, portals);
    }

    /** Both cards on the thief spawn, so a full card slot can be asserted. */
    static MapDefinition fullCardLab() {
        MapDefinition base = open();
        List<GroundCard> cards = List.of(
                new GroundCard("C1", "P1", 12.5, 6.5),
                new GroundCard("C2", "P2", 12.5, 6.5));
        return new MapDefinition(base.mapId(), base.mapVersion(), base.width(), base.height(),
                base.walls(), base.spawns(), base.materials(), base.deliveries(), base.exits(),
                base.itemSpawns(), cards, base.portals());
    }

    /**
     * Compact end-to-end route: card, all three deliveries and the exit are on the thief
     * spawn; P1 goes to the other exit. Used only by the full-match regression.
     */
    static MapDefinition fullMatch() {
        MapDefinition base = open();
        List<MaterialPoint> materials = List.of(
                new MaterialPoint("M1", 12.5, 6.5, "D1"),
                new MaterialPoint("M2", 12.5, 6.5, "D2"),
                new MaterialPoint("M3", 12.5, 6.5, "D3"));
        List<MapPoint> deliveries = List.of(
                new MapPoint("D1", 12.5, 6.5),
                new MapPoint("D2", 12.5, 6.5),
                new MapPoint("D3", 12.5, 6.5));
        List<MapPoint> exits = List.of(
                new MapPoint("E1", 1.5, 1.5),
                new MapPoint("E2", 14.5, 1.5));
        List<GroundCard> cards = List.of(
                new GroundCard("C1", "P1", 12.5, 6.5),
                new GroundCard("C2", "P2", 2.5, 7.5));
        List<PortalDefinition> portals = List.of(
                new PortalDefinition("P1", new Position(12.5, 6.5), new Position(1.5, 1.5)),
                new PortalDefinition("P2", new Position(1.5, 6.5), new Position(14.5, 10.5)));
        return new MapDefinition(base.mapId(), base.mapVersion(), base.width(), base.height(),
                base.walls(), base.spawns(), materials, deliveries, exits,
                List.of(), cards, portals);
    }

    /** A material, card and matching portal entry on the thief spawn; D1 is far away. */
    static MapDefinition carryLab() {
        MapDefinition base = open();
        List<MaterialPoint> materials = List.of(
                new MaterialPoint("M1", 12.5, 6.5, "D1"),
                new MaterialPoint("M2", 4.5, 9.5, "D2"),
                new MaterialPoint("M3", 7.5, 1.5, "D3"));
        List<GroundCard> cards = List.of(
                new GroundCard("C1", "P1", 12.5, 6.5),
                new GroundCard("C2", "P2", 2.5, 7.5));
        List<PortalDefinition> portals = List.of(
                new PortalDefinition("P1", new Position(12.5, 6.5), new Position(1.5, 1.5)),
                new PortalDefinition("P2", new Position(1.5, 6.5), new Position(14.5, 10.5)));
        return new MapDefinition(base.mapId(), base.mapVersion(), base.width(), base.height(),
                base.walls(), base.spawns(), materials, base.deliveries(), base.exits(),
                List.of(), cards, portals);
    }
}

