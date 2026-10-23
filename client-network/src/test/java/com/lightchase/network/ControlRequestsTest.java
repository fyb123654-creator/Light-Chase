package com.lightchase.network;
import com.lightchase.contracts.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ControlRequestsTest {
    @Test public void ackCompletesOnceAndMustMatchAction(){
        List<ControlStatus> events=new ArrayList<>();ControlRequests r=new ControlRequests(events::add);
        r.begin("r1","SET_READY",0);r.complete("r1","SET_READY",null);r.complete("r1","SET_READY",null);
        assertEquals(2,events.size());assertEquals(ControlStatus.State.SUCCEEDED,events.get(1).state);
        r.begin("r2","SELECT_ROLE",0);
        assertThrows(IllegalArgumentException.class,()->r.complete("r2","SET_READY",null));
    }
    @Test public void timeoutIsIndependentOfIncomingHeartbeats(){
        List<ControlStatus> events=new ArrayList<>();ControlRequests r=new ControlRequests(events::add);
        r.begin("r1","SET_READY",0);assertFalse(r.pulse(1999));assertFalse(r.pulse(2000));
        assertEquals(ControlStatus.State.WAITING,events.get(1).state);
        assertTrue(r.pulse(5000));r.cancel();r.complete("r1",null,null);
        assertEquals(3,events.size());assertEquals(ControlStatus.State.TIMED_OUT,events.get(2).state);
    }
    @Test public void errorAndDisconnectAreTerminal(){
        List<ControlStatus> events=new ArrayList<>();ControlRequests r=new ControlRequests(events::add);
        r.begin("r1","SELECT_ROLE",0);r.complete("r1",null,"ROLE_TAKEN");
        r.begin("r2","SET_READY",0);r.cancel();r.cancel();
        assertEquals(4,events.size());assertEquals("ROLE_TAKEN",events.get(1).errorCode);
        assertEquals(ControlStatus.State.CANCELLED,events.get(3).state);
    }
}
