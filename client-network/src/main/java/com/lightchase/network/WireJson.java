package com.lightchase.network;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/** Strict framing before Gson can discard duplicate keys or coerce malformed JSON. */
final class WireJson {
    private WireJson() {}

    static JsonObject parse(String text) {
        if (text == null) throw bad();
        if (text.getBytes(StandardCharsets.UTF_8).length > WireCodec.MAX_BYTES) {
            throw new IllegalArgumentException("MESSAGE_TOO_LARGE");
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = read(reader, 0);
            if (!root.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw bad();
            return root.getAsJsonObject();
        } catch (IOException | JsonParseException | NumberFormatException ex) {
            throw new IllegalArgumentException("BAD_MESSAGE", ex);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        // All v1 messages are shallow; bound malformed input before recursive DTO validation.
        if (depth > 64) throw bad();
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw bad();
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw bad();
        }
    }

    private static IllegalArgumentException bad() { return new IllegalArgumentException("BAD_MESSAGE"); }
}
