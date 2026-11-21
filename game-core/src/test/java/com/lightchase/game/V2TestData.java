package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.DepartureReason;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.v2.PlayerAction;
import com.lightchase.contracts.v2.PlayerCommand;
import com.lightchase.contracts.v2.StepInput;
import com.lightchase.contracts.v2.StepResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small builders and script helpers for the v2 engine tests. */
final class V2TestData {

    static final String POLICE = "p-police";
    static final String THIEF = "p-thief";

    private V2TestData() {
    }

    static MatchInit init() {
        return new MatchInit("m-v2", POLICE, THIEF);
    }

    static V2EngineFactory factory() {
        return new V2EngineFactory();
    }

    static com.lightchase.contracts.v2.MatchEngine engine() {
        return factory().create(V2TestMaps.open(), V2TestRules.fast(), init());
    }

    static PlayerCommand command(String playerId, long inputSeq, double moveX, double moveY,
                                 Double aimRad, boolean held, Long dropId) {
        return new PlayerCommand(playerId, inputSeq, moveX, moveY, aimRad, held, dropId);
    }

    static PlayerCommand police(long seq, double moveX, double moveY, Double aim, boolean held) {
        return command(POLICE, seq, moveX, moveY, aim, held, null);
    }

    static PlayerCommand thief(long seq, double moveX, double moveY, boolean held) {
        return command(THIEF, seq, moveX, moveY, null, held, null);
    }

    static PlayerAction use(String playerId, long actionId, int slot, String itemId) {
        return new PlayerAction(playerId, actionId, slot, itemId);
    }

    static StepInput step(long tick, PlayerCommand... commands) {
        List<PlayerCommand> list = new ArrayList<>();
        Collections.addAll(list, commands);
        return new StepInput(tick, list, Collections.emptyList(), Collections.emptyList());
    }

    static StepInput step(long tick, List<PlayerCommand> commands, List<PlayerAction> actions) {
        return new StepInput(tick, commands, actions, Collections.emptyList());
    }

    static StepInput step(long tick, List<PlayerCommand> commands, List<PlayerAction> actions,
                          List<Departure> departures) {
        return new StepInput(tick, commands, actions, departures);
    }

    static StepResult tick(com.lightchase.contracts.v2.MatchEngine engine, long tick,
                           PlayerCommand... commands) {
        return engine.step(step(tick, commands));
    }

    static Departure departure(String playerId, DepartureReason reason) {
        return new Departure(playerId, reason);
    }
}
