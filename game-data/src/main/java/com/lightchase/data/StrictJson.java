package com.lightchase.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small strict JSON gate used before Gson turns map/rules data into DTOs.
 * Gson alone silently turns missing fields into 0/null and ignores unknown fields,
 * so the data files are checked for exact keys and primitive types first.
 */
final class StrictJson {
    private StrictJson() {
    }

    static JsonObject parseObject(byte[] bytes, String what) {
        if (bytes == null) {
            throw new IllegalArgumentException(what + ": missing bytes");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);
                JsonElement root = readValue(reader, what, 0);
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IllegalArgumentException(what + ": invalid JSON: trailing content");
                }
                return asObject(root, what);
            }
        } catch (IOException | JsonParseException | NumberFormatException e) {
            throw new IllegalArgumentException(what + ": invalid JSON/UTF-8: " + e.getMessage(), e);
        }
    }

    // Read before constructing objects: JsonObject.add would otherwise overwrite duplicate keys.
    private static JsonElement readValue(JsonReader reader, String path, int depth) throws IOException {
        if (depth > 64) throw new IllegalArgumentException(path + ": invalid JSON: nesting exceeds 64");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException(path + ": duplicate field '" + key + "'");
                    object.add(key, readValue(reader, path + "." + key, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader, path + "[" + array.size() + "]", depth + 1));
                reader.endArray();
                return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IllegalArgumentException(path + ": invalid JSON: expected value");
        }
    }

    static JsonObject asObject(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(path + ": expected object");
        }
        return element.getAsJsonObject();
    }

    static JsonObject requireObject(JsonObject parent, String key, String path) {
        return asObject(require(parent, key, path), path + "." + key);
    }

    static JsonArray requireArray(JsonObject parent, String key, String path) {
        JsonElement element = require(parent, key, path);
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(path + "." + key + ": expected array");
        }
        return element.getAsJsonArray();
    }

    static String requireString(JsonObject parent, String key, String path) {
        JsonElement element = require(parent, key, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + "." + key + ": expected string");
        }
        return element.getAsString();
    }

    static int requireInt(JsonObject parent, String key, String path) {
        JsonElement element = require(parent, key, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + "." + key + ": expected integer");
        }
        try {
            BigDecimal number = element.getAsBigDecimal();
            if (number.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException(path + "." + key + ": expected integer");
            }
            return number.intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(path + "." + key + ": expected 32-bit integer", e);
        }
    }

    static double requireDouble(JsonObject parent, String key, String path) {
        JsonElement element = require(parent, key, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + "." + key + ": expected number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(path + "." + key + ": must be finite");
        }
        return value;
    }

    static void requireExactKeys(JsonObject object, String path, String... keys) {
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(keys));
        for (String key : expected) {
            if (!object.has(key)) {
                throw new IllegalArgumentException(path + ": missing field '" + key + "'");
            }
        }
        for (String key : object.keySet()) {
            if (!expected.contains(key)) {
                throw new IllegalArgumentException(path + ": unknown field '" + key + "'");
            }
        }
    }

    private static JsonElement require(JsonObject parent, String key, String path) {
        if (!parent.has(key)) {
            throw new IllegalArgumentException(path + ": missing field '" + key + "'");
        }
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(path + "." + key + ": must not be null");
        }
        return element;
    }
}
