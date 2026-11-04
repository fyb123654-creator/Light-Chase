package com.lightchase.client;

import com.lightchase.contracts.Role;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The lobby's one word of policy: what the primary action says, and the two labels it remembers so
 * the swap can happen exactly at the white peak of the wash.
 */
public class LobbyStateTest {

    @Test
    public void theActionReadsReadyUntilThePlayerIsReady() {
        LobbyState s = new LobbyState();
        assertEquals("READY", s.ctaLabel(false, false));
        assertEquals("CANCEL", s.ctaLabel(true, false));
    }

    @Test
    public void aPendingRequestOutranksTheLocalState() {
        LobbyState s = new LobbyState();
        assertEquals("WAITING...", s.ctaLabel(false, true));
        assertEquals("WAITING...", s.ctaLabel(true, true));
    }

    @Test
    public void thePressRemembersBothWordsSoTheSwapIsExact() {
        LobbyState s = new LobbyState();
        s.beginReadyPress(false);
        assertTrue(s.readyQueued);
        assertEquals("READY", s.readyLabelAtPress);
        assertEquals("CANCEL", s.readyLabelAfter);
        assertEquals("READY", s.ctaLabel(false, false));

        LobbyState t = new LobbyState();
        t.beginReadyPress(true);
        assertEquals("CANCEL", t.readyLabelAtPress);
        assertEquals("READY", t.readyLabelAfter);
    }

    @Test
    public void aPressOnlyCountsAsPeakedOnceTheWashIsUp() {
        LobbyState s = new LobbyState();
        assertFalse(s.readyPeaked());
        s.beginReadyPress(false);
        assertFalse("the peak needs the wash to actually rise", s.readyPeaked());
        s.readyPress = 1f;
        assertTrue(s.readyPeaked());
    }

    @Test
    public void goingBackToTheLobbyForgetsTheRole() {
        LobbyState s = new LobbyState();
        s.selectedRole = Role.THIEF;
        s.suppressRoomRole = false;
        s.forgetRole();
        assertNull(s.selectedRole);
        assertTrue("the room's stale role must be ignored", s.suppressRoomRole);
    }
}
