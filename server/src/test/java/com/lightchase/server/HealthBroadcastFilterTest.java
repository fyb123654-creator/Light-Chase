package com.lightchase.server;

import com.google.gson.*;
import com.lightchase.contracts.*;
import com.lightchase.network.WireCodec;
import java.util.*;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Proves legacy helpers cannot discard business changes together with a health change. */
public class HealthBroadcastFilterTest {
    private final WireCodec codec = new WireCodec();
    @Test public void firstRoomAndIdenticalReplaysAreAlwaysRetained() {
        HealthBroadcastFilter filter = new HealthBroadcastFilter(); JsonObject body = body();
        assertFalse(filter.healthOnly(wire(body))); assertFalse(filter.healthOnly(wire(body)));
        body.addProperty("revision", 2); assertFalse(filter.healthOnly(wire(body)));
    }
    @Test public void onlyHealthTransitionIsSeparated() {
        HealthBroadcastFilter filter = new HealthBroadcastFilter(); JsonObject body = body();
        assertFalse(filter.healthOnly(wire(body))); body.addProperty("revision", 2);
        body.getAsJsonArray("players").get(0).getAsJsonObject().addProperty("connection", "SUSPECT");
        assertTrue(filter.healthOnly(wire(body))); assertFalse(filter.healthOnly(wire(body)));
    }
    @Test public void everyBusinessFieldStillReachesTheOriginalAssertions() {
        List<Consumer<JsonObject>> changes = Arrays.asList(
                v -> v.addProperty("roomId", "OTHER"), v -> v.addProperty("phase", "FINISHED"),
                v -> v.addProperty("mapId", "other"), v -> v.addProperty("mapHash", "other"),
                v -> v.addProperty("configHash", "other"), v -> v.addProperty("matchId", "other"),
                v -> v.addProperty("countdownRemainingMs", 2000), v -> v.getAsJsonArray("players").remove(1),
                v -> self(v).addProperty("playerId", "other"), v -> self(v).addProperty("role", "THIEF"),
                v -> self(v).addProperty("ready", false), v -> self(v).addProperty("returned", true));
        for (Consumer<JsonObject> change : changes) {
            HealthBroadcastFilter filter = new HealthBroadcastFilter(); JsonObject body = body();
            assertFalse(filter.healthOnly(wire(body))); body.addProperty("revision", 2);
            self(body).addProperty("connection", "SUSPECT"); change.accept(body);
            assertFalse("Business changes must not be swallowed", filter.healthOnly(wire(body)));
        }
    }
    private JsonObject self(JsonObject body) { return body.getAsJsonArray("players").get(0).getAsJsonObject(); }
    private JsonObject body() {
        RoomState state = new RoomState("TEST", 1, RoomPhase.RUNNING, "map", "hash", "config", "m-1", 0,
                Arrays.asList(new RoomPlayer("p-1", Role.POLICE, true, "CONNECTED", false),
                        new RoomPlayer("p-2", Role.THIEF, true, "CONNECTED", false)));
        return codec.decode(codec.encode("ROOM_STATE", 1, null, null, state)).body;
    }
    private String wire(JsonObject body) { return codec.encode("ROOM_STATE", 1, null, null, body); }
}
