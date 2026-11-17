package com.lightchase.data;

import com.google.gson.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class V2DataFilesTest {
    private byte[] resource(String path)throws Exception{try(var in=getClass().getResourceAsStream(path)){assertNotNull(in);return in.readAllBytes();}}
    private JsonObject map()throws Exception{return JsonParser.parseString(new String(resource("/maps/extension-v2.json"),StandardCharsets.UTF_8)).getAsJsonObject();}
    private JsonObject rules()throws Exception{return JsonParser.parseString(new String(resource("/rules/extension-v2.json"),StandardCharsets.UTF_8)).getAsJsonObject();}
    private byte[] bytes(JsonObject o){return o.toString().getBytes(StandardCharsets.UTF_8);}
    private void reject(JsonObject map,JsonObject rules){assertThrows(IllegalArgumentException.class,()->V2DataFiles.read(bytes(map),bytes(rules)));}
    @Test public void loadsCompleteFixedMapAndHashesOriginalBytes()throws Exception{
        var data=V2DataFiles.bundled();assertEquals(2,data.map.mapVersion());assertEquals(2,data.map.portals().size());assertEquals(2,data.map.cardSpawns().size());
        assertEquals(DataFiles.hash(resource("/maps/extension-v2.json")),data.mapHash);assertEquals(90,data.rules.shieldTicks());
        assertThrows(UnsupportedOperationException.class,()->data.map.portals().clear());
    }
    @Test public void rejectsMissingOrMisspelledSettings()throws Exception{
        var r=rules();r.remove("shieldTicks");reject(map(),r);r=rules();r.addProperty("shieldTick",90);reject(map(),r);
    }
    @Test public void rejectsInvalidPairingsAndDuplicateIds()throws Exception{
        var m=map();m.getAsJsonArray("cardSpawns").get(1).getAsJsonObject().addProperty("portalId","P1");reject(m,rules());
        m=map();m.getAsJsonArray("cardSpawns").get(1).getAsJsonObject().addProperty("cardId","C1");reject(m,rules());
    }
    @Test public void rejectsBlockedExitAndRoleMismatch()throws Exception{
        var m=map();var exit=m.getAsJsonArray("portals").get(0).getAsJsonObject().getAsJsonObject("exit");exit.addProperty("x",0.5);exit.addProperty("y",0.5);reject(m,rules());
        m=map();m.getAsJsonArray("itemSpawns").get(0).getAsJsonObject().addProperty("ownerRole","THIEF");reject(m,rules());
    }
    @Test public void rejectsDuplicateEndpointsAndUnreachableCard()throws Exception{
        var m=map();var portals=m.getAsJsonArray("portals");portals.get(1).getAsJsonObject().add("entry",portals.get(0).getAsJsonObject().get("exit").deepCopy());reject(m,rules());
        m=map();var card=m.getAsJsonArray("cardSpawns").get(0).getAsJsonObject();card.addProperty("x",10.5);card.addProperty("y",14.5);
        for(int[] xy:new int[][]{{9,14},{11,14},{10,13},{10,15}}){var wall=new JsonObject();wall.addProperty("col",xy[0]);wall.addProperty("row",xy[1]);m.getAsJsonArray("walls").add(wall);}reject(m,rules());
    }
    @Test public void rejectsWrongPrimitiveTypeAndNonPositiveDuration()throws Exception{
        var r=rules();r.addProperty("shieldTicks","90");reject(map(),r);r=rules();r.addProperty("transitTicks",0);reject(map(),r);
    }
}
