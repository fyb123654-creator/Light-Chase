package com.lightchase.client;

import com.lightchase.contracts.InteractionTarget;
import com.lightchase.contracts.InteractionType;
import com.lightchase.contracts.Protection;
import com.lightchase.contracts.ResultReason;

/**
 * The two scripted victory timelines of the offline demo.
 *
 * <p>They are not rules: each is a pure function of elapsed match time that pushes the fixture into
 * the states a real server would produce, so the marking, protection, delivery and result screens
 * can be exercised without playing a full match. They live here because the gateway class should
 * describe a connection, not a story board - this used to be 120 lines of timeline in the middle of
 * the socket lifecycle.
 *
 * <p>Package-private field access is deliberate: the fixture and its scripts are two halves of one
 * test double, and threading a dozen getters through them would only hide that.
 */
final class OfflineScript {

    /** When the scripted police capture lands, in seconds. */
    private static final double POLICE_CAPTURE_AT = 10.0;
    /** When the scripted thief escape lands, in seconds. */
    private static final double THIEF_ESCAPE_AT = 11.0;
    /** A scripted long press lasts this long, in seconds. */
    private static final double HOLD_SECONDS = 1.0;

    private OfflineScript() {
    }

    /**
     * Police victory: mark 1, invisible, immune, mark 2, mark 3 and the capture.
     *
     * @return true when the match finished and the caller must stop advancing
     */
    static boolean policeWin(FakeGateway g) {
        double t = g.matchSeconds;
        if (t >= POLICE_CAPTURE_AT) {
            g.marks = 3;
            g.finish(ResultReason.CAPTURED);
            return true;
        }
        g.marks = t >= 6.0 ? 2 : t >= 2.0 ? 1 : 0;
        double since = t - (g.marks == 2 ? 6.0 : g.marks == 1 ? 2.0 : 0.0);
        g.protection = Protection.NORMAL;
        g.protectionTicks = 0;
        if (g.marks > 0) {
            double invisible = g.data.rules.invisibleTicks / (double) g.data.rules.tickRate;
            double immune = g.data.rules.immuneTicks / (double) g.data.rules.tickRate;
            if (since < invisible) {
                g.protection = Protection.INVISIBLE;
                g.protectionTicks = (int) Math.round((invisible - since) * g.data.rules.tickRate);
            } else if (since < invisible + immune) {
                g.protection = Protection.IMMUNE;
                g.protectionTicks = (int) Math.round((invisible + immune - since) * g.data.rules.tickRate);
            }
        }
        g.interactionTarget = null;
        g.interactionProgress = 0;
        return false;
    }

    /**
     * Thief victory: pick up and deliver three materials, then leave through an exit.
     *
     * @return true when the match finished and the caller must stop advancing
     */
    static boolean thiefWin(FakeGateway g) {
        double t = g.matchSeconds;
        if (t >= THIEF_ESCAPE_AT) {
            g.finish(ResultReason.ESCAPED);
            return true;
        }
        setDelivered(g, t >= 9.0 ? 3 : t >= 7.0 ? 2 : t >= 4.0 ? 1 : 0);
        g.exitsOpen = g.delivered >= 3;
        g.carrying = null;
        g.interactionTarget = null;
        g.interactionProgress = 0;
        g.protection = Protection.NORMAL;
        g.protectionTicks = 0;

        int ticks = g.data.rules.interactTicks;
        int rate = g.data.rules.tickRate;
        if (t >= 2.0 && t < 3.0) {
            g.interactionTarget = new InteractionTarget(InteractionType.PICKUP, "M1");
            g.interactionProgress = progressWithin(t - 2.0, ticks, rate);
        } else if (t >= 3.0 && t < 4.0) {
            g.carrying = "M1";
        } else if (t >= 4.0 && t < 5.0) {
            g.interactionTarget = new InteractionTarget(InteractionType.DELIVER, "D1");
            g.interactionProgress = progressWithin(t - 4.0, ticks, rate);
        } else if (t >= 5.0 && t < 6.0) {
            g.carrying = "M2";
        } else if (t >= 6.0 && t < 7.0) {
            g.interactionTarget = new InteractionTarget(InteractionType.DELIVER, "D2");
            g.interactionProgress = progressWithin(t - 6.0, ticks, rate);
        } else if (t >= 7.0 && t < 8.0) {
            g.carrying = "M3";
        } else if (t >= 8.0 && t < 9.0) {
            g.interactionTarget = new InteractionTarget(InteractionType.DELIVER, "D3");
            g.interactionProgress = progressWithin(t - 8.0, ticks, rate);
        } else if (t >= 9.0) {
            g.interactionTarget = new InteractionTarget(InteractionType.ESCAPE, "E1");
            g.interactionProgress = progressWithin(t - 9.0, ticks, rate);
        }
        return false;
    }

    /** The scripted path delivers the map's materials in order; keep the id set in step. */
    private static void setDelivered(FakeGateway g, int count) {
        g.delivered = count;
        g.deliveredIds.clear();
        for (int i = 0; i < count && i < g.data.map.materials.size(); i++) {
            g.deliveredIds.add(g.data.map.materials.get(i).id);
        }
    }

    /** Progress of a scripted long press, clamped to the rule's length. */
    private static int progressWithin(double elapsedInPhase, int interactTicks, int tickRate) {
        double hold = Math.max(0.0, Math.min(1.0, elapsedInPhase / HOLD_SECONDS));
        return (int) Math.round(hold * interactTicks);
    }
}
