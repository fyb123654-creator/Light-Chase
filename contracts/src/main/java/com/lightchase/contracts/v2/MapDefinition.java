package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record MapDefinition(String mapId,int mapVersion,int width,int height,List<WallCell> walls,Map<Role,SpawnPose> spawns,List<MaterialPoint> materials,List<MapPoint> deliveries,List<MapPoint> exits,List<GroundItem> itemSpawns,List<GroundCard> cardSpawns,List<PortalDefinition> portals) {
    public MapDefinition {
        walls=Collections.unmodifiableList(new ArrayList<>(walls));
        materials=Collections.unmodifiableList(new ArrayList<>(materials));
        deliveries=Collections.unmodifiableList(new ArrayList<>(deliveries));
        exits=Collections.unmodifiableList(new ArrayList<>(exits));
        itemSpawns=Collections.unmodifiableList(new ArrayList<>(itemSpawns));
        cardSpawns=Collections.unmodifiableList(new ArrayList<>(cardSpawns));
        portals=Collections.unmodifiableList(new ArrayList<>(portals));
        spawns=Collections.unmodifiableMap(new EnumMap<>(spawns));
    }
}
