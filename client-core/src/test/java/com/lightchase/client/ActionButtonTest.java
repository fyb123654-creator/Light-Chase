package com.lightchase.client;

import com.lightchase.contracts.InteractionTarget;
import com.lightchase.contracts.InteractionType;
import com.lightchase.contracts.InteractionView;
import com.lightchase.contracts.Protection;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.ThiefPrivate;
import com.lightchase.contracts.ActorPose;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.OriginStatus;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The action button's caption policy, asserted without a GL context.
 *
 * <p>{@code labels()} is pure: it reads the state machine's eased values and returns six weights.
 * That is the point of pulling it out of the draw call - the rule "exactly one word reads, and a
 * decay in one is a rise in another" can now be checked directly instead of eyeballed in a
 * screenshot.
 */
public class ActionButtonTest {

    private static final int TICKS = 30;
    private static final int RATE = 30;
    private static final float FRAME = 1f / 60f;

    /** A view with the given interaction state; everything else is the quiet default. */
    private static PlayerView view(String carried, InteractionType candidate, int progress,
                                   boolean exitsOpen, Role role) {
        InteractionView interaction = new InteractionView(
                candidate == null ? null : new InteractionTarget(candidate, "T1"), null, progress,
                false, "NONE");
        ThiefPrivate thief = role == Role.THIEF
                ? new ThiefPrivate(carried, Protection.NORMAL, 0,
                Collections.emptyList(), Collections.emptyList(), interaction)
                : null;
        return new PlayerView("m", 0, 0, 0, role, 100, 0, 0, exitsOpen,
                new ActorPose("self", 1, 1, 0), null, List.<OriginStatus>of(), thief);
    }

    /** Runs the button forward until the eased values settle. */
    private static ActionButton settle(PlayerView view, boolean pressed, int frames) {
        ActionButton button = new ActionButton();
        for (int i = 0; i < frames; i++) {
            button.update(FRAME, view, pressed, TICKS, RATE);
        }
        return button;
    }

    @Test
    public void anEmptyHandWithNothingNearbyReadsHold() {
        ActionButton.Labels l = settle(view(null, null, 0, false, Role.THIEF), false, 120).labels();
        assertTrue("HOLD must dominate, was " + l.hold, l.hold > 0.9f);
        assertTrue(l.drop < 0.05f && l.deliver < 0.05f && l.escape < 0.05f && l.wait < 0.05f);
    }

    @Test
    public void carryingLootReadsDrop() {
        ActionButton.Labels l = settle(view("M1", null, 0, false, Role.THIEF), false, 120).labels();
        assertTrue("DROP must dominate, was " + l.drop, l.drop > 0.9f);
        assertTrue(l.hold < 0.05f);
    }

    @Test
    public void standingOnTheMatchingDropPointReadsDeliverAndNeverFlashesDrop() {
        ActionButton button = new ActionButton();
        PlayerView carrying = view("M1", null, 0, false, Role.THIEF);
        PlayerView delivering = view("M1", InteractionType.DELIVER, 0, false, Role.THIEF);
        for (int i = 0; i < 120; i++) {
            button.update(FRAME, carrying, false, TICKS, RATE);
        }
        // Walk onto the point: DROP must hand over to DELIVER without a second word appearing.
        for (int i = 0; i < 120; i++) {
            button.update(FRAME, delivering, false, TICKS, RATE);
        }
        ActionButton.Labels l = button.labels();
        assertTrue("DELIVER must dominate, was " + l.deliver, l.deliver > 0.9f);
        assertTrue("DROP must not show, was " + l.drop, l.drop < 0.05f);
    }

    @Test
    public void standingOnAnOpenExitReadsEscape() {
        ActionButton.Labels l =
                settle(view(null, InteractionType.ESCAPE, 0, true, Role.THIEF), false, 120).labels();
        assertTrue("ESCAPE must dominate, was " + l.escape, l.escape > 0.9f);
        assertTrue("HOLD must not show, was " + l.hold, l.hold < 0.05f);
    }

    @Test
    public void holdingTheButtonOnATargetReadsWait() {
        PlayerView v = view(null, InteractionType.PICKUP, 5, false, Role.THIEF);
        ActionButton.Labels l = settle(v, true, 60).labels();
        assertTrue("Wait... must dominate, was " + l.wait, l.wait > 0.9f);
        assertTrue("HOLD must have faded, was " + l.hold, l.hold < 0.1f);
    }

    @Test
    public void theLabelsAreAPartitionSoExactlyOneWordEverReads() {
        PlayerView v = view("M1", null, 0, false, Role.THIEF);
        ActionButton button = new ActionButton();
        int dominated = 0;
        for (int i = 0; i < 300; i++) {
            button.update(FRAME, v, i % 7 == 0, TICKS, RATE);
            ActionButton.Labels l = button.labels();
            float total = l.hold + l.drop + l.deliver + l.escape;
            float max = Math.max(Math.max(l.hold, l.drop), Math.max(l.deliver, l.escape));
            assertTrue("the words must never overlap at frame " + i + " (sum " + total + ")",
                    total <= 1.02f);
            if (total > 0.5f) {
                assertTrue("one word must own the caption at frame " + i, max >= total - 0.02f);
                dominated++;
            }
        }
        assertTrue("the invariant must be exercised", dominated > 100);
        float settled = button.labels().hold + button.labels().drop
                + button.labels().deliver + button.labels().escape;
        assertEquals("once everything settles the partition is one", 1f, settled, 0.03f);
    }

    @Test
    public void anEscapeThatLandsCrossFadesIntoWinInsteadOfPopping() {
        ActionButton button = new ActionButton();
        PlayerView escaping = view(null, InteractionType.ESCAPE, TICKS / 2, true, Role.THIEF);
        for (int i = 0; i < 120; i++) {
            button.update(FRAME, escaping, false, TICKS, RATE);
        }
        assertTrue("ESCAPE must read while standing on the exit, was " + button.labels().escape,
                button.labels().escape > 0.5f);
        assertEquals("WIN must not show before the escape lands", 0f, button.labels().win, 0.01f);

        // The bar completes: the button latches, plays its tick, and only then shows WIN !.
        PlayerView landed = view(null, InteractionType.ESCAPE, TICKS - 1, true, Role.THIEF);
        float previous = button.labels().win;
        float biggestStep = 0f;
        for (int i = 0; i < 180; i++) {
            button.update(FRAME, landed, false, TICKS, RATE);
            float now = button.labels().win;
            biggestStep = Math.max(biggestStep, now - previous);
            previous = now;
        }
        assertTrue("WIN must end up reading, was " + previous, previous > 0.6f);
        assertTrue("WIN must climb rather than pop, biggest step was " + biggestStep,
                biggestStep < 0.45f);
        assertTrue("ESCAPE must have handed over, was " + button.labels().escape,
                button.labels().escape < 0.1f);
    }
}
