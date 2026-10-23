package com.lightchase.server;
import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.network.WebSocketClientGateway;
import org.junit.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class LobbyIntegrationTest {
    private LobbyServer server;
    private final DataFiles data=DataFiles.bundled();
    private final java.util.List<WebSocketClientGateway> clients=new java.util.ArrayList<>();
    private static final class Listener implements GatewayListener {
        final BlockingQueue<RoomState> rooms=new LinkedBlockingQueue<>();
        final BlockingQueue<GatewayError> errors=new LinkedBlockingQueue<>();
        public void onConnection(ConnectionStatus s){}
        public void onRoom(RoomState s){rooms.add(s);}
        public void onView(PlayerView s){fail("S0 must not pretend to run a match");}
        public void onResult(MatchResult s){fail("S0 must not fabricate a result");}
        public void onError(GatewayError e){errors.add(e);}
    }
    @Before public void start() throws Exception {server=new LobbyServer(new InetSocketAddress("127.0.0.1",0),data);server.start();server.awaitStart();}
    @After public void stop() throws Exception {for(WebSocketClientGateway c:clients)c.close();server.shutdown();}
    private WebSocketClientGateway connect(Listener listener,String hash) {
        WebSocketClientGateway client=new WebSocketClientGateway();clients.add(client);
        client.connect(new ConnectionOptions("127.0.0.1",server.getPort(),"TEST","junit",hash,data.configHash),listener);return client;
    }
    private RoomState awaitRoom(Listener l,int count,boolean withPolice) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until) {
            RoomState r=l.rooms.poll(250,TimeUnit.MILLISECONDS);
            if(r!=null && r.players.size()==count && (!withPolice||r.players.stream().anyMatch(p->p.role==Role.POLICE)))return r;
        }
        fail("Expected room state; errors="+l.errors.size());return null;
    }
    private GatewayError awaitError(Listener l,String code) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until) {
            GatewayError e=l.errors.poll(250,TimeUnit.MILLISECONDS);if(e!=null&&code.equals(e.code))return e;
        }
        fail("Expected "+code);return null;
    }
    @Test public void twoClientsJoinAndRolesAreExclusive() throws Exception {
        Listener a=new Listener(),b=new Listener();
        WebSocketClientGateway ca=connect(a,data.mapHash);awaitRoom(a,1,false);
        WebSocketClientGateway cb=connect(b,data.mapHash);awaitRoom(b,2,false);
        ca.selectRole(Role.POLICE);awaitRoom(a,2,true);
        cb.selectRole(Role.POLICE);awaitError(b,"ROLE_TAKEN");
        cb.setReady(true);awaitError(b,"INVALID_PHASE");
    }
    @Test public void thirdClientCannotReplaceExistingPlayers() throws Exception {
        Listener a=new Listener(),b=new Listener(),c=new Listener();
        connect(a,data.mapHash);awaitRoom(a,1,false);
        connect(b,data.mapHash);awaitRoom(b,2,false);
        connect(c,data.mapHash);awaitError(c,"ROOM_FULL");
    }
    @Test public void mismatchedMapRejectedBeforeRoomJoin() throws Exception {
        Listener a=new Listener();connect(a,"wrong-hash");
        GatewayError e=awaitError(a,"MAP_MISMATCH");assertFalse(e.recoverable);assertTrue(a.rooms.isEmpty());
    }
}
