package com.lightchase.client;

import com.lightchase.contracts.*;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class ViewInterpolatorTest {

    private static PlayerView view(String matchId, long tick, double x, double y,
                                   Double opponentX, Double opponentY) {
        VisibleOpponent opponent = opponentX == null ? null
                : new VisibleOpponent("p2", Role.POLICE, opponentX, opponentY, 0, false);
        return new PlayerView(matchId, tick, 0, 0, Role.THIEF, 5400, 0, 0, false,
                new ActorPose("p1", x, y, 0), opponent,
                Collections.singletonList(new OriginStatus("M1", false)),
                new ThiefPrivate(null, Protection.NORMAL, 0, Collections.emptyList(),
                        Collections.emptyList(), new InteractionView(null, null, 0, false, "NO_TARGET")));
    }

    @Test public void firstSnapshotIsUsedAsIs() {
        ViewInterpolator i = new ViewInterpolator(100);
        i.accept(view("m1", 1, 10, 5, null, null), 1000);
        i.sample(1000);
        assertEquals(10, i.selfX(), 1e-9);
        assertEquals(5, i.selfY(), 1e-9);
    }

    @Test public void secondSnapshotIsReachedAfterTheWindow() {
        ViewInterpolator i = new ViewInterpolator(100);
        i.accept(view("m1", 1, 10, 5, null, null), 1000);
        i.accept(view("m1", 2, 12, 7, null, null), 1100);
        i.sample(1100);
        assertEquals("at arrival the previous position is still the anchor", 10, i.selfX(), 1e-9);
        i.sample(1150);
        assertEquals(11, i.selfX(), 1e-9);
        assertEquals(6, i.selfY(), 1e-9);
        i.sample(1200);
        assertEquals(12, i.selfX(), 1e-9);
        i.sample(5000);
        assertEquals("never overshoots the newest snapshot", 12, i.selfX(), 1e-9);
    }

    @Test public void hiddenOpponentIsNotInterpolated() {
        ViewInterpolator i = new ViewInterpolator(100);
        i.accept(view("m1", 1, 10, 5, 20.0, 9.0), 1000);
        i.accept(view("m1", 2, 10, 5, null, null), 1100);
        i.sample(1100);
        assertFalse("a vanished opponent must disappear on the very first frame", i.opponentVisible());
    }

    @Test public void appearingOpponentStartsAtItsOwnPosition() {
        ViewInterpolator i = new ViewInterpolator(100);
        i.accept(view("m1", 1, 10, 5, null, null), 1000);
        i.accept(view("m1", 2, 10, 5, 20.0, 9.0), 1100);
        i.sample(1100);
        assertTrue(i.opponentVisible());
        assertEquals("no interpolation from a position it never occupied", 20, i.opponentX(), 1e-9);
    }

    @Test public void newMatchResetsTheHistory() {
        ViewInterpolator i = new ViewInterpolator(100);
        i.accept(view("m1", 90, 10, 5, 20.0, 9.0), 1000);
        i.accept(view("m2", 1, 3, 3, null, null), 1100);
        i.sample(1100);
        assertEquals("a new match must not be interpolated from the old one", 3, i.selfX(), 1e-9);
        assertFalse(i.opponentVisible());
    }

    @Test public void clearStopsSampling() {
        ViewInterpolator i = new ViewInterpolator(100);
        i.accept(view("m1", 1, 10, 5, 20.0, 9.0), 1000);
        i.clear();
        i.sample(1000);
        assertFalse(i.hasCurrent());
        assertFalse(i.opponentVisible());
    }
}
