package com.lightchase.game;

import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.MaterialPoint;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.WallCell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic maps for engine tests. They are test-only, never production data. */
final class TestMaps {
    private TestMaps() {
    }

    static MapDefinition open(int width, int height, double policeX, double policeY,
                              double thiefX, double thiefY) {
        return withWalls(width, height, policeX, policeY, thiefX, thiefY, new ArrayList<>());
    }

    static MapDefinition withWalls(int width, int height, double policeX, double policeY,
                                   double thiefX, double thiefY, List<WallCell> walls) {
        return taskMap(width, height, policeX, policeY, thiefX, thiefY, walls,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    static MapDefinition taskMap(int width, int height, double policeX, double policeY,
                                 double thiefX, double thiefY,
                                 List<MaterialPoint> materials, List<MapPoint> deliveries,
                                 List<MapPoint> exits) {
        return taskMap(width, height, policeX, policeY, thiefX, thiefY, new ArrayList<>(),
                materials, deliveries, exits);
    }

    static MapDefinition taskMap(int width, int height, double policeX, double policeY,
                                 double thiefX, double thiefY, List<WallCell> walls,
                                 List<MaterialPoint> materials, List<MapPoint> deliveries,
                                 List<MapPoint> exits) {
        Map<Role, SpawnPose> spawns = new LinkedHashMap<>();
        spawns.put(Role.POLICE, new SpawnPose(policeX, policeY, 0));
        spawns.put(Role.THIEF, new SpawnPose(thiefX, thiefY, Math.PI));
        return new MapDefinition("test", 1, width, height, walls, spawns,
                materials, deliveries, exits);
    }

    static MaterialPoint material(String id, double x, double y, String deliveryId) {
        return new MaterialPoint(id, x, y, deliveryId);
    }

    static MapPoint point(String id, double x, double y) {
        return new MapPoint(id, x, y);
    }

    static RuleConfig rules(int durationTicks) {
        return rules(durationTicks, 24, 45, 30);
    }

    static RuleConfig rules(int durationTicks, int markTicks, int invisibleTicks, int immuneTicks) {
        return new RuleConfig(3, 8, 16.0 / 9.0, 4, 60, markTicks, invisibleTicks, immuneTicks,
                0.8, 30, 3, durationTicks, 30, 90, 0.25);
    }

    static MatchInit init() {
        return new MatchInit("m", "p", "t");
    }
}
