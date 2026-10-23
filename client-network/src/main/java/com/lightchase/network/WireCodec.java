package com.lightchase.network;

import com.google.gson.*;
import com.lightchase.contracts.Protocol;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared JSON framing for B's server and client adapter. No gameplay decisions. */
public final class WireCodec {
    public static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("v","type","seq","requestId","matchId","body"));
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    public static final class Message {
        public final int v;
        public final String type, requestId, matchId;
        public final long seq;
        public final JsonObject body;
        private Message(int v, String type, long seq, String requestId, String matchId, JsonObject body) {
            this.v=v; this.type=type; this.seq=seq; this.requestId=requestId; this.matchId=matchId; this.body=body;
        }
    }
    public Message decode(String text) {
        if(text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("MESSAGE_TOO_LARGE");
        JsonElement parsed = JsonParser.parseString(text);
        if(!parsed.isJsonObject()) throw new IllegalArgumentException("BAD_MESSAGE");
        JsonObject o = parsed.getAsJsonObject();
        exactFields(o, "v","type","seq","requestId","matchId","body");
        long seq = integer(o, "seq");
        long version = integer(o, "v");
        if(version>Integer.MAX_VALUE || seq<1 || seq>9007199254740991L) throw new IllegalArgumentException("BAD_MESSAGE");
        if(!o.get("body").isJsonObject()) throw new IllegalArgumentException("BAD_MESSAGE");
        return new Message((int)version, string(o,"type"), seq, nullableString(o,"requestId"), nullableString(o,"matchId"),o.getAsJsonObject("body"));
    }
    public String encode(String type, long seq, String requestId, String matchId, Object body) {
        JsonObject o=new JsonObject();
        o.addProperty("v", Protocol.VERSION); o.addProperty("type",type); o.addProperty("seq",seq);
        o.add("requestId", requestId==null?JsonNull.INSTANCE:new JsonPrimitive(requestId));
        o.add("matchId", matchId==null?JsonNull.INSTANCE:new JsonPrimitive(matchId));
        o.add("body",gson.toJsonTree(body));
        return gson.toJson(o);
    }
    public static void exactFields(JsonObject o,String... names) {
        Set<String> expected=new HashSet<>(Arrays.asList(names));
        if(!o.keySet().equals(expected)) throw new IllegalArgumentException("BAD_MESSAGE");
    }
    public static String string(JsonObject o,String key) {
        JsonElement e=o.get(key);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("BAD_MESSAGE");
        return e.getAsString();
    }
    public static String nullableString(JsonObject o,String key) {
        JsonElement e=o.get(key);
        if(e==null) throw new IllegalArgumentException("BAD_MESSAGE");
        return e.isJsonNull()?null:string(o,key);
    }
    public static long integer(JsonObject o,String key) {
        JsonElement e=o.get(key);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("BAD_MESSAGE");
        try { return e.getAsBigDecimal().longValueExact(); }
        catch(ArithmeticException ex) { throw new IllegalArgumentException("BAD_MESSAGE"); }
    }
    public static boolean bool(JsonObject o,String key) {
        JsonElement e=o.get(key);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("BAD_MESSAGE");
        return e.getAsBoolean();
    }
    public <T> T body(Message m,Class<T> type) { return gson.fromJson(m.body,type); }
}
