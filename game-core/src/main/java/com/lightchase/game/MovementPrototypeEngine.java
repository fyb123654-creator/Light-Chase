package com.lightchase.game;
import com.lightchase.contracts.*;
import com.lightchase.contracts.geometry.WorldGeometry;
import java.util.*;
/** Internal prototype: movement, wall collision, timeout and abort only.
 * Marking, visibility, tasks and departures intentionally fail fast until A implements them.
 */
final class MovementPrototypeEngine implements MatchEngine {
 private final MapDefinition map;
 private final RuleConfig rules;
 private final MatchInit init;
 private final Map<String,ActorPose> actors=new HashMap<>();
 private final Map<String,InputFrame> inputs=new HashMap<>();
 private long tick;
 private MatchResult result;
 MovementPrototypeEngine(MapDefinition map,RuleConfig rules,MatchInit init) {
  if(init.policePlayerId.equals(init.thiefPlayerId)) throw new IllegalArgumentException("Distinct players required");
  this.map=map; this.rules=rules; this.init=init;
  add(init.policePlayerId,Role.POLICE); add(init.thiefPlayerId,Role.THIEF);
 }
 private void add(String id,Role role) {
  SpawnPose p=map.spawns.get(role);
  if(p==null || !WorldGeometry.canStand(map,p.x,p.y,rules.radius)) throw new IllegalArgumentException("Invalid spawn");
  actors.put(id,new ActorPose(id,p.x,p.y,p.headingRad)); inputs.put(id,InputFrame.ZERO);
 }
 public StepResult step(StepInput step) {
  if(result!=null) return new StepResult(tick,false,true,result);
  if(step.tick!=tick+1) throw new IllegalArgumentException("Non-consecutive tick");
  if(!step.departures.isEmpty()) throw new UnsupportedOperationException("S0: departure rules not implemented");
  // Validate the entire batch before mutating state.
  for(PlayerCommand c:step.commands) {
   requirePlayer(c.playerId);
   new InputFrame(c.moveX,c.moveY,c.interactHeld,c.dropId!=null);
   if(c.interactHeld || c.dropId!=null) throw new UnsupportedOperationException("S0: interaction not implemented");
  }
  for(PlayerCommand c:step.commands) inputs.put(c.playerId,new InputFrame(c.moveX,c.moveY,false,false));
  tick=step.tick;
  for(String id:inputs.keySet()) {
   InputFrame in=inputs.get(id); ActorPose old=actors.get(id);
   double magnitude=Math.max(1,Math.hypot(in.moveX,in.moveY));
   double dx=in.moveX/magnitude*rules.speed/rules.tickRate;
   double dy=in.moveY/magnitude*rules.speed/rules.tickRate;
   int parts=Math.max(1,(int)Math.ceil(Math.hypot(dx,dy)/0.1));
   double x=old.x,y=old.y;
   for(int i=0;i<parts;i++) {
    if(WorldGeometry.canStand(map,x+dx/parts,y,rules.radius)) x+=dx/parts;
    if(WorldGeometry.canStand(map,x,y+dy/parts,rules.radius)) y+=dy/parts;
   }
   double heading=(dx==0&&dy==0)?old.headingRad:Math.atan2(dy,dx);
   if(heading>=Math.PI) heading-=2*Math.PI;
   actors.put(id,new ActorPose(id,x,y,heading));
  }
  if(tick>=rules.durationTicks) end(Role.POLICE,ResultReason.TIMEOUT);
  return new StepResult(tick,false,result!=null,result);
 }
 private void requirePlayer(String id) { if(!actors.containsKey(id)) throw new IllegalArgumentException("Unknown player"); }
 public PlayerView viewFor(String id) {
  requirePlayer(id); Role role=id.equals(init.policePlayerId)?Role.POLICE:Role.THIEF;
  List<OriginStatus> origins=new ArrayList<>();
  List<GroundMaterial> ground=new ArrayList<>();
  for(MaterialPoint m:map.materials) { origins.add(new OriginStatus(m.id,false)); ground.add(new GroundMaterial(m.id,m.x,m.y)); }
  ThiefPrivate thief=role==Role.THIEF?new ThiefPrivate(null,Protection.NORMAL,0,ground,Collections.emptyList(),new InteractionView(null,null,0,false,"NO_TARGET")):null;
  // No opponent coordinates are exposed by this prototype. A adds visibility in S3.
  return new PlayerView(init.matchId,tick,0,0,role,(int)Math.max(0,rules.durationTicks-tick),0,0,false,actors.get(id),null,origins,thief);
 }
 private MatchResult end(Role winner,ResultReason reason) {
  if(result==null) result=new MatchResult(init.matchId+":result",init.matchId,winner,reason,null,(int)tick,0,0,tick);
  return result;
 }
 public MatchResult result() { return result; }
 public MatchResult abort() { return end(null,ResultReason.SERVER_ABORTED); }
}
