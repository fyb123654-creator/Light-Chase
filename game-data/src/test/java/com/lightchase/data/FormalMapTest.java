package com.lightchase.data;

import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MaterialPoint;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.WallCell;
import com.lightchase.contracts.geometry.WorldGeometry;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class FormalMapTest {
    @Test
    public void formalMapHasTheRequiredShapeAndStableHashes() {
        DataFiles formal = DataFiles.bundledFormal();
        MapDefinition map = formal.map;

        assertEquals("v1", map.mapId);
        assertEquals(1, map.mapVersion);
        assertEquals(24, map.width);
        assertEquals(18, map.height);
        assertEquals(3, map.materials.size());
        assertEquals(3, map.deliveries.size());
        assertEquals(2, map.exits.size());

        assertEquals("M1", map.materials.get(0).id);
        assertEquals("M2", map.materials.get(1).id);
        assertEquals("M3", map.materials.get(2).id);
        assertEquals("D1", map.deliveries.get(0).id);
        assertEquals("D2", map.deliveries.get(1).id);
        assertEquals("D3", map.deliveries.get(2).id);
        assertEquals("E1", map.exits.get(0).id);
        assertEquals("E2", map.exits.get(1).id);

        assertTrue(formal.mapHash.matches("[0-9a-f]{64}"));
        assertTrue(formal.configHash.matches("[0-9a-f]{64}"));
        assertEquals("98eb481271d57348ba0102f5d217c9da5d6ddd6032b0c5c86946dd6b89cc08c1",
                formal.mapHash);
        assertEquals("1db72fcc967ff5931939273e8994f7a6e810b16616c56066ea81f26b6e9230d1",
                formal.configHash);
        assertEquals(formal.mapHash, DataFiles.bundledFormal().mapHash);
        assertEquals(formal.configHash, DataFiles.bundled().configHash);
        assertNotEquals(DataFiles.bundled().mapHash, formal.mapHash);
    }

    @Test
    public void allFormalPointsAreFreeReachableAndDistinct() {
        DataFiles formal = DataFiles.bundledFormal();
        MapDefinition map = formal.map;
        List<MapPoint> targets = new ArrayList<>();
        for (MaterialPoint material : map.materials) {
            targets.add(new MapPoint(material.id, material.x, material.y));
        }
        targets.addAll(map.deliveries);
        targets.addAll(map.exits);

        Set<Long> occupied = new HashSet<>();
        for (MapPoint target : targets) {
            int col = (int) Math.floor(target.x);
            int row = (int) Math.floor(target.y);
            assertTrue("target outside map: " + target.id, col >= 0 && row >= 0);
            assertTrue("target outside map: " + target.id, col < map.width && row < map.height);
            assertTrue("target cannot stand: " + target.id,
                    WorldGeometry.canStand(map, target.x, target.y, formal.rules.radius));
            assertTrue("duplicate cell: " + target.id, occupied.add(MapTopology.key(col, row)));
        }
        for (Role role : new Role[]{Role.POLICE, Role.THIEF}) {
            SpawnPose spawn = map.spawns.get(role);
            int col = (int) Math.floor(spawn.x);
            int row = (int) Math.floor(spawn.y);
            assertTrue("duplicate spawn cell: " + role, occupied.add(MapTopology.key(col, row)));
            assertTrue("spawn cannot stand: " + role,
                    WorldGeometry.canStand(map, spawn.x, spawn.y, formal.rules.radius));
            targets.add(new MapPoint("spawn-" + role, spawn.x, spawn.y));
        }

        SpawnPose police = map.spawns.get(Role.POLICE);
        SpawnPose thief = map.spawns.get(Role.THIEF);
        assertFalse("thief is in the opening beam", WorldGeometry.inBeam(
                map, police.x, police.y, police.headingRad, thief.x, thief.y,
                formal.rules.beamRange, Math.toRadians(formal.rules.beamAngleDeg) / 2.0));

        MapTopology.checkAllTargetsReachable(map, targets);
    }

    @Test
    public void freeSpaceHasAtLeastTwoLoops() {
        MapDefinition map = DataFiles.bundledFormal().map;
        Set<Long> free = MapTopology.freeCells(map);
        int edges = 0;
        for (long cell : free) {
            int col = MapTopology.col(cell);
            int row = MapTopology.row(cell);
            if (free.contains(MapTopology.key(col + 1, row))) {
                edges++;
            }
            if (free.contains(MapTopology.key(col, row + 1))) {
                edges++;
            }
        }
        int independentCycles = edges - free.size() + 1;
        assertTrue("formal map needs at least two loops, got " + independentCycles,
                independentCycles >= 2);

        Set<Long> interiorWalls = new HashSet<>();
        for (WallCell wall : map.walls) {
            if (wall.col > 0 && wall.col < map.width - 1
                    && wall.row > 0 && wall.row < map.height - 1) {
                interiorWalls.add(MapTopology.key(wall.col, wall.row));
            }
        }
        List<Integer> islandSizes = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long start : interiorWalls) {
            if (!seen.add(start)) {
                continue;
            }
            int size = 0;
            Deque<Long> queue = new ArrayDeque<>();
            queue.addLast(start);
            while (!queue.isEmpty()) {
                long cell = queue.removeFirst();
                size++;
                int col = MapTopology.col(cell);
                int row = MapTopology.row(cell);
                long[] neighbors = {
                        MapTopology.key(col + 1, row),
                        MapTopology.key(col - 1, row),
                        MapTopology.key(col, row + 1),
                        MapTopology.key(col, row - 1)
                };
                for (long next : neighbors) {
                    if (interiorWalls.contains(next) && seen.add(next)) {
                        queue.addLast(next);
                    }
                }
            }
            islandSizes.add(size);
        }
        assertEquals("formal map should have two interior islands", 2, islandSizes.size());
        assertEquals(9, (int) islandSizes.get(0));
        assertEquals(9, (int) islandSizes.get(1));
    }

    @Test
    public void formalMapIsNotTheTestOnlyRoom() {
        DataFiles formal = DataFiles.bundledFormal();
        DataFiles testRoom = DataFiles.bundled();
        assertNotEquals(testRoom.map.mapId, formal.map.mapId);
        assertNotEquals(testRoom.mapHash, formal.mapHash);
        assertEquals(testRoom.configHash, formal.configHash);
        assertEquals(3, formal.map.materials.size());
        assertFalse(formal.mapHash.isEmpty());
    }
}
