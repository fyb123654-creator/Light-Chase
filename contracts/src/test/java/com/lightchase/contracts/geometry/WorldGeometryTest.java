package com.lightchase.contracts.geometry;

import com.lightchase.contracts.*;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class WorldGeometryTest {
    private static MapDefinition room() {
        List<WallCell> walls = new ArrayList<>();
        // A 4x4 block around (4..5, 4..5) in a 10x10 open room.
        for (int c = 4; c <= 5; c++) for (int r = 4; r <= 5; r++) walls.add(new WallCell(c, r));
        return new MapDefinition("t", 1, 10, 10, walls,
                new HashMap<Role, SpawnPose>(), new ArrayList<MaterialPoint>(),
                new ArrayList<MapPoint>(), new ArrayList<MapPoint>());
    }

    private final MapDefinition map = room();

    @Test public void canStandRejectsWallsAndOutside() {
        assertTrue(WorldGeometry.canStand(map, 2.0, 2.0, 0.25));
        assertFalse(WorldGeometry.canStand(map, 4.5, 4.5, 0.25));
        assertFalse(WorldGeometry.canStand(map, 0.1, 5.0, 0.25));
        assertFalse(WorldGeometry.canStand(map, 9.95, 5.0, 0.25));
    }

    @Test public void segmentBlockedThroughAndAroundWalls() {
        assertTrue(WorldGeometry.segmentBlocked(map, 2.0, 4.5, 8.0, 4.5));
        assertFalse(WorldGeometry.segmentBlocked(map, 2.0, 2.0, 8.0, 2.0));
        // Grazing the wall corner at (4,4) still blocks sight.
        assertTrue(WorldGeometry.segmentBlocked(map, 3.0, 3.0, 5.0, 5.0));
    }

    @Test public void beamChecksRangeAngleAndWalls() {
        double heading = 0.0; // facing +x
        double half = Math.toRadians(30);
        assertTrue(WorldGeometry.inBeam(map, 1.0, 1.0, heading, 3.0, 1.0, 4.0, half));
        assertFalse(WorldGeometry.inBeam(map, 1.0, 1.0, heading, 5.5, 1.0, 4.0, half));
        assertFalse(WorldGeometry.inBeam(map, 1.0, 1.0, heading, 2.0, 3.0, 4.0, half));
        // Exactly 30 degrees is inside; 30.001 is not.
        double boundary = 1.0 + Math.tan(half);
        assertTrue(WorldGeometry.inBeam(map, 1.0, 1.0, heading, 2.0, boundary, 4.0, half));
        assertFalse(WorldGeometry.inBeam(map, 1.0, 1.0, heading, 2.0, boundary + 0.01, 4.0, half));
        // Wall between police at (3.5,4.5) and thief at (6.5,4.5).
        assertFalse(WorldGeometry.inBeam(map, 3.5, 4.5, heading, 6.5, 4.5, 4.0, half));
    }

    @Test public void beamAtZeroDistanceIgnoresAngle() {
        assertTrue(WorldGeometry.inBeam(map, 2.0, 2.0, Math.PI, 2.0, 2.0, 4.0, Math.toRadians(30)));
    }

    @Test public void thiefViewRectangleIncludesBoundary() {
        double halfW = 8.0 * 16 / 9 / 2, halfH = 4.0;
        assertTrue(WorldGeometry.inViewRect(5, 5, 5 + halfW, 9, halfW, halfH));
        assertTrue(WorldGeometry.inViewRect(5, 5, 5 - halfW, 1, halfW, halfH));
        assertFalse(WorldGeometry.inViewRect(5, 5, 5, 5 + halfH + 0.01, halfW, halfH));
        assertFalse(WorldGeometry.inViewRect(5, 5, 5 + halfW + 0.01, 5, halfW, halfH));
    }
}
