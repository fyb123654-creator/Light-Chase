package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record StepResult(long tick,boolean visibilityChanged,boolean ended,MatchResult result,List<PlayerActionResult> actionResults,List<PublicEvent> events) {
    public StepResult {
        actionResults=Collections.unmodifiableList(new ArrayList<>(actionResults));
        events=Collections.unmodifiableList(new ArrayList<>(events));
    }
}
