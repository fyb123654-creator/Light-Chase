package com.lightchase.game;

import com.lightchase.contracts.RuleConfig;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

/** The engine must reject rule values it would otherwise use silently. */
public class RuleConfigValidationTest {
    private static RuleConfig config(double speed, double viewHeight, double viewAspect,
                                     double beamRange, double beamAngleDeg, int markTicks,
                                     int invisibleTicks, int immuneTicks, double interactRange,
                                     int interactTicks, int maxMarks, int durationTicks,
                                     int tickRate, int countdownTicks, double radius) {
        return new RuleConfig(speed, viewHeight, viewAspect, beamRange, beamAngleDeg,
                markTicks, invisibleTicks, immuneTicks, interactRange, interactTicks,
                maxMarks, durationTicks, tickRate, countdownTicks, radius);
    }

    private static RuleConfig valid() {
        return config(3, 8, 16.0 / 9.0, 4, 60, 24, 45, 30, 0.8, 30,
                3, 5400, 30, 90, 0.25);
    }

    private static RuleConfig withSpeed(double speed) {
        RuleConfig r = valid();
        return config(speed, r.viewHeight, r.viewAspect, r.beamRange, r.beamAngleDeg,
                r.markTicks, r.invisibleTicks, r.immuneTicks, r.interactRange,
                r.interactTicks, r.maxMarks, r.durationTicks, r.tickRate,
                r.countdownTicks, r.radius);
    }

    private static RuleConfig withView(double viewHeight, double viewAspect, double radius) {
        RuleConfig r = valid();
        return config(r.speed, viewHeight, viewAspect, r.beamRange, r.beamAngleDeg,
                r.markTicks, r.invisibleTicks, r.immuneTicks, r.interactRange,
                r.interactTicks, r.maxMarks, r.durationTicks, r.tickRate,
                r.countdownTicks, radius);
    }

    private static RuleConfig withBeam(double beamRange, double beamAngleDeg) {
        RuleConfig r = valid();
        return config(r.speed, r.viewHeight, r.viewAspect, beamRange, beamAngleDeg,
                r.markTicks, r.invisibleTicks, r.immuneTicks, r.interactRange,
                r.interactTicks, r.maxMarks, r.durationTicks, r.tickRate,
                r.countdownTicks, r.radius);
    }

    private static RuleConfig withMark(int markTicks, int invisibleTicks, int immuneTicks,
                                       int maxMarks) {
        RuleConfig r = valid();
        return config(r.speed, r.viewHeight, r.viewAspect, r.beamRange, r.beamAngleDeg,
                markTicks, invisibleTicks, immuneTicks, r.interactRange,
                r.interactTicks, maxMarks, r.durationTicks, r.tickRate,
                r.countdownTicks, r.radius);
    }

    private static RuleConfig withInteraction(double interactRange, int interactTicks) {
        RuleConfig r = valid();
        return config(r.speed, r.viewHeight, r.viewAspect, r.beamRange, r.beamAngleDeg,
                r.markTicks, r.invisibleTicks, r.immuneTicks, interactRange,
                interactTicks, r.maxMarks, r.durationTicks, r.tickRate,
                r.countdownTicks, r.radius);
    }

    private static RuleConfig withTime(int durationTicks, int tickRate, int countdownTicks) {
        RuleConfig r = valid();
        return config(r.speed, r.viewHeight, r.viewAspect, r.beamRange, r.beamAngleDeg,
                r.markTicks, r.invisibleTicks, r.immuneTicks, r.interactRange,
                r.interactTicks, r.maxMarks, durationTicks, tickRate,
                countdownTicks, r.radius);
    }

    private static void assertRejected(RuleConfig rules) {
        assertThrows(IllegalArgumentException.class, () -> new V1EngineFactory().create(
                TestMaps.open(10, 10, 5.5, 5.5, 8.5, 5.5), rules, TestMaps.init()));
    }

    @Test
    public void rejectsInvalidMovementAndViewRules() {
        assertRejected(withSpeed(0));
        assertRejected(withSpeed(Double.NaN));
        assertRejected(withView(0, 16.0 / 9.0, 0.25));
        assertRejected(withView(8, 0, 0.25));
        assertRejected(withView(8, 16.0 / 9.0, 0));
        assertRejected(withView(8, 16.0 / 9.0, 0.5));
    }

    @Test
    public void rejectsInvalidBeamRules() {
        assertRejected(withBeam(0, 60));
        assertRejected(withBeam(4, 0));
        assertRejected(withBeam(4, 180));
    }

    @Test
    public void rejectsInvalidMarkRules() {
        assertRejected(withMark(0, 45, 30, 3));
        assertRejected(withMark(24, -1, 30, 3));
        assertRejected(withMark(24, 45, -1, 3));
        assertRejected(withMark(24, 45, 30, 0));
        assertRejected(withMark(24, 45, 30, 4));
    }

    @Test
    public void rejectsInvalidInteractionRules() {
        assertRejected(withInteraction(0, 30));
        assertRejected(withInteraction(0.8, 0));
    }

    @Test
    public void rejectsInvalidTimeRules() {
        assertRejected(withTime(0, 30, 90));
        assertRejected(withTime(5401, 30, 90));
        assertRejected(withTime(5400, 0, 90));
        assertRejected(withTime(5400, 60, 90));
        assertRejected(withTime(5400, 30, -1));
    }
}
