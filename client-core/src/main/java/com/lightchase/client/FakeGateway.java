package com.lightchase.client;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import java.util.*;

/** C's offline UI fixture, deliberately not a rule engine or real network match. */
public final class FakeGateway implements ClientGateway {
    private DataFiles data;
    private GatewayListener listener;
    private Role role=Role.THIEF;
    private boolean connected,running;
    private InputFrame input=InputFrame.ZERO;
    private double x,y,elapsed;
    private long tick,revision,requestSeq;
    public void configure(DataFiles data) { this.data=data; }
    public InputFrame lastInput() { return input; }
    public void connect(ConnectionOptions options,GatewayListener listener) {
        if(data==null) throw new IllegalStateException("Configure fixture data first");
        this.listener=listener;connected=true;running=false;input=InputFrame.ZERO;
        listener.onSession(new SessionInfo("offline-session","demo-self"));
        listener.onConnection(new ConnectionStatus("CONNECTED","OFFLINE FIXTURE"));
        room(RoomPhase.LOBBY);
    }
    private void doselectRole(Role role) { if(!connected||running)return;this.role=role;room(RoomPhase.LOBBY); }
    private void dosetReady(boolean ready) {
        if(!connected)return;
        if(!ready) { running=false;input=InputFrame.ZERO;room(RoomPhase.LOBBY);return; }
        SpawnPose p=data.map.spawns.get(role);x=p.x;y=p.y;elapsed=0;tick=0;input=InputFrame.ZERO;running=true;
        room(RoomPhase.RUNNING);emit();
    }
    public void submitInput(InputFrame frame) { input=running?frame:InputFrame.ZERO; }
    /** Called only by the demo renderer. It demonstrates input, without collisions or game rules. */
    public void advance(float seconds) {
        if(!running)return;
        double dt=Math.max(0,Math.min(seconds,0.1));elapsed+=dt;tick=(long)(elapsed*30);
        double length=Math.max(1,Math.hypot(input.moveX,input.moveY));
        x=Math.max(1.5,Math.min(data.map.width-1.5,x+input.moveX/length*3*dt));
        y=Math.max(1.5,Math.min(data.map.height-1.5,y+input.moveY/length*3*dt));
        emit();
    }
    private void emit() {
        List<OriginStatus> origins=new ArrayList<>();List<GroundMaterial> ground=new ArrayList<>();
        for(MaterialPoint m:data.map.materials) { origins.add(new OriginStatus(m.id,false));ground.add(new GroundMaterial(m.id,m.x,m.y)); }
        ThiefPrivate thief=role==Role.THIEF?new ThiefPrivate(null,Protection.NORMAL,0,ground,Collections.emptyList(),new InteractionView(null,null,0,false,"NO_TARGET")):null;
        listener.onView(new PlayerView("offline-demo",tick,0,0,role,5400,0,0,false,new ActorPose("demo-self",x,y,0),null,origins,thief));
    }
    private void room(RoomPhase phase) {
        listener.onRoom(new RoomState("DEMO",++revision,phase,data.map.mapId,data.mapHash,data.configHash,running?"offline-demo":null,0,
            Collections.singletonList(new RoomPlayer("demo-self",role,false,"CONNECTED",false))));
    }
    private void doreturnToLobby() { if(!connected)return;running=false;input=InputFrame.ZERO;room(RoomPhase.LOBBY); }
    public void leaveRoom() { leaveFixture(); }
    public void background() { leaveFixture(); }
    public void close() {
        connected=running=false;input=InputFrame.ZERO;
        if(listener!=null)listener.onSession(null);
        if(listener!=null)listener.onConnection(new ConnectionStatus("CLOSED","Offline fixture closed"));
    }
    public void selectRole(Role role) {
        if(listener==null)return;
        String id="fake-"+(++requestSeq);
        listener.onControl(new ControlStatus(id,"SELECT_ROLE",ControlStatus.State.PENDING,null));
        if(!connected){listener.onControl(new ControlStatus(id,"SELECT_ROLE",ControlStatus.State.FAILED,"NOT_CONNECTED"));return;}
        doselectRole(role);
        listener.onControl(new ControlStatus(id,"SELECT_ROLE",ControlStatus.State.SUCCEEDED,null));
    }
    public void setReady(boolean ready) {
        if(listener==null)return;
        String id="fake-"+(++requestSeq);
        listener.onControl(new ControlStatus(id,"SET_READY",ControlStatus.State.PENDING,null));
        if(!connected){listener.onControl(new ControlStatus(id,"SET_READY",ControlStatus.State.FAILED,"NOT_CONNECTED"));return;}
        dosetReady(ready);
        listener.onControl(new ControlStatus(id,"SET_READY",ControlStatus.State.SUCCEEDED,null));
    }
    public void returnToLobby() {
        if(listener==null)return;
        String id="fake-"+(++requestSeq);
        listener.onControl(new ControlStatus(id,"RETURN_LOBBY",ControlStatus.State.PENDING,null));
        if(!connected){listener.onControl(new ControlStatus(id,"RETURN_LOBBY",ControlStatus.State.FAILED,"NOT_CONNECTED"));return;}
        doreturnToLobby();
        listener.onControl(new ControlStatus(id,"RETURN_LOBBY",ControlStatus.State.SUCCEEDED,null));
    }
    private void leaveFixture() {
        if(listener==null){close();return;}
        String id="fake-"+(++requestSeq);
        listener.onControl(new ControlStatus(id,"LEAVE_ROOM",ControlStatus.State.PENDING,null));
        listener.onControl(new ControlStatus(id,"LEAVE_ROOM",connected?ControlStatus.State.SUCCEEDED:ControlStatus.State.FAILED,connected?null:"NOT_CONNECTED"));
        close();
    }
}
