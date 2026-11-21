package com.lightchase.game;

import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.MaterialPoint;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.WallCell;
import com.lightchase.contracts.geometry.WorldGeometry;
import com.lightchase.contracts.v2.GroundCard;
import com.lightchase.contracts.v2.GroundItem;
import com.lightchase.contracts.v2.ItemType;
import com.lightchase.contracts.v2.MapDefinition;
import com.lightchase.contracts.v2.PortalDefinition;
import com.lightchase.contracts.v2.Position;
import com.lightchase.contracts.v2.RuleConfig;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fails fast on a v2 match configuration. The production JSON loader already performs the
 * V1-shaped checks (24x18, M1-M3/D1-D3/E1-E2); this validator additionally understands the
 * R3 objects and proves that every interactive point can actually be reached by the role that
 * needs it, so a match cannot start with unreachable cards or items.
 */
final class V2ConfigValidator {

    private static final Set<Role> ROLES =
            new LinkedHashSet<>(java.util.Arrays.asList(Role.POLICE, Role.THIEF));

    private V2ConfigValidator() {
    }

    static void validate(MapDefinition map, RuleConfig rules, MatchInit init) {
        validateRules(rules);
        validateInit(init);
        validateMap(map, rules);
    }

    /** Shared geometry still speaks the v1 map type; this adapter keeps one conversion point. */
    static com.lightchase.contracts.MapDefinition baseMap(MapDefinition map) {
        return new com.lightchase.contracts.MapDefinition(map.mapId(), map.mapVersion(),
                map.width(), map.height(), map.walls(), map.spawns(), map.materials(),
                map.deliveries(), map.exits());
    }

    private static void validateInit(MatchInit init) {
        if (init == null) {
            throw new IllegalArgumentException("matchInit: missing");
        }
        if (blank(init.matchId)) {
            throw new IllegalArgumentException("matchInit.matchId: must not be blank");
        }
        if (blank(init.policePlayerId) || blank(init.thiefPlayerId)) {
            throw new IllegalArgumentException("matchInit: both player ids are required");
        }
        if (init.policePlayerId.equals(init.thiefPlayerId)) {
            throw new IllegalArgumentException("matchInit: player ids must differ");
        }
    }

    private static void validateRules(RuleConfig rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules: missing");
        }
        positive(rules.speed(), "rules.speed");
        positive(rules.viewHeight(), "rules.viewHeight");
        positive(rules.viewAspect(), "rules.viewAspect");
        positive(rules.beamRange(), "rules.beamRange");
        positive(rules.beamAngleDeg(), "rules.beamAngleDeg");
        if (rules.beamAngleDeg() >= 180.0) {
            throw new IllegalArgumentException("rules.beamAngleDeg: must be < 180");
        }
        if (rules.markTicks() <= 0) {
            throw new IllegalArgumentException("rules.markTicks: must be > 0");
        }
        if (rules.invisibleTicks() < 0) {
            throw new IllegalArgumentException("rules.invisibleTicks: must be >= 0");
        }
        if (rules.immuneTicks() < 0) {
            throw new IllegalArgumentException("rules.immuneTicks: must be >= 0");
        }
        positive(rules.interactRange(), "rules.interactRange");
        if (rules.interactTicks() <= 0) {
            throw new IllegalArgumentException("rules.interactTicks: must be > 0");
        }
        if (rules.maxMarks() < 1 || rules.maxMarks() > 3) {
            throw new IllegalArgumentException("rules.maxMarks: must be in 1..3");
        }
        if (rules.durationTicks() <= 0 || rules.durationTicks() > 5400) {
            throw new IllegalArgumentException("rules.durationTicks: must be in 1..5400");
        }
        if (rules.tickRate() != 30) {
            throw new IllegalArgumentException("rules.tickRate: must be 30, got " + rules.tickRate());
        }
        if (rules.countdownTicks() < 0) {
            throw new IllegalArgumentException("rules.countdownTicks: must be >= 0");
        }
        positive(rules.radius(), "rules.radius");
        if (rules.radius() >= 0.5) {
            throw new IllegalArgumentException("rules.radius: must be < 0.5");
        }
        multiplier(rules.speedBoostMultiplier(), "rules.speedBoostMultiplier");
        multiplier(rules.lightBoostMultiplier(), "rules.lightBoostMultiplier");
        positiveTicks(rules.speedBoostTicks(), "rules.speedBoostTicks");
        positiveTicks(rules.lightBoostTicks(), "rules.lightBoostTicks");
        positiveTicks(rules.shieldTicks(), "rules.shieldTicks");
        positiveTicks(rules.transitTicks(), "rules.transitTicks");
        positiveTicks(rules.itemPickupTicks(), "rules.itemPickupTicks");
        positiveTicks(rules.cardPickupTicks(), "rules.cardPickupTicks");
        positiveTicks(rules.portalUnlockTicks(), "rules.portalUnlockTicks");
        positiveTicks(rules.portalEnterTicks(), "rules.portalEnterTicks");
    }

    private static void validateMap(MapDefinition map, RuleConfig rules) {
        if (map == null) {
            throw new IllegalArgumentException("map: missing");
        }
        if (blank(map.mapId())) {
            throw new IllegalArgumentException("map.mapId: must not be blank");
        }
        if (map.mapVersion() != 2) {
            throw new IllegalArgumentException("map.mapVersion: v2 requires 2, got " + map.mapVersion());
        }
        if (map.width() <= 0 || map.height() <= 0) {
            throw new IllegalArgumentException("map: width/height must be positive");
        }
        if (map.walls() == null || map.spawns() == null || map.materials() == null
                || map.deliveries() == null || map.exits() == null
                || map.itemSpawns() == null || map.cardSpawns() == null
                || map.portals() == null) {
            throw new IllegalArgumentException("map: all collections are required");
        }

        Set<Long> wallCells = new HashSet<>();
        com.lightchase.contracts.MapDefinition geometryMap = baseMap(map);
        for (WallCell wall : map.walls()) {
            if (wall == null) {
                throw new IllegalArgumentException("map.walls: must not contain null");
            }
            if (wall.col < 0 || wall.col >= map.width() || wall.row < 0 || wall.row >= map.height()) {
                throw new IllegalArgumentException("map.walls: cell (" + wall.col + "," + wall.row
                        + ") is outside the map");
            }
            if (!wallCells.add(cellKey(wall.col, wall.row))) {
                throw new IllegalArgumentException("map.walls: duplicate cell (" + wall.col + ","
                        + wall.row + ")");
            }
        }

        if (map.spawns().size() != 2 || !map.spawns().keySet().equals(ROLES)) {
            throw new IllegalArgumentException("map.spawns: must contain exactly POLICE and THIEF");
        }
        for (Role role : ROLES) {
            SpawnPose spawn = map.spawns().get(role);
            if (spawn == null) {
                throw new IllegalArgumentException("map.spawns." + role + ": missing");
            }
            finite(spawn.x, spawn.y, "map.spawns." + role);
            if (!Double.isFinite(spawn.headingRad)) {
                throw new IllegalArgumentException("map.spawns." + role + ".headingRad: must be finite");
            }
            standable(geometryMap, rules.radius(), spawn.x, spawn.y, "map.spawns." + role);
        }

        Set<String> ids = new HashSet<>();

        for (MaterialPoint material : map.materials()) {
            if (material == null) {
                throw new IllegalArgumentException("map.materials: must not contain null");
            }
            id(material.id, "map.materials.id");
            unique(ids, material.id, "map.materials");
            finite(material.x, material.y, "map.materials." + material.id);
            standable(geometryMap, rules.radius(), material.x, material.y, "map.materials." + material.id);
            if (blank(material.deliveryId)) {
                throw new IllegalArgumentException("map.materials." + material.id
                        + ".deliveryId: must not be blank");
            }
        }
        for (MapPoint delivery : map.deliveries()) {
            if (delivery == null) {
                throw new IllegalArgumentException("map.deliveries: must not contain null");
            }
            id(delivery.id, "map.deliveries.id");
            unique(ids, delivery.id, "map.deliveries");
            finite(delivery.x, delivery.y, "map.deliveries." + delivery.id);
            standable(geometryMap, rules.radius(), delivery.x, delivery.y, "map.deliveries." + delivery.id);
        }
        for (MapPoint exit : map.exits()) {
            if (exit == null) {
                throw new IllegalArgumentException("map.exits: must not contain null");
            }
            id(exit.id, "map.exits.id");
            unique(ids, exit.id, "map.exits");
            finite(exit.x, exit.y, "map.exits." + exit.id);
            standable(geometryMap, rules.radius(), exit.x, exit.y, "map.exits." + exit.id);
        }
        Set<String> deliveryIds = new HashSet<>();
        for (MapPoint delivery : map.deliveries()) {
            deliveryIds.add(delivery.id);
        }
        for (MaterialPoint material : map.materials()) {
            if (!deliveryIds.contains(material.deliveryId)) {
                throw new IllegalArgumentException("map.materials." + material.id
                        + ": unknown delivery " + material.deliveryId);
            }
        }

        for (GroundItem item : map.itemSpawns()) {
            if (item == null) {
                throw new IllegalArgumentException("map.itemSpawns: must not contain null");
            }
            id(item.itemId(), "map.itemSpawns.itemId");
            unique(ids, item.itemId(), "map.itemSpawns");
            if (item.type() == null || item.ownerRole() == null) {
                throw new IllegalArgumentException("map.itemSpawns." + item.itemId()
                        + ": type and ownerRole are required");
            }
            if (item.type() == ItemType.LIGHT_BOOST && item.ownerRole() != Role.POLICE) {
                throw new IllegalArgumentException("map.itemSpawns." + item.itemId()
                        + ": LIGHT_BOOST belongs to POLICE");
            }
            if (item.type() == ItemType.SHIELD && item.ownerRole() != Role.THIEF) {
                throw new IllegalArgumentException("map.itemSpawns." + item.itemId()
                        + ": SHIELD belongs to THIEF");
            }
            finite(item.x(), item.y(), "map.itemSpawns." + item.itemId());
            standable(geometryMap, rules.radius(), item.x(), item.y(), "map.itemSpawns." + item.itemId());
        }

        if (map.portals().size() != 2) {
            throw new IllegalArgumentException("map.portals: v2 requires exactly 2 portals");
        }
        if (map.cardSpawns().size() != 2) {
            throw new IllegalArgumentException("map.cardSpawns: v2 requires exactly 2 cards");
        }
        Set<String> endpoints = new HashSet<>();
        Map<String, PortalDefinition> portalsById = new LinkedHashMap<>();
        for (PortalDefinition portal : map.portals()) {
            if (portal == null) {
                throw new IllegalArgumentException("map.portals: must not contain null");
            }
            id(portal.portalId(), "map.portals.portalId");
            unique(ids, portal.portalId(), "map.portals");
            if (portalsById.put(portal.portalId(), portal) != null) {
                throw new IllegalArgumentException("map.portals: duplicate id " + portal.portalId());
            }
            for (String side : new String[]{"entry", "exit"}) {
                Position point = side.equals("entry") ? portal.entry() : portal.exit();
                if (point == null) {
                    throw new IllegalArgumentException("map.portals." + portal.portalId()
                            + "." + side + ": missing");
                }
                finite(point.x(), point.y(), "map.portals." + portal.portalId() + "." + side);
                standable(geometryMap, rules.radius(), point.x(), point.y(),
                        "map.portals." + portal.portalId() + "." + side);
                if (!endpoints.add(point.x() + ":" + point.y())) {
                    throw new IllegalArgumentException("map.portals: endpoints must be four distinct points");
                }
            }
        }
        Map<String, String> cardByPortal = new LinkedHashMap<>();
        for (GroundCard card : map.cardSpawns()) {
            if (card == null) {
                throw new IllegalArgumentException("map.cardSpawns: must not contain null");
            }
            id(card.cardId(), "map.cardSpawns.cardId");
            unique(ids, card.cardId(), "map.cardSpawns");
            if (blank(card.portalId()) || !portalsById.containsKey(card.portalId())) {
                throw new IllegalArgumentException("map.cardSpawns." + card.cardId()
                        + ": unknown portal " + card.portalId());
            }
            if (cardByPortal.put(card.portalId(), card.cardId()) != null) {
                throw new IllegalArgumentException("map.cardSpawns: portal " + card.portalId()
                        + " has more than one card");
            }
            finite(card.x(), card.y(), "map.cardSpawns." + card.cardId());
            standable(geometryMap, rules.radius(), card.x(), card.y(), "map.cardSpawns." + card.cardId());
        }
        for (String portalId : portalsById.keySet()) {
            if (!cardByPortal.containsKey(portalId)) {
                throw new IllegalArgumentException("map.cardSpawns: portal " + portalId
                        + " has no card");
            }
        }

        // Structural reachability. Production data already proves the same property at load
        // time; repeating it here keeps direct engine tests from silently using an unreachable
        // map and makes the engine's own contract explicit.
        Set<Long> policeReach = reachable(geometryMap, map.spawns().get(Role.POLICE), rules.radius());
        Set<Long> thiefReach = reachable(geometryMap, map.spawns().get(Role.THIEF), rules.radius());
        for (GroundItem item : map.itemSpawns()) {
            Set<Long> reach = item.ownerRole() == Role.POLICE ? policeReach : thiefReach;
            reachableAt(reach, item.x(), item.y(), "map.itemSpawns." + item.itemId());
        }
        for (MaterialPoint material : map.materials()) {
            reachableAt(thiefReach, material.x, material.y, "map.materials." + material.id);
        }
        for (MapPoint delivery : map.deliveries()) {
            reachableAt(thiefReach, delivery.x, delivery.y, "map.deliveries." + delivery.id);
        }
        for (MapPoint exit : map.exits()) {
            reachableAt(thiefReach, exit.x, exit.y, "map.exits." + exit.id);
        }
        for (GroundCard card : map.cardSpawns()) {
            reachableAt(thiefReach, card.x(), card.y(), "map.cardSpawns." + card.cardId());
        }
        for (PortalDefinition portal : map.portals()) {
            reachableAt(thiefReach, portal.entry().x(), portal.entry().y(),
                    "map.portals." + portal.portalId() + ".entry");
        }
    }

    private static Set<Long> reachable(com.lightchase.contracts.MapDefinition map, SpawnPose spawn,
                                       double radius) {
        int startCol = (int) Math.floor(spawn.x);
        int startRow = (int) Math.floor(spawn.y);
        Set<Long> visited = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        visited.add(cellKey(startCol, startRow));
        queue.add(new int[]{startCol, startRow});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] dir : dirs) {
                int col = cell[0] + dir[0];
                int row = cell[1] + dir[1];
                if (col < 0 || row < 0 || col >= map.width || row >= map.height) continue;
                if (!WorldGeometry.canStand(map, col + 0.5, row + 0.5, radius)) continue;
                if (visited.add(cellKey(col, row))) {
                    queue.add(new int[]{col, row});
                }
            }
        }
        return visited;
    }

    private static void reachableAt(Set<Long> cells, double x, double y, String path) {
        int col = (int) Math.floor(x);
        int row = (int) Math.floor(y);
        if (!cells.contains(cellKey(col, row))) {
            throw new IllegalArgumentException(path + ": must be reachable from its role's spawn");
        }
    }

    private static long cellKey(int col, int row) {
        return ((long) col << 32) ^ (row & 0xffffffffL);
    }

    private static void unique(Set<String> ids, String id, String path) {
        if (!ids.add(id)) {
            throw new IllegalArgumentException(path + ": duplicate id " + id);
        }
    }

    private static void id(String value, String path) {
        if (blank(value) || value.length() > 64) {
            throw new IllegalArgumentException(path + ": must be a non-empty string of at most 64 chars");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void finite(double x, double y, String path) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException(path + ": coordinates must be finite");
        }
    }

    private static void standable(com.lightchase.contracts.MapDefinition map, double radius,
                                  double x, double y, String path) {
        if (!WorldGeometry.canStand(map, x, y, radius)) {
            throw new IllegalArgumentException(path + ": must be inside the map and clear of walls");
        }
    }

    private static void positive(double value, String path) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(path + ": must be positive and finite");
        }
    }

    private static void multiplier(double value, String path) {
        if (!Double.isFinite(value) || value <= 1.0) {
            throw new IllegalArgumentException(path + ": must be finite and > 1");
        }
    }

    private static void positiveTicks(int value, String path) {
        if (value <= 0) {
            throw new IllegalArgumentException(path + ": must be > 0");
        }
    }
}
