package com.lightchase.server;

import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.network.WireCodec;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

/** B's S0 lobby. No hidden game state or pretend successful match start. */
public final class LobbyServer extends WebSocketServer {
    private final WireCodec codec=new WireCodec();
    private final DataFiles data;
    private final Map<WebSocket,Session> sessions=new LinkedHashMap<>();
    private final Map<String,Room> rooms=new LinkedHashMap<>();
    private final CountDownLatch started=new CountDownLatch(1);
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"lightchase-lobby-timeouts");t.setDaemon(true);return t;});
    private volatile Exception startFailure;
    private static final class Session {
        String id=UUID.randomUUID().toString(),playerId=UUID.randomUUID().toString(),roomId;
        Role role; boolean welcomed; long inSeq,outSeq,lastPacket=now();
        Map<String,String> requests=new LinkedHashMap<>();
        Map<String,Reply> replies=new LinkedHashMap<>();
    }
    private static final class Reply {
        final String type;final Object body;
        Reply(String type,Object body){this.type=type;this.body=body;}
    }
    private static final class Room { long revision; final List<WebSocket> members=new ArrayList<>(); }
    public LobbyServer(InetSocketAddress address,DataFiles data) { super(address);this.data=data;setReuseAddr(true);setConnectionLostTimeout(0); }
    private static long now() { return System.nanoTime()/1_000_000; }
    public void awaitStart() throws Exception {
        if(!started.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Server startup timed out");
        if(startFailure!=null) throw startFailure;
    }
    public void shutdown() throws InterruptedException { timer.shutdownNow();stop(1000); }
    @Override public synchronized void onOpen(WebSocket c,ClientHandshake h) {
        Session s=new Session(); sessions.put(c,s);
        if(!"/game".equals(h.getResourceDescriptor())) fail(c,s,null,"BAD_MESSAGE","Use /game",true);
    }
    @Override public synchronized void onClose(WebSocket c,int code,String reason,boolean remote) { remove(c); }
    @Override public synchronized void onMessage(WebSocket c,String text) {
        Session s=sessions.get(c); if(s==null) return;
        WireCodec.Message m=null;
        try {
            m=codec.decode(text);
            if(m.v!=Protocol.VERSION) { fail(c,s,m,"VERSION_MISMATCH","Protocol version must be 1",true);return; }
            if(m.seq<=s.inSeq) { fail(c,s,m,"STALE_MESSAGE","Sequence must increase",false);return; }
            s.inSeq=m.seq;
            if(!s.welcomed && !"HELLO".equals(m.type)) { fail(c,s,m,"HANDSHAKE_REQUIRED","Send HELLO first",true);return; }
            if(m.matchId!=null) { fail(c,s,m,"INVALID_PHASE","S0 server has no running match",false);return; }
            if(!"HEARTBEAT".equals(m.type)) {
                if(m.requestId==null || m.requestId.isEmpty() || m.requestId.length()>64) throw new IllegalArgumentException("BAD_MESSAGE");
                String request=m.type+":"+m.body.toString();
                if(s.requests.containsKey(m.requestId)) {
                    Reply reply=s.replies.get(m.requestId);
                    if(request.equals(s.requests.get(m.requestId)) && reply!=null) {
                        send(c,s,reply.type,m.requestId,reply.body);
                    } else fail(c,s,m,"REQUEST_ID_REUSED","Request ID reused with different contents",false);
                    return;
                }
                s.requests.put(m.requestId,request);
                if(s.requests.size()>128) {String oldest=s.requests.keySet().iterator().next();s.requests.remove(oldest);s.replies.remove(oldest);}
            }
            switch(m.type) {
                case "HELLO":
                    WireCodec.exactFields(m.body,"clientBuild","mapHash","configHash");
                    WireCodec.string(m.body,"clientBuild");
                    if(s.welcomed) { fail(c,s,m,"INVALID_PHASE","Already handshaken",false);break; }
                    if(!data.mapHash.equals(WireCodec.string(m.body,"mapHash"))) { fail(c,s,m,"MAP_MISMATCH","Map hash differs",true);break; }
                    if(!data.configHash.equals(WireCodec.string(m.body,"configHash"))) { fail(c,s,m,"CONFIG_MISMATCH","Rule hash differs",true);break; }
                    s.welcomed=true;
                    send(c,s,"WELCOME",m.requestId,map("sessionId",s.id,"playerId",s.playerId,"protocolVersion",1,"mapHash",data.mapHash,"configHash",data.configHash,"heartbeatMs",1000,"disconnectMs",5000));break;
                case "JOIN_ROOM":
                    WireCodec.exactFields(m.body,"roomId");String id=WireCodec.string(m.body,"roomId");
                    if(!id.matches("[A-Z0-9]{1,8}")) { fail(c,s,m,"INVALID_ROOM_ID","Use 1-8 uppercase letters/digits",false);break; }
                    if(s.roomId!=null && !s.roomId.equals(id)) { fail(c,s,m,"INVALID_PHASE","Leave current room first",false);break; }
                    Room room=rooms.get(id);
                    if(room==null && rooms.size()>=8) { fail(c,s,m,"SERVER_BUSY","Maximum 8 rooms",false);break; }
                    if(room==null) { room=new Room();rooms.put(id,room); }
                    if(!room.members.contains(c) && room.members.size()>=2) { fail(c,s,m,"ROOM_FULL","Room has two players",false);break; }
                    if(!room.members.contains(c)) room.members.add(c);
                    s.roomId=id; ack(c,s,m);broadcastRoom(id);break;
                case "SELECT_ROLE":
                    WireCodec.exactFields(m.body,"role");
                    Role role=Role.valueOf(WireCodec.string(m.body,"role"));
                    if(s.roomId==null) { fail(c,s,m,"NOT_IN_ROOM","Join first",false);break; }
                    boolean taken=false;
                    for(WebSocket peer:rooms.get(s.roomId).members) if(peer!=c && sessions.get(peer).role==role) taken=true;
                    if(taken) { fail(c,s,m,"ROLE_TAKEN","Choose the other role",false);break; }
                    s.role=role;ack(c,s,m);broadcastRoom(s.roomId);break;
                case "SET_READY":
                    WireCodec.exactFields(m.body,"ready");
                    if(s.roomId==null) { fail(c,s,m,"NOT_IN_ROOM","Join first",false);break; }
                    if(WireCodec.bool(m.body,"ready")) fail(c,s,m,"INVALID_PHASE","S0 skeleton: countdown and matches are not implemented yet",false);
                    else { ack(c,s,m);broadcastRoom(s.roomId); } break;
                case "HEARTBEAT":
                    WireCodec.exactFields(m.body,"nonce");
                    send(c,s,"PONG",null,map("nonce",WireCodec.integer(m.body,"nonce"),"serverTimeMs",now()));break;
                case "LEAVE_ROOM":
                    WireCodec.exactFields(m.body,"reason");String reason=WireCodec.string(m.body,"reason");
                    if(!"QUIT".equals(reason)&&!"BACKGROUND".equals(reason)) throw new IllegalArgumentException("BAD_MESSAGE");
                    ack(c,s,m);remove(c);c.close(1000,reason);break;
                default: fail(c,s,m,"INVALID_PHASE","S0 supports handshake and lobby only",false);
            }
            s.lastPacket=now();
        } catch(RuntimeException ex) {
            String code="MESSAGE_TOO_LARGE".equals(ex.getMessage())?"MESSAGE_TOO_LARGE":"BAD_MESSAGE";
            fail(c,s,m,code,"Invalid message or field",true);
        }
    }
    private void ack(WebSocket c,Session s,WireCodec.Message m) { send(c,s,"ACK",m.requestId,map("action",m.type)); }
    private void fail(WebSocket c,Session s,WireCodec.Message m,String code,String message,boolean fatal) {
        send(c,s,"ERROR",m==null?null:m.requestId,map("code",code,"message",message,"fatal",fatal));
        if(fatal) c.close(1008,code);
    }
    private void send(WebSocket c,Session s,String type,String request,Object body) {
        if(request!=null) s.replies.putIfAbsent(request,new Reply(type,body));
        if(c.isOpen()) c.send(codec.encode(type,++s.outSeq,request,null,body));
    }
    private void broadcastRoom(String roomId) {
        Room room=rooms.get(roomId); if(room==null)return;
        List<RoomPlayer> players=new ArrayList<>();
        for(WebSocket peer:room.members) {
            Session s=sessions.get(peer);players.add(new RoomPlayer(s.playerId,s.role,false,"CONNECTED",false));
        }
        RoomState state=new RoomState(roomId,++room.revision,RoomPhase.LOBBY,data.map.mapId,data.mapHash,data.configHash,null,0,players);
        for(WebSocket peer:room.members) send(peer,sessions.get(peer),"ROOM_STATE",null,state);
    }
    private void remove(WebSocket c) {
        Session s=sessions.remove(c);
        if(s==null || s.roomId==null)return;
        Room room=rooms.get(s.roomId);room.members.remove(c);
        if(room.members.isEmpty()) rooms.remove(s.roomId);else broadcastRoom(s.roomId);
    }
    @Override public synchronized void onError(WebSocket c,Exception ex) {
        if(c==null) { startFailure=ex;started.countDown(); }
        System.err.println("WebSocket error: "+ex.getClass().getSimpleName());
    }
    @Override public void onStart() {
        started.countDown();
        timer.scheduleAtFixedRate(()->{
            synchronized(LobbyServer.this) {
                for(Map.Entry<WebSocket,Session> e:new ArrayList<>(sessions.entrySet())) {
                    long limit=e.getValue().welcomed?5000:3000;
                    if(now()-e.getValue().lastPacket>=limit) {
                        e.getKey().close(1001,"Timeout");remove(e.getKey());
                    }
                }
            }
        },250,250,TimeUnit.MILLISECONDS);
    }
    private static Map<String,Object> map(Object... fields) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i+=2)out.put((String)fields[i],fields[i+1]);return out;
    }
}
