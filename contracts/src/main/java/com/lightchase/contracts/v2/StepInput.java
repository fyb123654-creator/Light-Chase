package com.lightchase.contracts.v2;
import java.util.*;
import com.lightchase.contracts.*;
/** R3 shared target DTO; see docs/development/02_接口契约.md section 13. */
public record StepInput(long tick,List<PlayerCommand> commands,List<PlayerAction> actions,List<Departure> departures) {
    public StepInput {
        commands=Collections.unmodifiableList(new ArrayList<>(commands));
        actions=Collections.unmodifiableList(new ArrayList<>(actions));
        departures=Collections.unmodifiableList(new ArrayList<>(departures));
    }
}
