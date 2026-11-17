package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record InputFrame(double moveX,double moveY,Double aimRad,boolean interactHeld,boolean dropPressed) {
    public InputFrame {
        if (!Double.isFinite(moveX)||!Double.isFinite(moveY)||Math.abs(moveX)>1||Math.abs(moveY)>1||aimRad!=null&&(!Double.isFinite(aimRad)||aimRad<0||aimRad>=Math.PI*2)) throw new IllegalArgumentException("INVALID_INPUT");
    }
}
