package com.lightchase.network;

import com.google.gson.*;
import com.lightchase.contracts.v2.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class V2WireCodecTest {
    private final V2WireCodec codec=new V2WireCodec();
    private Path fixtures(){return Path.of(System.getProperty("lightchase.repoRoot"),"docs/development/fixtures/v2");}
    private String fixture(String name)throws Exception{return Files.readString(fixtures().resolve(name),StandardCharsets.UTF_8);}
    private JsonObject object(String name)throws Exception{return JsonParser.parseString(fixture(name)).getAsJsonObject();}
    private void reject(JsonObject o,boolean client){assertThrows(IllegalArgumentException.class,()->{if(client)codec.decodeClient(o.toString());else codec.decodeServer(o.toString());});}
    @Test public void everyDocumentedPacketDecodesAndTypedBodiesRoundTrip()throws Exception{
        int count=0;try(var files=Files.list(fixtures())){for(Path file:files.filter(p->p.toString().endsWith(".json")).toList()){
            if(file.getFileName().toString().equals("scenario-expectations.json"))continue;
            String text=Files.readString(file);String type=JsonParser.parseString(text).getAsJsonObject().get("type").getAsString();boolean client=Set.of("INPUT","USE_ITEM").contains(type);
            var m=client?codec.decodeClient(text):codec.decodeServer(text);
            Object body=switch(type){case "INPUT"->codec.body(m,InputBody.class);case "USE_ITEM"->codec.body(m,UseItem.class);case "SNAPSHOT"->codec.body(m,PlayerView.class);case "ACTION_RESULT"->codec.body(m,ActionResult.class);case "PUBLIC_EVENT"->codec.body(m,PublicEvent.class);default->throw new AssertionError(type);};
            String encoded=client?codec.encodeClient(type,m.seq,m.requestId,m.matchId,body):codec.encodeServer(type,m.seq,m.requestId,m.matchId,body);
            assertEquals(file.toString(),JsonParser.parseString(text),JsonParser.parseString(encoded));count++;
        }}assertEquals(15,count);
    }
    @Test public void rejectsMissingUnknownAndCoercedFields()throws Exception{
        var o=object("input-police.json");o.getAsJsonObject("body").remove("aimRad");reject(o,true);
        o=object("input-police.json");o.getAsJsonObject("body").addProperty("playerId","cheat");reject(o,true);
        o=object("input-police.json");o.getAsJsonObject("body").addProperty("moveX","1");reject(o,true);
        o=object("snapshot-thief-locked.json");o.getAsJsonObject("body").getAsJsonObject("thief").addProperty("extra",1);reject(o,false);
    }
    @Test public void rejectsWrongVersionsAndDirections()throws Exception{
        var o=object("input-police.json");o.addProperty("v",1);reject(o,true);
        String input=fixture("input-police.json");assertThrows(IllegalArgumentException.class,()->codec.decodeServer(input));
        String snapshot=fixture("snapshot-police-locked.json");assertThrows(IllegalArgumentException.class,()->codec.decodeClient(snapshot));
    }
    @Test public void actionCorrelationAndSlotCannotBeForged()throws Exception{
        var o=object("use-shield.json");o.addProperty("requestId","a:99");reject(o,true);
        o=object("use-shield.json");o.getAsJsonObject("body").addProperty("slot",2);reject(o,true);
        o=object("action-success.json");o.getAsJsonObject("body").addProperty("success",false);reject(o,false);
    }
    @Test public void enforcesVisibilityAndPrivateInventory()throws Exception{
        var o=object("snapshot-police-locked.json");var thief=object("snapshot-thief-locked.json").getAsJsonObject("body").get("thief");o.getAsJsonObject("body").add("thief",thief);reject(o,false);
        o=object("snapshot-thief-two-shields.json");o.getAsJsonObject("body").getAsJsonArray("inventory").add(JsonNull.INSTANCE);reject(o,false);
        o=object("snapshot-thief-two-shields.json");o.getAsJsonObject("body").getAsJsonObject("thief").addProperty("invisibleUntilTick",299);reject(o,false);
        o=object("snapshot-thief-transit.json");o.getAsJsonObject("body").getAsJsonObject("self").addProperty("x",20);reject(o,false);
    }
    @Test public void rejectsDuplicateKeysAndOversizedPayloads()throws Exception{
        String input=fixture("input-police.json");assertThrows(IllegalArgumentException.class,()->codec.decodeClient(input.replace("\"v\": 2","\"v\": 2,\"v\": 2")));
        assertThrows(IllegalArgumentException.class,()->codec.decodeClient(" ".repeat(65537)));
    }
    @Test public void dtoCollectionsAreImmutableAndNullableSlotsSurvive()throws Exception{
        var v=codec.body(codec.decodeServer(fixture("snapshot-thief-locked.json")),PlayerView.class);
        assertNull(v.inventory().get(0));assertThrows(UnsupportedOperationException.class,()->v.inventory().set(0,new ItemInstance("x",ItemType.SHIELD)));
        assertThrows(UnsupportedOperationException.class,()->v.groundCards().clear());
    }
    @Test public void rejectsInvalidAimAndNonFiniteNumbers()throws Exception{
        var o=object("input-police.json");o.getAsJsonObject("body").addProperty("aimRad",Math.PI*2);reject(o,true);
        assertThrows(IllegalArgumentException.class,()->new InputFrame(Double.NaN,0,null,false,false));
        assertThrows(IllegalArgumentException.class,()->codec.decodeClient(fixtureUnchecked().replace("1.0","1e999")));
    }
    private String fixtureUnchecked(){try{return fixture("input-police.json");}catch(Exception e){throw new AssertionError(e);}}
}
