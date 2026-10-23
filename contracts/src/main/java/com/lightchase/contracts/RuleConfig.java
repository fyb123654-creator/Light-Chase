package com.lightchase.contracts;
public final class RuleConfig {
public final double speed;
public final double viewHeight;
public final double viewAspect;
public final double beamRange;
public final double beamAngleDeg;
public final int markTicks;
public final int invisibleTicks;
public final int immuneTicks;
public final double interactRange;
public final int interactTicks;
public final int maxMarks;
public final int durationTicks;
public final int tickRate;
public final int countdownTicks;
public final double radius;
public RuleConfig(double speed, double viewHeight, double viewAspect, double beamRange, double beamAngleDeg, int markTicks, int invisibleTicks, int immuneTicks, double interactRange, int interactTicks, int maxMarks, int durationTicks, int tickRate, int countdownTicks, double radius) {
this.speed=speed;
this.viewHeight=viewHeight;
this.viewAspect=viewAspect;
this.beamRange=beamRange;
this.beamAngleDeg=beamAngleDeg;
this.markTicks=markTicks;
this.invisibleTicks=invisibleTicks;
this.immuneTicks=immuneTicks;
this.interactRange=interactRange;
this.interactTicks=interactTicks;
this.maxMarks=maxMarks;
this.durationTicks=durationTicks;
this.tickRate=tickRate;
this.countdownTicks=countdownTicks;
this.radius=radius;
}
}
