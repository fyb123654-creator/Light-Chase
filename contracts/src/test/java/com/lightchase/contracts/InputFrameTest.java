package com.lightchase.contracts;
import org.junit.Test;
import static org.junit.Assert.*;
public class InputFrameTest {
 @Test public void zeroIsReleased() { assertFalse(InputFrame.ZERO.interactHeld); }
 @Test(expected=IllegalArgumentException.class) public void nanRejected() { new InputFrame(Double.NaN,0,false,false); }
 @Test(expected=IllegalArgumentException.class) public void outOfRangeRejected() { new InputFrame(1.01,0,false,false); }
}
