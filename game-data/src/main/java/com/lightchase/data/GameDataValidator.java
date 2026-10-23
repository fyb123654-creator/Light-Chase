package com.lightchase.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MaterialPoint;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.WallCell;
import com.lightchase.contracts.geometry.WorldGeometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates map/rules data before a match can start. It deliberately fails fast:
 * a bad data file must be rejected at load time, not after hundreds of ticks.
 */
final class GameDataValidator {
    private static final Set<String> MATERIAL_IDS =
            new LinkedHashSet<>(Arrays.asList("M1", "M2", "M3"));
    private static final Set<String> DELIVERY_IDS =
            new LinkedHashSet<>(Arrays.asList("D1", "D2", "D3"));
    private static final Set<String> EXIT_IDS =
            new LinkedHashSet<>(Arrays.asList("E1", "E2"));
    private static final Set<Role> ROLES =
            new LinkedHashSet<>(Arrays.asList(Role.POLICE, Role.THIEF));

    private GameDataValidator() {
    }

    /** Checks JSON shape (exact keys and primitive types) before Gson mapping. */
    static void validateShape(JsonObject mapJson, JsonObject rulesJson) {
        StrictJson.requireExactKeys(mapJson, "map",
                "mapId", "mapVersion", "width", "height", "walls", "spawns",
                "materials", "deliveries", "exits");
        StrictJson.requireString(mapJson, "mapId", "map");
        StrictJson.requireInt(mapJson, "mapVersion", "map");
        StrictJson.requireInt(mapJson, "width", "map");
        StrictJson.requireInt(mapJson, "height", "map");

        JsonArray walls = StrictJson.requireArray(mapJson, "walls", "map");
        for (int i = 0; i < walls.size(); i++) {
            String path = "map.walls[" + i + "]";
            JsonObject wall = StrictJson.asObject(walls.get(i), path);
            StrictJson.requireExactKeys(wall, path, "col", "row");
            StrictJson.requireInt(wall, "col", path);
            StrictJson.requireInt(wall, "row", path);
        }

        JsonObject spawns = StrictJson.requireObject(mapJson, "spawns", "map");
        StrictJson.requireExactKeys(spawns, "map.spawns", "POLICE", "THIEF");
        for (String role : new String[]{"POLICE", "THIEF"}) {
            String path = "map.spawns." + role;
            JsonObject spawn = StrictJson.requireObject(spawns, role, "map.spawns");
            StrictJson.requireExactKeys(spawn, path, "x", "y", "headingRad");
            StrictJson.requireDouble(spawn, "x", path);
            StrictJson.requireDouble(spawn, "y", path);
            StrictJson.requireDouble(spawn, "headingRad", path);
        }

        validatePointArray(mapJson, "materials", "map", true);
        validatePointArray(mapJson, "deliveries", "map", false);
        validatePointArray(mapJson, "exits", "map", false);

        StrictJson.requireExactKeys(rulesJson, "rules",
                "speed", "viewHeight", "viewAspect", "beamRange", "beamAngleDeg",
                "markTicks", "invisibleTicks", "immuneTicks", "interactRange",
                "interactTicks", "maxMarks", "durationTicks", "tickRate",
                "countdownTicks", "radius");
        StrictJson.requireDouble(rulesJson, "speed", "rules");
        StrictJson.requireDouble(rulesJson, "viewHeight", "rules");
        StrictJson.requireDouble(rulesJson, "viewAspect", "rules");
        StrictJson.requireDouble(rulesJson, "beamRange", "rules");
        StrictJson.requireDouble(rulesJson, "beamAngleDeg", "rules");
        StrictJson.requireInt(rulesJson, "markTicks", "rules");
        StrictJson.requireInt(rulesJson, "invisibleTicks", "rules");
        StrictJson.requireInt(rulesJson, "immuneTicks", "rules");
        StrictJson.requireDouble(rulesJson, "interactRange", "rules");
        StrictJson.requireInt(rulesJson, "interactTicks", "rules");
        StrictJson.requireInt(rulesJson, "maxMarks", "rules");
        StrictJson.requireInt(rulesJson, "durationTicks", "rules");
        StrictJson.requireInt(rulesJson, "tickRate", "rules");
        StrictJson.requireInt(rulesJson, "countdownTicks", "rules");
        StrictJson.requireDouble(rulesJson, "radius", "rules");
    }

    private static void validatePointArray(JsonObject parent, String key, String path, boolean material) {
        JsonArray array = StrictJson.requireArray(parent, key, path);
        for (int i = 0; i < array.size(); i++) {
            String itemPath = path + "." + key + "[" + i + "]";
            JsonObject item = StrictJson.asObject(array.get(i), itemPath);
            if (material) {
                StrictJson.requireExactKeys(item, itemPath, "id", "x", "y", "deliveryId");
                StrictJson.requireString(item, "id", itemPath);
                StrictJson.requireDouble(item, "x", itemPath);
                StrictJson.requireDouble(item, "y", itemPath);
                StrictJson.requireString(item, "deliveryId", itemPath);
            } else {
                StrictJson.requireExactKeys(item, itemPath, "id", "x", "y");
                StrictJson.requireString(item, "id", itemPath);
                StrictJson.requireDouble(item, "x", itemPath);
                StrictJson.requireDouble(item, "y", itemPath);
            }
        }
    }

    static void validate(MapDefinition map, RuleConfig rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules: missing");
        }
        validateRules(rules);
        validateMap(map, rules);
    }

    static void validateRules(RuleConfig rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules: missing");
        }
        requirePositive(rules.speed, "rules.speed");
        requirePositive(rules.viewHeight, "rules.viewHeight");
        requirePositive(rules.viewAspect, "rules.viewAspect");
        requirePositive(rules.beamRange, "rules.beamRange");
        requirePositive(rules.beamAngleDeg, "rules.beamAngleDeg");
        if (rules.beamAngleDeg >= 180) {
            throw new IllegalArgumentException("rules.beamAngleDeg: must be < 180");
        }
        if (rules.markTicks <= 0) {
            throw new IllegalArgumentException("rules.markTicks: must be > 0");
        }
        if (rules.invisibleTicks < 0) {
            throw new IllegalArgumentException("rules.invisibleTicks: must be >= 0");
        }
        if (rules.immuneTicks < 0) {
            throw new IllegalArgumentException("rules.immuneTicks: must be >= 0");
        }
        requirePositive(rules.interactRange, "rules.interactRange");
        if (rules.interactTicks <= 0) {
            throw new IllegalArgumentException("rules.interactTicks: must be > 0");
        }
        if (rules.maxMarks < 1) {
            throw new IllegalArgumentException("rules.maxMarks: must be >= 1");
        }
        if (rules.durationTicks <= 0) {
            throw new IllegalArgumentException("rules.durationTicks: must be > 0");
        }
        if (rules.tickRate != 30) {
            throw new IllegalArgumentException("rules.tickRate: V1 requires 30, got " + rules.tickRate);
        }
        if (rules.countdownTicks < 0) {
            throw new IllegalArgumentException("rules.countdownTicks: must be >= 0");
        }
        requirePositive(rules.radius, "rules.radius");
        if (rules.radius >= 0.5) {
            throw new IllegalArgumentException("rules.radius: must be < 0.5 for one-cell corridors");
        }
    }

    private static void validateMap(MapDefinition map, RuleConfig rules) {
        if (map == null) {
            throw new IllegalArgumentException("map: missing");
        }
        if (map.mapId == null || map.mapId.trim().isEmpty()) {
            throw new IllegalArgumentException("map.mapId: must not be blank");
        }
        if (map.mapVersion < 1) {
            throw new IllegalArgumentException("map.mapVersion: must be >= 1");
        }
        if (map.width != 24 || map.height != 18) {
            throw new IllegalArgumentException("map: V1 map must be 24x18, got " + map.width + "x" + map.height);
        }
        if (map.walls == null) {
            throw new IllegalArgumentException("map.walls: missing");
        }

        Set<Long> wallKeys = new HashSet<>();
        for (int i = 0; i < map.walls.size(); i++) {
            WallCell wall = map.walls.get(i);
            if (wall == null) {
                throw new IllegalArgumentException("map.walls[" + i + "]: must not be null");
            }
            if (wall.col < 0 || wall.col >= map.width || wall.row < 0 || wall.row >= map.height) {
                throw new IllegalArgumentException("map.walls[" + i + "]: cell (" + wall.col + "," + wall.row
                        + ") is outside 0.." + (map.width - 1) + " x 0.." + (map.height - 1));
            }
            if (!wallKeys.add(MapTopology.key(wall.col, wall.row))) {
                throw new IllegalArgumentException("map.walls[" + i + "]: duplicate wall cell ("
                        + wall.col + "," + wall.row + ")");
            }
        }

        if (map.spawns == null || map.spawns.size() != 2 || !map.spawns.keySet().equals(ROLES)) {
            throw new IllegalArgumentException("map.spawns: must contain exactly POLICE and THIEF");
        }
        for (Role role : ROLES) {
            SpawnPose spawn = map.spawns.get(role);
            if (spawn == null) {
                throw new IllegalArgumentException("map.spawns." + role + ": missing");
            }
            requireFinite(spawn.x, spawn.y, "map.spawns." + role);
            if (!Double.isFinite(spawn.headingRad)) {
                throw new IllegalArgumentException("map.spawns." + role + ".headingRad: must be finite");
            }
            requireStandable(map, rules.radius, spawn.x, spawn.y, "map.spawns." + role);
        }

        if (map.materials == null || map.materials.size() != 3) {
            throw new IllegalArgumentException("map.materials: V1 requires exactly 3 materials");
        }
        Set<String> materialIds = new HashSet<>();
        for (int i = 0; i < map.materials.size(); i++) {
            MaterialPoint material = map.materials.get(i);
            String path = "map.materials[" + i + "]";
            if (material == null) {
                throw new IllegalArgumentException(path + ": must not be null");
            }
            if (material.id == null || material.id.trim().isEmpty()) {
                throw new IllegalArgumentException(path + ".id: must not be blank");
            }
            if (!materialIds.add(material.id)) {
                throw new IllegalArgumentException(path + ".id: duplicate material id " + material.id);
            }
            requireFinite(material.x, material.y, path);
            requireStandable(map, rules.radius, material.x, material.y, path);
            if (material.deliveryId == null || material.deliveryId.trim().isEmpty()) {
                throw new IllegalArgumentException(path + ".deliveryId: must not be blank");
            }
        }
        if (!materialIds.equals(MATERIAL_IDS)) {
            throw new IllegalArgumentException("map.materials: ids must be M1, M2, M3; got " + materialIds);
        }

        if (map.deliveries == null || map.deliveries.size() != 3) {
            throw new IllegalArgumentException("map.deliveries: V1 requires exactly 3 delivery points");
        }
        Set<String> deliveryIds = new HashSet<>();
        for (int i = 0; i < map.deliveries.size(); i++) {
            MapPoint delivery = map.deliveries.get(i);
            String path = "map.deliveries[" + i + "]";
            if (delivery == null) {
                throw new IllegalArgumentException(path + ": must not be null");
            }
            if (delivery.id == null || delivery.id.trim().isEmpty()) {
                throw new IllegalArgumentException(path + ".id: must not be blank");
            }
            if (!deliveryIds.add(delivery.id)) {
                throw new IllegalArgumentException(path + ".id: duplicate delivery id " + delivery.id);
            }
            requireFinite(delivery.x, delivery.y, path);
            requireStandable(map, rules.radius, delivery.x, delivery.y, path);
        }
        if (!deliveryIds.equals(DELIVERY_IDS)) {
            throw new IllegalArgumentException("map.deliveries: ids must be D1, D2, D3; got " + deliveryIds);
        }

        if (map.exits == null || map.exits.size() != 2) {
            throw new IllegalArgumentException("map.exits: V1 requires exactly 2 exits");
        }
        Set<String> exitIds = new HashSet<>();
        for (int i = 0; i < map.exits.size(); i++) {
            MapPoint exit = map.exits.get(i);
            String path = "map.exits[" + i + "]";
            if (exit == null) {
                throw new IllegalArgumentException(path + ": must not be null");
            }
            if (exit.id == null || exit.id.trim().isEmpty()) {
                throw new IllegalArgumentException(path + ".id: must not be blank");
            }
            if (!exitIds.add(exit.id)) {
                throw new IllegalArgumentException(path + ".id: duplicate exit id " + exit.id);
            }
            requireFinite(exit.x, exit.y, path);
            requireStandable(map, rules.radius, exit.x, exit.y, path);
        }
        if (!exitIds.equals(EXIT_IDS)) {
            throw new IllegalArgumentException("map.exits: ids must be E1, E2; got " + exitIds);
        }

        Map<String, String> materialByDelivery = new HashMap<>();
        for (MaterialPoint material : map.materials) {
            if (!deliveryIds.contains(material.deliveryId)) {
                throw new IllegalArgumentException("map.materials: " + material.id + " points to unknown delivery "
                        + material.deliveryId);
            }
            String expectedDelivery = "D" + material.id.substring(1);
            if (!expectedDelivery.equals(material.deliveryId)) {
                throw new IllegalArgumentException("map.materials: " + material.id
                        + " must bind to " + expectedDelivery + ", got " + material.deliveryId);
            }
            if (materialByDelivery.put(material.deliveryId, material.id) != null) {
                throw new IllegalArgumentException("map.deliveries: " + material.deliveryId
                        + " is bound to more than one material");
            }
        }
        if (!materialByDelivery.keySet().equals(deliveryIds)) {
            throw new IllegalArgumentException("map.materials: every delivery point must be bound to one material");
        }

        List<NamedPoint> points = new ArrayList<>();
        for (MaterialPoint material : map.materials) {
            points.add(new NamedPoint("material " + material.id, material.x, material.y));
        }
        for (MapPoint delivery : map.deliveries) {
            points.add(new NamedPoint("delivery " + delivery.id, delivery.x, delivery.y));
        }
        for (MapPoint exit : map.exits) {
            points.add(new NamedPoint("exit " + exit.id, exit.x, exit.y));
        }
        for (Role role : ROLES) {
            SpawnPose spawn = map.spawns.get(role);
            points.add(new NamedPoint("spawn " + role, spawn.x, spawn.y));
        }
        // Fixed points must occupy distinct cells. Different coordinates that floor to
        // the same cell are not distinguishable on the client and can overlap actions.
        Set<Long> occupiedCells = new HashSet<>();
        for (NamedPoint point : points) {
            int col = (int) Math.floor(point.x);
            int row = (int) Math.floor(point.y);
            long cell = MapTopology.key(col, row);
            if (!occupiedCells.add(cell)) {
                throw new IllegalArgumentException("map: " + point.name
                        + " shares cell (" + col + "," + row + ") with another fixed point");
            }
        }

        SpawnPose police = map.spawns.get(Role.POLICE);
        SpawnPose thief = map.spawns.get(Role.THIEF);
        double halfAngle = Math.toRadians(rules.beamAngleDeg) / 2.0;
        if (WorldGeometry.inBeam(map, police.x, police.y, police.headingRad,
                thief.x, thief.y, rules.beamRange, halfAngle)) {
            throw new IllegalArgumentException("map.spawns: thief starts inside the police beam; move spawns or heading");
        }

        List<MapPoint> targets = new ArrayList<>();
        for (MaterialPoint material : map.materials) {
            targets.add(new MapPoint(material.id, material.x, material.y));
        }
        targets.addAll(map.deliveries);
        targets.addAll(map.exits);
        MapTopology.checkAllTargetsReachable(map, targets);
    }

    private static void requireFinite(double x, double y, String path) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException(path + ": coordinates must be finite");
        }
    }

    private static void requireStandable(MapDefinition map, double radius, double x, double y, String path) {
        if (!WorldGeometry.canStand(map, x, y, radius)) {
            throw new IllegalArgumentException(path + ": point must be inside the map and not inside a wall");
        }
    }

    private static void requirePositive(double value, String path) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(path + ": must be a positive finite number");
        }
    }

    private static final class NamedPoint {
        final String name;
        final double x;
        final double y;

        NamedPoint(String name, double x, double y) {
            this.name = name;
            this.x = x;
            this.y = y;
        }
    }
}
