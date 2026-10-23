package com.lightchase.contracts.geometry;
import com.lightchase.contracts.*;
/** Shared pure geometry used by game-core (rules) and client-core (drawn beam, map).
 * Deterministic, no I/O, no clock. Server decides visibility; client may only reuse the
 * same math for rendering, never to override the server's opponent decision. */
public final class WorldGeometry {
 private WorldGeometry() {}
 public static final double EPS = 1e-6;
 /** Circle of radius r centred at (x,y) stays inside the map and out of every wall square. */
 public static boolean canStand(MapDefinition map, double x, double y, double r) {
  if (x-r<0 || y-r<0 || x+r>map.width || y+r>map.height) return false;
  for (WallCell wall:map.walls) {
   double nx=Math.max(wall.col,Math.min(x,wall.col+1));
   double ny=Math.max(wall.row,Math.min(y,wall.row+1));
   if ((x-nx)*(x-nx)+(y-ny)*(y-ny)<r*r-EPS) return false;
  }
  return true;
 }
 public static double distance(double x1,double y1,double x2,double y2) {
  return Math.hypot(x2-x1,y2-y1);
 }
 /** True when segment (x1,y1)-(x2,y2) touches or crosses any wall square (closed cells).
  * Touching a corner counts as blocked: sight is not allowed around a grazing corner. */
 public static boolean segmentBlocked(MapDefinition map,double x1,double y1,double x2,double y2) {
  for (WallCell wall:map.walls) {
   if (segmentHitsSquare(x1,y1,x2,y2,wall.col,wall.row,wall.col+1,wall.row+1)) return true;
  }
  return false;
 }
 /** Police beam: distance <= range, |angle from heading| <= halfAngleRad, wall not blocking.
  * Zero distance counts as inside the angle (rule doc 01 §6). Boundary comparisons use EPS. */
 public static boolean inBeam(MapDefinition map,double px,double py,double headingRad,
                              double tx,double ty,double range,double halfAngleRad) {
  double dx=tx-px,dy=ty-py,len=Math.hypot(dx,dy);
  if (len>range+EPS) return false;
  if (len>EPS) {
   double cos=(Math.cos(headingRad)*dx+Math.sin(headingRad)*dy)/len;
   double angle=Math.acos(Math.max(-1,Math.min(1,cos)));
   if (angle>halfAngleRad+EPS) return false;
  }
  return !segmentBlocked(map,px,py,tx,ty);
 }
 /** Thief looks at the police anywhere inside the 8 x (8*16/9) view rectangle, walls ignored. */
 public static boolean inViewRect(double px,double py,double tx,double ty,double halfWidth,double halfHeight) {
  return Math.abs(tx-px)<=halfWidth+EPS && Math.abs(ty-py)<=halfHeight+EPS;
 }
 /** Liang-Barsky segment vs closed axis-aligned rectangle; touching counts as a hit. */
 static boolean segmentHitsSquare(double x1,double y1,double x2,double y2,
                                  double left,double bottom,double right,double top) {
  double dx=x2-x1,dy=y2-y1;
  double t0=0.0,t1=1.0;
  double[] p={-dx,dx,-dy,dy};
  double[] q={x1-left,right-x1,y1-bottom,top-y1};
  for (int i=0;i<4;i++) {
   if (Math.abs(p[i])<EPS) { if (q[i]<-EPS) return false; continue; }
   double t=q[i]/p[i];
   if (p[i]<0) { if (t>t1) return false; if (t>t0) t0=t; }
   else { if (t<t0) return false; if (t<t1) t1=t; }
  }
  return t0<=t1+EPS;
 }
}
