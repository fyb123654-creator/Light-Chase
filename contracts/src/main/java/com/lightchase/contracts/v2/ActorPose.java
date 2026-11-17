package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record ActorPose(String playerId,double x,double y,double headingRad,Double aimRad,BeamView beam,Motion motion) {}
