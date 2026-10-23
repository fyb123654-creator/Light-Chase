package com.lightchase.network;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class WireCodecTest {
    @Test public void nullEnvelopeFieldsSurviveRoundTrip() {
        WireCodec c=new WireCodec();
        WireCodec.Message m=c.decode(c.encode("HEARTBEAT",1,null,null,Collections.singletonMap("nonce",7)));
        assertNull(m.requestId);assertNull(m.matchId);assertEquals(7,WireCodec.integer(m.body,"nonce"));
    }
    @Test(expected=IllegalArgumentException.class) public void missingEnvelopeFieldsRejected() {new WireCodec().decode("{}");}
    @Test(expected=IllegalArgumentException.class) public void fractionalSequenceRejected() {
        new WireCodec().decode("{\"v\":1,\"type\":\"X\",\"seq\":1.5,\"requestId\":null,\"matchId\":null,\"body\":{}}");
    }
}
