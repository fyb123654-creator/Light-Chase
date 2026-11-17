package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record RuleConfig(double speed,double viewHeight,double viewAspect,double beamRange,double beamAngleDeg,int markTicks,int invisibleTicks,int immuneTicks,double interactRange,int interactTicks,int maxMarks,int durationTicks,int tickRate,int countdownTicks,double radius,double speedBoostMultiplier,double lightBoostMultiplier,int speedBoostTicks,int lightBoostTicks,int shieldTicks,int transitTicks,int itemPickupTicks,int cardPickupTicks,int portalUnlockTicks,int portalEnterTicks) {}
