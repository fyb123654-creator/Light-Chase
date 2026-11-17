package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record PlayerCommand(String playerId,long inputSeq,double moveX,double moveY,Double aimRad,boolean interactHeld,Long dropId) {}
