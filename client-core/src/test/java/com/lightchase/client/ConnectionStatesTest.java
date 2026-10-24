package com.lightchase.client;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * SUSPECT is a warning, not a disconnect (contract §2: three seconds without a PONG). Treating it
 * as "gone" throws the player back to the connect page mid-match, so the distinction is pinned here.
 */
public class ConnectionStatesTest {

    @Test public void connectedAndSuspectBothKeepTheSessionAlive() {
        assertTrue(ConnectionStates.isSessionAlive(ConnectionStates.CONNECTED));
        assertTrue("a slow connection must not end the match", ConnectionStates.isSessionAlive(ConnectionStates.SUSPECT));
    }

    @Test public void everyOtherStateEndsTheSession() {
        for (String state : new String[]{
                ConnectionStates.DISCONNECTED, ConnectionStates.CONNECTING, ConnectionStates.HANDSHAKING,
                ConnectionStates.JOINING, ConnectionStates.CLOSED, null, "", "UNKNOWN"}) {
            assertFalse("must not be treated as alive: " + state, ConnectionStates.isSessionAlive(state));
        }
    }
}
