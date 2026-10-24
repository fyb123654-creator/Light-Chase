package com.lightchase.client;

import com.lightchase.contracts.*;
import com.lightchase.contracts.geometry.WorldGeometry;
import com.lightchase.data.DataFiles;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Everything the client needs to know before the three of us switch to the formal map.
 *
 * <p>The switch is dangerous because {@code mapHash} travels in HELLO: if the client and the server
 * disagree the connection is refused outright. This file pins down what does and does not change
 * when the map swaps, so switching day is a mechanical edit rather than an experiment.
 */
public class FormalMapReadinessTest {

    private static final int RANGE = 4;          // RuleConfig.beamRange
    private static final int RAYS = 72;

    /** How many of {@link #RAYS} directions out of the police spawn are stopped by a wall. */
    private static int blockedRays(MapDefinition map) {
        SpawnPose spawn = map.spawns.get(com.lightchase.contracts.Role.POLICE);
        int blocked = 0;
        for (int i = 0; i < RAYS; i++) {
            double angle = 2 * Math.PI * i / RAYS;
            double dx = Math.cos(angle), dy = Math.sin(angle);
            if (WorldGeometry.segmentBlocked(map, spawn.x, spawn.y,
                    spawn.x + dx * RANGE, spawn.y + dy * RANGE)) {
                blocked++;
            }
        }
        return blocked;
    }

    @Test public void bothMapsAreTheSameShapeSoTheLayoutDoesNotMove() {
        DataFiles test = DataFiles.bundled();
        DataFiles formal = DataFiles.bundledFormal();

        assertEquals("test-room", test.map.mapId);
        assertEquals("v1", formal.map.mapId);
        assertEquals("switching must not change the world size", test.map.width, formal.map.width);
        assertEquals("switching must not change the world size", test.map.height, formal.map.height);
        assertEquals(24, formal.map.width);
        assertEquals(18, formal.map.height);

        // The minimap is drawn at MM_SCALE pixels per tile, so identical dimensions mean an
        // identical box - no layout work on switching day.
        assertEquals(WorldRenderer.MM_SCALE * test.map.width, WorldRenderer.MM_SCALE * formal.map.width, 1e-6);
        assertEquals(WorldRenderer.MM_SCALE * test.map.height, WorldRenderer.MM_SCALE * formal.map.height, 1e-6);

        assertEquals("the same three materials", 3, formal.map.materials.size());
        assertEquals("the same three deliveries", 3, formal.map.deliveries.size());
        assertEquals("the same two exits", 2, formal.map.exits.size());
    }

    @Test public void onlyTheMapHashMoves() {
        DataFiles test = DataFiles.bundled();
        DataFiles formal = DataFiles.bundledFormal();
        assertNotEquals("the map hash must change or nobody would notice the switch",
                test.mapHash, formal.mapHash);
        assertEquals("the rules file is shared, so configHash must not move",
                test.configHash, formal.configHash);
        // HELLO compares both hashes; the client sends whatever DataFiles produced.
        assertEquals(64, formal.mapHash.length());
        assertEquals(64, formal.configHash.length());
    }

    @Test public void theBeamClippingReallyChanges() {
        DataFiles test = DataFiles.bundled();
        DataFiles formal = DataFiles.bundledFormal();

        int testWalls = test.map.walls.size();
        int formalWalls = formal.map.walls.size();
        assertEquals("the formal map is denser", 88, testWalls);
        assertEquals(98, formalWalls);

        int blockedBefore = blockedRays(test.map);
        int blockedAfter = blockedRays(formal.map);
        assertTrue("a different wall layout must change what the beam hits: "
                        + blockedBefore + " vs " + blockedAfter,
                blockedBefore != blockedAfter);

        // Sanity: the counts are inside the possible range for both maps.
        assertTrue(blockedBefore >= 0 && blockedBefore <= RAYS);
        assertTrue(blockedAfter >= 0 && blockedAfter <= RAYS);
    }

    @Test public void theWallCellsStayInsideTheGrid() {
        DataFiles formal = DataFiles.bundledFormal();
        for (WallCell w : formal.map.walls) {
            assertTrue("wall column " + w.col, w.col >= 0 && w.col < formal.map.width);
            assertTrue("wall row " + w.row, w.row >= 0 && w.row < formal.map.height);
        }
    }

    /**
     * A hand-checkable ray on the formal map: straight through a known wall is blocked, straight
     * along a row of empty cells is not. This is the "does the clipping agree with the picture"
     * check, done without a renderer.
     */
    @Test public void aHandPickedRayOnTheFormalMapBehaves() {
        MapDefinition map = DataFiles.bundledFormal().map;
        WallCell wall = map.walls.get(0);

        // Through the wall: from half a tile left of it to half a tile right of it, at its centre row.
        double rowCentre = wall.row + 0.5;
        assertTrue("a ray crossing a known wall cell must be blocked",
                WorldGeometry.segmentBlocked(map, wall.col - 0.5, rowCentre, wall.col + 1.5, rowCentre));

        // Along free cells: find a row with two adjacent free cells and shoot between them.
        boolean foundFreePair = false;
        for (int row = 0; row < map.height && !foundFreePair; row++) {
            for (int col = 0; col + 1 < map.width; col++) {
                if (!isWall(map, col, row) && !isWall(map, col + 1, row)) {
                    assertFalse("a ray between two free cells must not be blocked",
                            WorldGeometry.segmentBlocked(map, col + 0.5, row + 0.5, col + 1.5, row + 0.5));
                    foundFreePair = true;
                    break;
                }
            }
        }
        assertTrue("the formal map must contain at least one free pair", foundFreePair);
    }

    private static boolean isWall(MapDefinition map, int col, int row) {
        for (WallCell w : map.walls) {
            if (w.col == col && w.row == row) return true;
        }
        return false;
    }

    /** Minimal listener: just enough to know the fixture produced a view, a room and a result. */
    private static final class Sink implements GatewayListener {
        RoomState room;
        PlayerView view;
        MatchResult result;
        public void onSession(SessionInfo s) { }
        public void onConnection(ConnectionStatus s) { }
        public void onRoom(RoomState s) { room = s; }
        public void onView(PlayerView s) { view = s; }
        public void onResult(MatchResult s) { result = s; }
        public void onError(GatewayError e) { }
        public void onControl(ControlStatus s) { }
    }

    /**
     * After the switch the offline demo runs on the formal map too, so the fixture must not depend on
     * test-room's layout. This drives the fixture once per map: countdown, a few running ticks, quit.
     */
    @Test public void theOfflineFixtureRunsOnEitherMap() {
        for (DataFiles d : new DataFiles[]{DataFiles.bundled(), DataFiles.bundledFormal()}) {
            Sink sink = new Sink();
            FakeGateway g = new FakeGateway();
            g.configure(d);
            g.connect(new ConnectionOptions("offline", 8080, "DEMO", "t", d.mapHash, d.configHash), sink);

            g.setReady(true);
            g.advance(3.2f);
            assertEquals("the countdown must reach RUNNING on " + d.map.mapId,
                    com.lightchase.contracts.RoomPhase.RUNNING, sink.room.phase);
            for (int i = 0; i < 5; i++) g.advance(0.1f);

            assertNotNull("no view on " + d.map.mapId, sink.view);
            assertEquals("the view must belong to the configured map", d.map.mapId, sink.room.mapId);
            assertEquals("the spawn must come from the configured map",
                    d.map.spawns.get(com.lightchase.contracts.Role.THIEF).x, sink.view.self.x, 0.3);

            g.leaveRoom();
            assertNotNull("quitting must still produce a result on " + d.map.mapId, sink.result);
        }
    }

    @Test public void theClientStillDefaultsToTheTestMap() {
        // Guard: nobody should switch the default by accident. Switching day is a deliberate edit
        // of ClientAssets.MAP, and this assertion is what makes that edit show up in review.
        assertEquals(ClientAssets.TEST_MAP, ClientAssets.MAP);
        assertNotEquals(ClientAssets.FORMAL_MAP, ClientAssets.MAP);
        assertEquals("maps/test-room.json", ClientAssets.TEST_MAP);
        assertEquals("maps/v1.json", ClientAssets.FORMAL_MAP);
        assertEquals("rules/v1.json", ClientAssets.RULES);

        // Both paths must be real classpath resources, or the switch would fail at runtime.
        for (String path : new String[]{ClientAssets.TEST_MAP, ClientAssets.FORMAL_MAP, ClientAssets.RULES}) {
            assertNotNull("missing resource " + path,
                    FormalMapReadinessTest.class.getClassLoader().getResource(path));
        }
    }

    /**
     * Binds the constant to the bytes it names: whatever {@code ClientAssets.MAP} currently points
     * at is the map the client will really load, and it must be readable and self-consistent.
     *
     * <p>Without this, pointing the constant at a wrong path would only fail on a device.
     */
    @Test public void theConfiguredMapIsTheMapTheClientWouldLoad() throws Exception {
        String path = ClientAssets.MAP;
        byte[] mapBytes;
        try (java.io.InputStream in = FormalMapReadinessTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull("ClientAssets.MAP must point at a real resource: " + path, in);
            mapBytes = in.readAllBytes();
        }
        byte[] rulesBytes;
        try (java.io.InputStream in = FormalMapReadinessTest.class.getClassLoader().getResourceAsStream(ClientAssets.RULES)) {
            assertNotNull("ClientAssets.RULES must point at a real resource", in);
            rulesBytes = in.readAllBytes();
        }

        DataFiles configured = DataFiles.read(mapBytes, rulesBytes);
        DataFiles expected = ClientAssets.MAP.equals(ClientAssets.FORMAL_MAP)
                ? DataFiles.bundledFormal() : DataFiles.bundled();

        assertEquals("the configured resource must be exactly the map the loader expects",
                expected.mapHash, configured.mapHash);
        assertEquals(expected.map.mapId, configured.map.mapId);
        assertEquals("the client defaults to the test map today",
                DataFiles.bundled().mapHash, configured.mapHash);
    }
}
