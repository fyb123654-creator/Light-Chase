package com.lightchase.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

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
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(what + ": invalid JSON: " + e.getMessage(), e);
        }
        return asObject(root, what);
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
