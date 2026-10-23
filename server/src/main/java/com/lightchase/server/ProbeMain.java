package com.lightchase.server;
import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import com.lightchase.network.WebSocketClientGateway;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
/** Read-only local connectivity smoke check; never claims a full game passed. */
public final class ProbeMain {
    public static void main(String[] args) throws Exception {
        String host=args.length>0?args[0]:"127.0.0.1";
        int port=args.length>1?Integer.parseInt(args[1]):8080;
        DataFiles d=DataFiles.bundled();CountDownLatch done=new CountDownLatch(1);
        AtomicReference<String> failure=new AtomicReference<>();
        WebSocketClientGateway client=new WebSocketClientGateway();
        try {
            client.connect(new ConnectionOptions(host,port,"PROBE","s0-probe",d.mapHash,d.configHash),new GatewayListener(){
                public void onSession(SessionInfo session) {}
        public void onControl(ControlStatus status) {}
        public void onConnection(ConnectionStatus s){System.out.println("Connection: "+s.state);}
                public void onRoom(RoomState r){System.out.println("PASS: joined "+r.roomId+" ("+r.players.size()+" player(s)); protocol and resource hashes agree.");done.countDown();}
                public void onView(PlayerView v){}
                public void onResult(MatchResult r){}
                public void onError(GatewayError e){failure.set(e.code+": "+e.message);done.countDown();}
            });
            if(!done.await(7,TimeUnit.SECONDS))throw new IllegalStateException("Probe timed out");
            if(failure.get()!=null)throw new IllegalStateException(failure.get());
            System.out.println("This verifies transport/lobby only, not gameplay.");
        } finally {client.close();}
    }
}
