package com.lightchase.data;

import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.WallCell;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cell-level reachability check for a grid map. It is intentionally small:
 * a 24x18 map has at most 432 cells, so this runs once at load time.
 */
final class MapTopology {
    private MapTopology() {
    }

    static Set<Long> freeCells(MapDefinition map) {
        if (map == null || map.walls == null) {
            throw new IllegalArgumentException("map: missing walls");
        }
        Set<Long> walls = new HashSet<>();
        for (WallCell wall : map.walls) {
            if (wall == null) {
                throw new IllegalArgumentException("map.walls: null wall cell");
            }
            walls.add(key(wall.col, wall.row));
        }
        Set<Long> free = new LinkedHashSet<>();
        for (int col = 0; col < map.width; col++) {
            for (int row = 0; row < map.height; row++) {
                long cell = key(col, row);
                if (!walls.contains(cell)) {
                    free.add(cell);
                }
            }
        }
        return free;
    }

    static Set<Long> reachableFrom(MapDefinition map, int startCol, int startRow) {
        Set<Long> free = freeCells(map);
        long start = key(startCol, startRow);
        if (!free.contains(start)) {
            return Collections.emptySet();
        }
        return reachableFrom(map, free, start);
    }

    private static Set<Long> reachableFrom(MapDefinition map, Set<Long> free, long start) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        visited.add(start);
        queue.addLast(start);
        int[] deltaCol = {1, -1, 0, 0};
        int[] deltaRow = {0, 0, 1, -1};
        while (!queue.isEmpty()) {
            long current = queue.removeFirst();
            int col = col(current);
            int row = row(current);
            for (int i = 0; i < 4; i++) {
                int nextCol = col + deltaCol[i];
                int nextRow = row + deltaRow[i];
                if (nextCol < 0 || nextRow < 0 || nextCol >= map.width || nextRow >= map.height) {
                    continue;
                }
                long next = key(nextCol, nextRow);
                if (free.contains(next) && visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited;
    }

    static void checkAllTargetsReachable(MapDefinition map, List<MapPoint> targets) {
        Set<Long> free = freeCells(map);
        if (free.isEmpty()) {
            throw new IllegalArgumentException("map: no walkable cells");
        }
        long start = free.iterator().next();
        Set<Long> visited = reachableFrom(map, free, start);
        if (visited.size() != free.size()) {
            for (long cell : free) {
                if (!visited.contains(cell)) {
                    throw new IllegalArgumentException("map: free cells are not connected; isolated cell ("
                            + col(cell) + "," + row(cell) + ")");
                }
            }
        }
        if (targets == null) {
            return;
        }
        for (MapPoint target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("map: null target");
            }
            int targetCol = (int) Math.floor(target.x);
            int targetRow = (int) Math.floor(target.y);
            if (!visited.contains(key(targetCol, targetRow))) {
                throw new IllegalArgumentException("map: target " + target.id + " at ("
                        + target.x + "," + target.y + ") is unreachable");
            }
        }
    }

    static long key(int col, int row) {
        return ((long) col << 32) | (row & 0xffffffffL);
    }

    static int col(long key) {
        return (int) (key >>> 32);
    }

    static int row(long key) {
        return (int) key;
    }
}
