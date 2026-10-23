package com.lightchase.game;

import com.lightchase.contracts.RuleConfig;

/** Pure rule math shared by later visibility/interaction stages. */
final class RuleMath {
    private RuleMath() {
    }

    static double normalizeHeading(double radians) {
        double value = radians % (2.0 * Math.PI);
        if (value >= Math.PI) {
            value -= 2.0 * Math.PI;
        }
        if (value < -Math.PI) {
            value += 2.0 * Math.PI;
        }
        return value;
    }

    static double halfBeamAngle(RuleConfig rules) {
        return Math.toRadians(rules.beamAngleDeg) / 2.0;
    }

    static double viewHalfWidth(RuleConfig rules) {
        return rules.viewHeight * rules.viewAspect / 2.0;
    }

    static double viewHalfHeight(RuleConfig rules) {
        return rules.viewHeight / 2.0;
    }
}
