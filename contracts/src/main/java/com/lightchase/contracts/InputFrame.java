package com.lightchase.contracts;
public final class InputFrame {
    public static final InputFrame ZERO = new InputFrame(0, 0, false, false);
    public final double moveX, moveY;
    public final boolean interactHeld, dropPressed;
    public InputFrame(double x, double y, boolean held, boolean drop) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x)>1 || Math.abs(y)>1)
            throw new IllegalArgumentException("Input must be finite and within [-1, 1]");
        moveX=x; moveY=y; interactHeld=held; dropPressed=drop;
    }
}
