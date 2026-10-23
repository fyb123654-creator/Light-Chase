package com.lightchase.contracts;
public final class MapDefinition {
public final String mapId;
public final int mapVersion;
public final int width;
public final int height;
public final java.util.List<WallCell> walls;
public final java.util.Map<Role, SpawnPose> spawns;
public final java.util.List<MaterialPoint> materials;
public final java.util.List<MapPoint> deliveries;
public final java.util.List<MapPoint> exits;
public MapDefinition(String mapId, int mapVersion, int width, int height, java.util.List<WallCell> walls, java.util.Map<Role, SpawnPose> spawns, java.util.List<MaterialPoint> materials, java.util.List<MapPoint> deliveries, java.util.List<MapPoint> exits) {
this.mapId=mapId;
this.mapVersion=mapVersion;
this.width=width;
this.height=height;
this.walls=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(walls));
this.spawns=java.util.Collections.unmodifiableMap(new java.util.HashMap<>(spawns));
this.materials=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(materials));
this.deliveries=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(deliveries));
this.exits=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(exits));
}
}
