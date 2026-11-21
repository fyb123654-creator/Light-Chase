package com.lightchase.game;

import com.lightchase.contracts.v2.Position;
import com.lightchase.contracts.v2.RuleConfig;

/**
 * Pure rule math for the v2 engine. Kept separate from the v1 {@link RuleMath} because v2
 * publishes every angle in [0, 2*pi) instead of the v1 signed convention.
 */
final class V2RuleMath {

    static final double TWO_PI = Math.PI * 2.0;

    private V2RuleMath() {
    }

    /** Normalizes any finite angle to [0, 2*pi). */
    static double angle(double radians) {
        double value = radians % TWO_PI;
        if (value < 0.0) {
            value += TWO_PI;
        }
        if (value >= TWO_PI) {
            value = 0.0;
        }
        return value;
    }

    static double halfBeamAngle(RuleConfig rules) {
        return Math.toRadians(rules.beamAngleDeg()) / 2.0;
    }

    static double viewHalfWidth(RuleConfig rules) {
        return rules.viewHeight() * rules.viewAspect() / 2.0;
    }

    static double viewHalfHeight(RuleConfig rules) {
        return rules.viewHeight() / 2.0;
    }

    static double progress(long tick, long startTick, long endTick) {
        if (endTick <= startTick) {
            return 1.0;
        }
        double p = (tick - startTick) / (double) (endTick - startTick);
        if (p < 0.0) return 0.0;
        if (p > 1.0) return 1.0;
        return p;
    }

    static Position interpolate(Position from, Position to, double progress) {
        return new Position(from.x() + (to.x() - from.x()) * progress,
                from.y() + (to.y() - from.y()) * progress);
    }
}
