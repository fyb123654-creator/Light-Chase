package com.lightchase.data;

import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.WallCell;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MapTopologyTest {
    private static MapDefinition map(int width, int height, List<WallCell> walls) {
        Map<Role, SpawnPose> spawns = new HashMap<>();
        spawns.put(Role.POLICE, new SpawnPose(0.5, 0.5, 0));
        spawns.put(Role.THIEF, new SpawnPose(width - 0.5, height - 0.5, 0));
        return new MapDefinition("test", 1, width, height, walls, spawns,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private static MapPoint point(String id, double x, double y) {
        return new MapPoint(id, x, y);
    }

    @Test
    public void openRoomIsFullyReachable() {
        MapDefinition map = map(5, 5, new ArrayList<>());
        assertEquals(25, MapTopology.freeCells(map).size());
        assertEquals(25, MapTopology.reachableFrom(map, 0, 0).size());
        MapTopology.checkAllTargetsReachable(map, Arrays.asList(point("A", 2.5, 2.5)));
    }

    @Test
    public void wallsReduceFreeCellCount() {
        MapDefinition map = map(5, 5, Arrays.asList(new WallCell(1, 1), new WallCell(2, 1)));
        Set<Long> free = MapTopology.freeCells(map);
        assertEquals(23, free.size());
        assertFalse(free.contains(MapTopology.key(1, 1)));
        assertTrue(free.contains(MapTopology.key(1, 2)));
    }

    @Test
    public void walledOffTargetIsRejected() {
        List<WallCell> walls = Arrays.asList(
                new WallCell(1, 2), new WallCell(3, 2),
                new WallCell(2, 1), new WallCell(2, 3));
        MapDefinition map = map(5, 5, walls);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> MapTopology.checkAllTargetsReachable(map, Arrays.asList(point("A", 2.5, 2.5))));
        assertTrue(exception.getMessage().contains("not connected"));
    }

    @Test
    public void outsideTargetIsRejected() {
        MapDefinition map = map(5, 5, new ArrayList<>());
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> MapTopology.checkAllTargetsReachable(map, Arrays.asList(point("A", 9.5, 2.5))));
        assertTrue(exception.getMessage().contains("unreachable"));
    }

    @Test
    public void wallStartHasNoReachableCells() {
        MapDefinition map = map(5, 5, Arrays.asList(new WallCell(2, 2)));
        assertTrue(MapTopology.reachableFrom(map, 2, 2).isEmpty());
    }

    @Test
    public void cellKeyRoundTrips() {
        long key = MapTopology.key(23, 17);
        assertEquals(23, MapTopology.col(key));
        assertEquals(17, MapTopology.row(key));
    }
}
