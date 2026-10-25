package com.lightchase.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.lightchase.network.WireCodec;
import java.util.*;

/** Only legacy non-health tests use this: retain all business broadcasts and identical replays. */
final class HealthBroadcastFilter {
    private final WireCodec codec = new WireCodec();
    private JsonObject previous;

    boolean healthOnly(String text) {
        WireCodec.Message message = codec.decodeServer(text);
        if (!"ROOM_STATE".equals(message.type)) return false;
        JsonObject current = message.body;
        boolean ignore = previous != null && !connections(previous).equals(connections(current))
                && business(previous).equals(business(current));
        previous = current.deepCopy();
        return ignore;
    }
    private static List<String> connections(JsonObject room) {
        List<String> states = new ArrayList<>();
        for (JsonElement player : room.getAsJsonArray("players")) states.add(player.getAsJsonObject().get("connection").getAsString());
        return states;
    }
    private static JsonObject business(JsonObject room) {
        JsonObject body = room.deepCopy(); body.remove("revision");
        for (JsonElement player : body.getAsJsonArray("players")) player.getAsJsonObject().remove("connection");
        return body;
    }
}
