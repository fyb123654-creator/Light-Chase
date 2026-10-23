package com.lightchase.contracts.geometry;
import com.lightchase.contracts.*;
public final class WorldGeometry {
 private WorldGeometry() {}
 public static boolean canStand(MapDefinition map, double x, double y, double r) {
  if (x-r<0 || y-r<0 || x+r>map.width || y+r>map.height) return false;
  for (WallCell wall:map.walls) {
   double nx=Math.max(wall.col,Math.min(x,wall.col+1));
   double ny=Math.max(wall.row,Math.min(y,wall.row+1));
   if ((x-nx)*(x-nx)+(y-ny)*(y-ny)<r*r-1e-6) return false;
  }
  return true;
 }
}
