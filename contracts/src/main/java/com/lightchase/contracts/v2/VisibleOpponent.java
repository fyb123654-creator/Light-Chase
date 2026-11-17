package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record VisibleOpponent(String playerId,Role role,double x,double y,double headingRad,Double aimRad,BeamView beam,boolean carrying) {}
