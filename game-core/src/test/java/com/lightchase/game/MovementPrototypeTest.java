package com.lightchase.game;
import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class MovementPrototypeTest {
 private MatchEngine engine() { DataFiles d=DataFiles.bundled(); return new PrototypeEngineFactory().create(d.map,d.rules,new MatchInit("test","p","t")); }
 private StepInput input(long tick,double x,double y) { return new StepInput(tick,Collections.singletonList(new PlayerCommand("p",tick,x,y,false,null)),Collections.emptyList()); }
 @Test public void speedAndDiagonal() {
  MatchEngine e=engine(); double x=e.viewFor("p").self.x,y=e.viewFor("p").self.y;
  e.step(input(1,1,1));
  assertEquals(0.1,Math.hypot(e.viewFor("p").self.x-x,e.viewFor("p").self.y-y),1e-8);
 }
 @Test public void boundaryStopsPlayer() {
  MatchEngine e=engine(); for(int i=1;i<=120;i++) e.step(input(i,-1,0));
  assertTrue(e.viewFor("p").self.x>=1.25);
 }
 @Test public void releasedInputStopsMovement() {
  MatchEngine e=engine(); e.step(input(1,1,0)); double x=e.viewFor("p").self.x;
  e.step(input(2,0,0)); assertEquals(x,e.viewFor("p").self.x,0);
 }
 @Test public void abortIsFinalAndPrivateViewIsFiltered() {
  MatchEngine e=engine(); MatchResult r=e.abort(); e.step(input(1,1,0));
  assertSame(r,e.result()); assertNull(e.viewFor("p").thief); assertNull(e.viewFor("p").opponent);
 }
 @Test(expected=IllegalArgumentException.class) public void unknownPlayerRejected() { engine().viewFor("stranger"); }
 @Test(expected=IllegalArgumentException.class) public void nonConsecutiveTickRejected() { engine().step(input(2,0,0)); }
 @Test public void timeoutIsUnique() {
  MatchEngine e=engine(); for(int i=1;i<=5400;i++) e.step(input(i,0,0));
  assertEquals(ResultReason.TIMEOUT,e.result().reason); assertSame(e.result(),e.abort());
 }
}
