package com.lightchase.server;
import com.lightchase.data.DataFiles;
import java.net.InetSocketAddress;
public final class ServerMain {
    public static void main(String[] args) throws Exception {
        int port=args.length==0?8080:Integer.parseInt(args[0]);
        if(port<1 || port>65535) throw new IllegalArgumentException("Port must be 1..65535");
        LobbyServer server=new LobbyServer(new InetSocketAddress("0.0.0.0",port),DataFiles.bundled());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try { server.shutdown(); }catch(InterruptedException e){Thread.currentThread().interrupt();}}));
        server.start();
        server.awaitStart();
        System.out.println("Light Chase S0 lobby listening on ws://0.0.0.0:"+server.getPort()+"/game");
        System.out.println("Handshake/lobby only. Match start intentionally unavailable in this skeleton.");
    }
}
