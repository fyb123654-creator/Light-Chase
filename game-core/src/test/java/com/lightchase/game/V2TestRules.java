package com.lightchase.game;

import com.lightchase.contracts.v2.RuleConfig;

/** Fast, deterministic rule values for engine tests. Production values stay in JSON. */
final class V2TestRules {

    private V2TestRules() {
    }

    static RuleConfig fast() {
        return new RuleConfig(30.0, 8.0, 16.0 / 9.0, 4.0, 60.0,
                2, 2, 2, 0.8, 2, 3, 600, 30, 0, 0.25,
                1.5, 1.5, 10, 10, 10, 5, 1, 1, 1, 1);
    }

    /** A copy with a different duration, useful for timeout tests. */
    static RuleConfig duration(int durationTicks) {
        RuleConfig r = fast();
        return new RuleConfig(r.speed(), r.viewHeight(), r.viewAspect(), r.beamRange(),
                r.beamAngleDeg(), r.markTicks(), r.invisibleTicks(), r.immuneTicks(),
                r.interactRange(), r.interactTicks(), r.maxMarks(), durationTicks,
                r.tickRate(), r.countdownTicks(), r.radius(), r.speedBoostMultiplier(),
                r.lightBoostMultiplier(), r.speedBoostTicks(), r.lightBoostTicks(),
                r.shieldTicks(), r.transitTicks(), r.itemPickupTicks(), r.cardPickupTicks(),
                r.portalUnlockTicks(), r.portalEnterTicks());
    }

    static RuleConfig shield(int shieldTicks, int invisibleTicks, int immuneTicks) {
        RuleConfig r = fast();
        return new RuleConfig(r.speed(), r.viewHeight(), r.viewAspect(), r.beamRange(),
                r.beamAngleDeg(), r.markTicks(), invisibleTicks, immuneTicks,
                r.interactRange(), r.interactTicks(), r.maxMarks(), r.durationTicks(),
                r.tickRate(), r.countdownTicks(), r.radius(), r.speedBoostMultiplier(),
                r.lightBoostMultiplier(), r.speedBoostTicks(), r.lightBoostTicks(),
                shieldTicks, r.transitTicks(), r.itemPickupTicks(), r.cardPickupTicks(),
                r.portalUnlockTicks(), r.portalEnterTicks());
    }
}
