package com.lightchase.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DataFilesTest {
    private static byte[] resource(String path) {
        try (InputStream in = DataFilesTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] mapBytes() {
        return resource("/maps/test-room.json");
    }

    private static byte[] configBytes() {
        return resource("/rules/v1.json");
    }

    private static JsonObject mapJson() {
        return JsonParser.parseString(new String(mapBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject configJson() {
        return JsonParser.parseString(new String(configBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static DataFiles read(JsonObject map, JsonObject config) {
        return DataFiles.read(
                map.toString().getBytes(StandardCharsets.UTF_8),
                config.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String error(JsonObject map, JsonObject config) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> read(map, config));
        assertNotNull(exception.getMessage());
        return exception.getMessage();
    }

    private static JsonObject wall(int col, int row) {
        JsonObject wall = new JsonObject();
        wall.addProperty("col", col);
        wall.addProperty("row", row);
        return wall;
    }

    @Test
    public void bundledDataLoads() {
        DataFiles data = DataFiles.bundled();
        assertEquals("test-room", data.map.mapId);
        assertEquals(24, data.map.width);
        assertEquals(18, data.map.height);
        assertEquals(3, data.map.materials.size());
        assertEquals(3, data.map.deliveries.size());
        assertEquals(2, data.map.exits.size());
        assertEquals(64, data.mapHash.length());
        assertEquals(64, data.configHash.length());
    }

    @Test
    public void validFixtureLoads() {
        DataFiles data = read(mapJson(), configJson());
        assertEquals("test-room", data.map.mapId);
        assertEquals(3, data.rules.maxMarks);
    }

    @Test
    public void hashIsStable() {
        DataFiles first = DataFiles.read(mapBytes(), configBytes());
        DataFiles second = DataFiles.read(mapBytes(), configBytes());
        assertEquals(first.mapHash, second.mapHash);
        assertEquals(first.configHash, second.configHash);
        assertEquals(DataFiles.hash(mapBytes()), first.mapHash);
        assertEquals(DataFiles.hash(configBytes()), first.configHash);
    }

    @Test
    public void unknownTopLevelFieldRejected() {
        JsonObject map = mapJson();
        map.addProperty("extra", 1);
        assertTrue(error(map, configJson()).contains("extra"));
    }

    @Test
    public void missingRequiredFieldRejected() {
        JsonObject map = mapJson();
        map.remove("mapId");
        assertTrue(error(map, configJson()).contains("mapId"));
    }

    @Test
    public void wrongPrimitiveTypeRejected() {
        JsonObject map = mapJson();
        map.addProperty("width", "24");
        assertTrue(error(map, configJson()).contains("width"));
    }

    @Test
    public void duplicateWallRejected() {
        JsonObject map = mapJson();
        JsonArray walls = map.getAsJsonArray("walls");
        JsonElement copy = JsonParser.parseString(walls.get(0).toString());
        walls.add(copy);
        assertTrue(error(map, configJson()).contains("duplicate"));
    }

    @Test
    public void wallOutOfBoundsRejected() {
        JsonObject map = mapJson();
        map.getAsJsonArray("walls").add(wall(24, 0));
        assertTrue(error(map, configJson()).contains("outside"));
    }

    @Test
    public void spawnInsideWallRejected() {
        JsonObject map = mapJson();
        JsonObject police = map.getAsJsonObject("spawns").getAsJsonObject("POLICE");
        police.addProperty("x", 12.5);
        police.addProperty("y", 4.5);
        assertTrue(error(map, configJson()).contains("spawns.POLICE"));
    }

    @Test
    public void materialDeliveryMismatchRejected() {
        JsonObject map = mapJson();
        map.getAsJsonArray("materials").get(0).getAsJsonObject().addProperty("deliveryId", "D9");
        assertTrue(error(map, configJson()).contains("D9"));
    }

    @Test
    public void missingExitRejected() {
        JsonObject map = mapJson();
        map.getAsJsonArray("exits").remove(0);
        assertTrue(error(map, configJson()).contains("exits"));
    }

    @Test
    public void wrongMapSizeRejected() {
        JsonObject map = mapJson();
        map.addProperty("width", 23);
        assertTrue(error(map, configJson()).contains("24x18"));
    }

    @Test
    public void blankMapIdRejected() {
        JsonObject map = mapJson();
        map.addProperty("mapId", "  ");
        assertTrue(error(map, configJson()).contains("mapId"));
    }

    @Test
    public void invalidRuleValueRejected() {
        JsonObject config = configJson();
        config.addProperty("tickRate", 0);
        assertTrue(error(mapJson(), config).contains("tickRate"));
    }

    @Test
    public void nonV1TickRateRejected() {
        JsonObject config = configJson();
        config.addProperty("tickRate", 60);
        assertTrue(error(mapJson(), config).contains("tickRate"));
    }

    @Test
    public void unknownNestedFieldRejected() {
        JsonObject map = mapJson();
        map.getAsJsonArray("walls").get(0).getAsJsonObject().addProperty("color", "red");
        assertTrue(error(map, configJson()).contains("unknown field"));
    }

    @Test
    public void unreachableTargetRejected() {
        JsonObject map = mapJson();
        JsonArray walls = map.getAsJsonArray("walls");
        // E2 sits in cell (21,2); wall its four neighbours so it is cut off.
        walls.add(wall(20, 2));
        walls.add(wall(22, 2));
        walls.add(wall(21, 1));
        walls.add(wall(21, 3));
        assertTrue(error(map, configJson()).contains("not connected"));
    }

    @Test
    public void badJsonRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> DataFiles.read("{".getBytes(StandardCharsets.UTF_8), configBytes()));
        assertTrue(exception.getMessage().contains("invalid JSON"));
    }

    @Test
    public void nullBytesRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> DataFiles.read(null, configBytes()));
        assertTrue(exception.getMessage().contains("missing bytes"));
    }

    @Test public void swappedDeliveryBindingsRejected() {
        JsonObject map=mapJson();
        map.getAsJsonArray("materials").get(0).getAsJsonObject().addProperty("deliveryId","D2");
        map.getAsJsonArray("materials").get(1).getAsJsonObject().addProperty("deliveryId","D1");
        assertTrue(error(map,configJson()).contains("must bind to"));
    }
    @Test public void duplicateJsonKeyRejectedBeforeMapping() {
        String json=new String(mapBytes(),StandardCharsets.UTF_8);
        json=json.replaceFirst("\\{","{\"mapId\":\"overwritten\",");
        final byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,()->DataFiles.read(bytes,configBytes()));
    }
    @Test public void nestedDuplicateKeyRejected() {
        assertThrows(IllegalArgumentException.class,()->StrictJson.parseObject(
                "{\"nested\":{\"x\":1,\"x\":2}}".getBytes(StandardCharsets.UTF_8),"map"));
    }
    @Test public void nonStandardJsonRejected() {
        for(String text:new String[]{"{unquoted:1}","{\"x\":1/*comment*/}","{\"x\":NaN}","{} {}"}) {
            assertThrows(text,IllegalArgumentException.class,()->StrictJson.parseObject(text.getBytes(StandardCharsets.UTF_8),"map"));
        }
    }
    @Test public void invalidUtf8Rejected() {
        byte[] bytes=new byte[]{'{','"','x','"',':','"',(byte)0xc3,0x28,'"','}'};
        assertThrows(IllegalArgumentException.class,()->StrictJson.parseObject(bytes,"map"));
    }
    @Test public void validUtf8AndNestedJsonRetained() {
        JsonObject parsed=StrictJson.parseObject("{\"name\":\"地图\",\"nested\":[true,null,1.5]}".getBytes(StandardCharsets.UTF_8),"map");
        assertEquals("地图",parsed.get("name").getAsString());
        assertEquals(3,parsed.getAsJsonArray("nested").size());
    }
    @Test public void excessiveNestingRejected() {
        StringBuilder json=new StringBuilder();
        for(int i=0;i<70;i++) json.append("{\"a\":");
        json.append("1");
        for(int i=0;i<70;i++) json.append('}');
        assertThrows(IllegalArgumentException.class,()->StrictJson.parseObject(
                json.toString().getBytes(StandardCharsets.UTF_8),"map"));
    }
}
