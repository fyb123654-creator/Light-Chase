package com.lightchase.client;
import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import org.junit.Test;
import static org.junit.Assert.*;
public class FakeGatewayTest {
    private static class Listener implements GatewayListener {
        PlayerView view;String state;
        public void onConnection(ConnectionStatus s){state=s.state;}
        public void onRoom(RoomState s){}
        public void onView(PlayerView s){view=s;}
        public void onResult(MatchResult s){}
        public void onError(GatewayError e){fail(e.message);}
    }
    @Test public void backgroundClearsInputAndStopsFixture() {
        DataFiles d=DataFiles.bundled();FakeGateway g=new FakeGateway();g.configure(d);Listener l=new Listener();
        g.connect(new ConnectionOptions("offline",8080,"DEMO","test",d.mapHash,d.configHash),l);
        g.setReady(true);g.submitInput(new InputFrame(1,0,true,true));g.advance(.1f);
        double x=l.view.self.x;
        g.background();g.advance(1);
        assertEquals("CLOSED",l.state);assertEquals(0,g.lastInput().moveX,0);assertFalse(g.lastInput().interactHeld);
        assertEquals(x,l.view.self.x,0);
    }
    @Test public void policeFixtureDoesNotExposeThiefPrivateState() {
        DataFiles d=DataFiles.bundled();FakeGateway g=new FakeGateway();g.configure(d);Listener l=new Listener();
        g.connect(new ConnectionOptions("offline",8080,"DEMO","test",d.mapHash,d.configHash),l);
        g.selectRole(Role.POLICE);g.setReady(true);
        assertNull(l.view.thief);assertNull(l.view.opponent);
    }
}
