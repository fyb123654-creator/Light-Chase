package com.lightchase.network;

import com.lightchase.contracts.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/** B's real transport adapter. The S0 server supports a lobby, not a playable match yet. */
public final class WebSocketClientGateway implements ClientGateway {
    private final WireCodec codec=new WireCodec();
    private WebSocketClient socket;
    private ScheduledExecutorService clock;
    private GatewayListener listener;
    private final ControlRequests requests=new ControlRequests(s -> listener.onControl(s));
    private ConnectionOptions options;
    private long seq, requestSeq, lastServerSeq, inputSeq, dropSeq;
    private long lastPacket, lastInput, lastHeartbeat, lastSend;
    private boolean welcomed, suspect, running;
    private String matchId, resultId;
    private InputFrame frame=InputFrame.ZERO;
    private long now() { return System.nanoTime()/1_000_000; }

    @Override public synchronized void connect(ConnectionOptions o,GatewayListener l) {
        if(socket!=null) { l.onError(new GatewayError("ALREADY_CONNECTED","Already connecting or connected",true,null)); return; }
        listener=l; options=o; listener.onSession(null);
        if(o.host==null || !o.host.matches("[A-Za-z0-9.-]+") || o.port<1 || o.port>65535) {
            error("INVALID_ADDRESS","Enter an IPv4 address or DNS host and valid port",true,null); return;
        }
        seq=requestSeq=lastServerSeq=inputSeq=dropSeq=0;
        matchId=resultId=null; welcomed=suspect=running=false; frame=InputFrame.ZERO;
        lastPacket=lastInput=lastHeartbeat=lastSend=now();
        URI uri=URI.create("ws://"+o.host+":"+o.port+"/game");
        socket=new WebSocketClient(uri) {
            @Override public void onOpen(ServerHandshake h) {
                synchronized(WebSocketClientGateway.this) {
                    if(socket!=this) return;
                    state("HANDSHAKING",null);
                    sendControl("HELLO",map("clientBuild",o.clientBuild,"mapHash",o.mapHash,"configHash",o.configHash),null);
                }
            }
            @Override public void onMessage(String text) {
                synchronized(WebSocketClientGateway.this) { if(socket==this) receive(text); }
            }
            @Override public void onClose(int code,String reason,boolean remote) {
                synchronized(WebSocketClientGateway.this) {
                    if(socket!=this) return;
                    socket=null; stopClock(); running=false; frame=InputFrame.ZERO;
                    requests.cancel(); listener.onSession(null); state("CLOSED",reason);
                }
            }
            @Override public void onError(Exception ex) {
                synchronized(WebSocketClientGateway.this) {
                    if(socket==this) error("CONNECTION_LOST",ex.getClass().getSimpleName(),true,null);
                }
            }
        };
        clock=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"lightchase-client");t.setDaemon(true);return t;});
        state("CONNECTING",null);
        socket.connect();
        clock.scheduleAtFixedRate(this::pulse,50,50,TimeUnit.MILLISECONDS);
    }
    private synchronized void pulse() {
        if(socket==null) return;
        long n=now();
        if(requests.pulse(n)) { close(); return; }
        if(n-lastPacket>=5000) { error("CONNECTION_LOST","No server response for 5 seconds",true,null); close(); return; }
        if(!welcomed) return;
        if(n-lastPacket>=3000 && !suspect) { suspect=true; frame=InputFrame.ZERO; state("SUSPECT","No recent server packets"); }
        if(n-lastHeartbeat>=1000) { send("HEARTBEAT",null,null,map("nonce",n));lastHeartbeat=n; }
        if(running && !suspect && n-lastSend>=50) {
            if(n-lastInput>=250) frame=InputFrame.ZERO;
            sendInput(null); lastSend=n;
        }
    }
    private void receive(String text) {
        try {
            WireCodec.Message m=codec.decode(text);
            if(m.v!=Protocol.VERSION || m.seq<=lastServerSeq) return;
            lastServerSeq=m.seq; lastPacket=now();
            if(suspect) { suspect=false; state("CONNECTED",null); }
            switch(m.type) {
                case "WELCOME":
                    if(!options.mapHash.equals(WireCodec.string(m.body,"mapHash")) ||
                       !options.configHash.equals(WireCodec.string(m.body,"configHash"))) {
                        error("CONFIG_MISMATCH","Server resource hashes differ",false,m.requestId); close(); return;
                    }
                    if(welcomed) throw new IllegalArgumentException("DUPLICATE_WELCOME");
                    WireCodec.exactFields(m.body,"sessionId","playerId","protocolVersion","mapHash","configHash","heartbeatMs","disconnectMs");
                    if(WireCodec.integer(m.body,"protocolVersion")!=Protocol.VERSION || WireCodec.integer(m.body,"heartbeatMs")!=1000 || WireCodec.integer(m.body,"disconnectMs")!=5000) throw new IllegalArgumentException("INVALID_WELCOME");
                    listener.onSession(new SessionInfo(WireCodec.string(m.body,"sessionId"),WireCodec.string(m.body,"playerId")));
                    requests.complete(m.requestId,"HELLO",null);
                    welcomed=true; state("JOINING",null);
                    sendControl("JOIN_ROOM",map("roomId",options.roomId.trim().toUpperCase(Locale.ROOT)),null);
                    break;
                case "ROOM_STATE":
                    RoomState room=codec.body(m,RoomState.class);
                    running=room.phase==RoomPhase.RUNNING;
                    if(!Objects.equals(matchId,room.matchId)) { inputSeq=dropSeq=0;frame=InputFrame.ZERO; }
                    matchId=room.matchId;
                    state("CONNECTED",null); listener.onRoom(room); break;
                case "SNAPSHOT":
                    if(running && Objects.equals(matchId,m.matchId)) listener.onView(codec.body(m,PlayerView.class)); break;
                case "GAME_OVER":
                    if(Objects.equals(matchId,m.matchId)) {
                        MatchResult result=codec.body(m,MatchResult.class); running=false;frame=InputFrame.ZERO;
                        if(!Objects.equals(resultId,result.resultId)) { resultId=result.resultId;listener.onResult(result); }
                        send("RESULT_ACK",null,matchId,map("resultId",resultId));
                    } break;
                case "ERROR":
                    WireCodec.exactFields(m.body,"code","message","fatal");
                    boolean fatal=WireCodec.bool(m.body,"fatal");
                    requests.complete(m.requestId,null,WireCodec.string(m.body,"code"));
                    error(WireCodec.string(m.body,"code"),WireCodec.string(m.body,"message"),!fatal,m.requestId);
                    if(fatal) close(); break;
                case "ACK":
                    WireCodec.exactFields(m.body,"action");
                    requests.complete(m.requestId,WireCodec.string(m.body,"action"),null); break;
                case "PONG":
                    WireCodec.exactFields(m.body,"nonce","serverTimeMs");
                    WireCodec.integer(m.body,"nonce");WireCodec.integer(m.body,"serverTimeMs");break;
                default: error("BAD_MESSAGE","Unknown server message",false,null);close();
            }
        } catch(RuntimeException ex) { error("BAD_MESSAGE","Invalid server message",false,null); close(); }
    }
    public synchronized void selectRole(Role r) { sendControl("SELECT_ROLE",map("role",r.name()),null); }
    public synchronized void setReady(boolean r) { sendControl("SET_READY",map("ready",r),null); }
    public synchronized void submitInput(InputFrame f) {
        boolean edge=frame.interactHeld!=f.interactHeld;
        frame=new InputFrame(f.moveX,f.moveY,f.interactHeld,false); lastInput=now();
        if(running && !suspect && (edge || f.dropPressed)) sendInput(f.dropPressed?++dropSeq:null);
    }
    private void sendInput(Long dropId) { send("INPUT",null,matchId,map("inputSeq",++inputSeq,"moveX",frame.moveX,"moveY",frame.moveY,"interactHeld",frame.interactHeld,"dropId",dropId)); }
    public synchronized void returnToLobby() { sendControl("RETURN_LOBBY",map("resultId",resultId),matchId); }
    public synchronized void leaveRoom() { sendControl("LEAVE_ROOM",map("reason","QUIT"),null); }
    public synchronized void background() { frame=InputFrame.ZERO;sendControl("LEAVE_ROOM",map("reason","BACKGROUND"),null); close(); }
    public synchronized void close() {
        WebSocketClient old=socket;socket=null;running=false;frame=InputFrame.ZERO;
        stopClock();if(old!=null) old.close();
        if(listener!=null) { requests.cancel(); listener.onSession(null); state("CLOSED","Closed locally"); }
    }
    private void stopClock() { if(clock!=null) { clock.shutdownNow();clock=null; } }
    private void state(String s,String reason) { listener.onConnection(new ConnectionStatus(s,reason)); }
    private void error(String code,String msg,boolean retry,String request) { if(listener!=null) listener.onError(new GatewayError(code,msg,retry,request)); }
    private void sendControl(String type,Object body,String match) {
        if(listener==null)return;
        String id="r-"+(++requestSeq);
        requests.begin(id,type,now());
        if(socket==null||!socket.isOpen()) {requests.complete(id,type,"NOT_CONNECTED");error("NOT_CONNECTED","Socket is not open",true,id);return;}
        send(type,id,match,body);
    }
    private void send(String type,String request,String match,Object body) {
        if(socket==null || !socket.isOpen()) { error("NOT_CONNECTED","Socket is not open",true,request);return; }
        socket.send(codec.encode(type,++seq,request,match,body));
    }
    private static Map<String,Object> map(Object... fields) {
        Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<fields.length;i+=2) out.put((String)fields[i],fields[i+1]);return out;
    }
}
