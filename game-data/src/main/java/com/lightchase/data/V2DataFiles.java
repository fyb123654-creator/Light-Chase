package com.lightchase.data;

import com.google.gson.*;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.geometry.WorldGeometry;
import com.lightchase.contracts.v2.*;
import java.io.*;
import java.util.*;

/** Opt-in R3 resources, validated before sharing the same hashes with both endpoints. */
public final class V2DataFiles {
    public final MapDefinition map;
    public final RuleConfig rules;
    public final String mapHash,configHash;
    private static final String[] EXTRA_RULES={"speedBoostMultiplier","lightBoostMultiplier","speedBoostTicks","lightBoostTicks","shieldTicks","transitTicks","itemPickupTicks","cardPickupTicks","portalUnlockTicks","portalEnterTicks"};
    private V2DataFiles(byte[] mapBytes,byte[] ruleBytes){
        JsonObject m=StrictJson.parseObject(mapBytes,"map"),r=StrictJson.parseObject(ruleBytes,"rules");
        JsonObject legacyMap=m.deepCopy(),legacyRules=r.deepCopy();
        for(String k:List.of("itemSpawns","cardSpawns","portals"))legacyMap.remove(k);
        for(String k:EXTRA_RULES)legacyRules.remove(k);
        // Reuse strict, proven base validation rather than duplicating its rules.
        DataFiles base=DataFiles.read(bytes(legacyMap),bytes(legacyRules));
        if(m.size()!=legacyMap.size()+3||r.size()!=legacyRules.size()+EXTRA_RULES.length)bad();
        for(String k:EXTRA_RULES){if(k.endsWith("Multiplier")){double v=StrictJson.requireDouble(r,k,"rules");if(v<=1)bad();}
            else if(StrictJson.requireInt(r,k,"rules")<=0)bad();}
        for(JsonElement e:StrictJson.requireArray(m,"itemSpawns","map")){JsonObject o=StrictJson.asObject(e,"item");StrictJson.requireExactKeys(o,"item","itemId","type","ownerRole","x","y");strings(o,"itemId","type","ownerRole");point(o);}
        for(JsonElement e:StrictJson.requireArray(m,"cardSpawns","map")){JsonObject o=StrictJson.asObject(e,"card");StrictJson.requireExactKeys(o,"card","cardId","portalId","x","y");strings(o,"cardId","portalId");point(o);}
        for(JsonElement e:StrictJson.requireArray(m,"portals","map")){JsonObject o=StrictJson.asObject(e,"portal");StrictJson.requireExactKeys(o,"portal","portalId","entry","exit");strings(o,"portalId");
            for(String k:List.of("entry","exit")){JsonObject pos=StrictJson.requireObject(o,k,"portal");StrictJson.requireExactKeys(pos,k,"x","y");point(pos);}}
        Gson gson=new Gson();map=gson.fromJson(m,MapDefinition.class);rules=gson.fromJson(r,RuleConfig.class);
        if(map.mapVersion()!=2||map.portals().size()!=2||map.cardSpawns().size()!=2)bad();
        Set<String> ids=new HashSet<>();for(var p:map.materials())unique(ids,p.id);for(var p:map.deliveries())unique(ids,p.id);for(var p:map.exits())unique(ids,p.id);
        Set<String> portalIds=new HashSet<>(),endpoints=new HashSet<>();
        Set<Long> reachable=MapTopology.reachableFrom(base.map,(int)Math.floor(map.spawns().get(Role.THIEF).x),(int)Math.floor(map.spawns().get(Role.THIEF).y));
        for(var p:map.portals()){unique(ids,p.portalId());portalIds.add(p.portalId());for(var pos:List.of(p.entry(),p.exit())){if(!endpoints.add(pos.x()+":"+pos.y()))bad();stand(base,pos.x(),pos.y());reachable(reachable,pos.x(),pos.y());}}
        Set<String> paired=new HashSet<>();for(var c:map.cardSpawns()){unique(ids,c.cardId());if(!portalIds.contains(c.portalId())||!paired.add(c.portalId()))bad();stand(base,c.x(),c.y());reachable(reachable,c.x(),c.y());}
        for(var i:map.itemSpawns()){unique(ids,i.itemId());if(i.type()==null||i.ownerRole()==null||i.type()==ItemType.LIGHT_BOOST&&i.ownerRole()!=Role.POLICE||i.type()==ItemType.SHIELD&&i.ownerRole()!=Role.THIEF)bad();stand(base,i.x(),i.y());reachable(reachable,i.x(),i.y());}
        mapHash=DataFiles.hash(mapBytes);configHash=DataFiles.hash(ruleBytes);
    }
    public static V2DataFiles read(byte[] map,byte[] rules){return new V2DataFiles(map,rules);}
    public static V2DataFiles bundled(){return read(resource("/maps/extension-v2.json"),resource("/rules/extension-v2.json"));}
    private static void strings(JsonObject o,String... keys){for(String k:keys)id(StrictJson.requireString(o,k,"extension"));}
    private static void point(JsonObject o){StrictJson.requireDouble(o,"x","point");StrictJson.requireDouble(o,"y","point");}
    private static void stand(DataFiles base,double x,double y){if(!WorldGeometry.canStand(base.map,x,y,base.rules.radius))bad();}
    private static void reachable(Set<Long> cells,double x,double y){if(!cells.contains(MapTopology.key((int)Math.floor(x),(int)Math.floor(y))))bad();}
    private static void unique(Set<String> set,String id){id(id);if(!set.add(id))bad();}
    private static void id(String id){if(id==null||id.isEmpty()||id.length()>64)bad();}
    private static byte[] bytes(JsonObject o){return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private static byte[] resource(String path){try(InputStream in=V2DataFiles.class.getResourceAsStream(path)){if(in==null)throw new IllegalStateException(path);return in.readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}}
    private static void bad(){throw new IllegalArgumentException("INVALID_V2_CONFIG");}
}
