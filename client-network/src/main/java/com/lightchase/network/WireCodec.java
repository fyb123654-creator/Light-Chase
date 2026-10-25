package com.lightchase.network;

import com.google.gson.*;
import com.lightchase.contracts.Protocol;
import java.util.*;

/** Shared JSON framing for B's server and client adapter. No gameplay decisions. */
public final class WireCodec {
    public static final int MAX_BYTES = 64 * 1024;
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;
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
        JsonObject o = WireJson.parse(text);
        exactFields(o, "v","type","seq","requestId","matchId","body");
        long seq = integer(o, "seq");
        long version = integer(o, "v");
        if(version<1 || version>Integer.MAX_VALUE || seq<1 || seq>MAX_SAFE_INTEGER) throw new IllegalArgumentException("BAD_MESSAGE");
        if(!o.get("body").isJsonObject()) throw new IllegalArgumentException("BAD_MESSAGE");
        String request = nullableString(o,"requestId");
        if(request != null && (request.isEmpty() || request.length() > 64)) throw new IllegalArgumentException("BAD_MESSAGE");
        return new Message((int)version, string(o,"type"), seq, request, nullableString(o,"matchId"),o.getAsJsonObject("body"));
    }

    /** Server entry: decode a message sent by a client, before applying session/phase rules. */
    public Message decodeClient(String text) {
        Message m = decode(text);
        validateClient(m);
        return m;
    }

    /** Client entry: decode a message sent by the server, before applying local state rules. */
    public Message decodeServer(String text) {
        Message m = decode(text);
        validateServer(m);
        return m;
    }

    public void validateClient(Message m) {
        // Leave other-version bodies uninterpreted so the receiver can report VERSION_MISMATCH.
        if(m.v == Protocol.VERSION) MessageSchema.client(m);
    }

    public void validateServer(Message m) {
        if(m.v == Protocol.VERSION) MessageSchema.server(m);
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
        catch(ArithmeticException | NumberFormatException ex) { throw new IllegalArgumentException("BAD_MESSAGE", ex); }
    }
    public static boolean bool(JsonObject o,String key) {
        JsonElement e=o.get(key);
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("BAD_MESSAGE");
        return e.getAsBoolean();
    }
    public <T> T body(Message m,Class<T> type) { BodyValidator.validate(m.body,type); return gson.fromJson(m.body,type); }
}
