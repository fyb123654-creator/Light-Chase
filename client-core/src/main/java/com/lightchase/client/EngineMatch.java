package com.lightchase.client;

import com.lightchase.contracts.InputFrame;
import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.PlayerCommand;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.StepInput;
import com.lightchase.contracts.StepResult;
import com.lightchase.data.DataFiles;
import com.lightchase.game.V1EngineFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The offline match loop: turn wall-clock time into rule ticks and drive A's engine with them.
 *
 * <p>It used to be the middle of {@code FakeGateway.advance()}, mixed in with the room lifecycle and
 * the projection. Everything here is about one thing - feeding the engine at the rule's tick rate
 * and reporting what it produced - so the fixture class can stay a fixture.
 */
final class EngineMatch {

    private final DataFiles data;
    private MatchEngine engine;
    /** Seconds not yet handed to the engine, so it is stepped at the rule tick rate. */
    private double acc;
    private long seq;
    /** Monotonic id for the drop pulse; the engine drops when it sees a new id. */
    private long dropSeq;

    EngineMatch(DataFiles data) {
        this.data = data;
    }

    /** Starts a fresh match for the given player ids. */
    void start(String matchId, String policeId, String thiefId) {
        this.engine = new V1EngineFactory().create(data.map, data.rules,
                new MatchInit(matchId, policeId, thiefId));
        this.acc = 0;
        this.seq = 0;
        this.dropSeq = 0;
    }

    boolean running() {
        return engine != null;
    }

    /** The engine's authoritative view, or null before the first match. */
    PlayerView view(String playerId) {
        return engine == null ? null : engine.viewFor(playerId);
    }

    /**
     * Advances the engine by {@code dt} seconds of real time.
     *
     * @return the match result when this step ended the match, otherwise null
     */
    MatchResult advance(double dt, InputFrame input, String selfId, String opponentId) {
        if (engine == null) return null;
        double tickLen = 1.0 / data.rules.tickRate;
        acc += dt;
        while (acc >= tickLen) {
            acc -= tickLen;
            seq++;
            List<PlayerCommand> commands = new ArrayList<>(2);
            Long dropId = null;
            InputFrame frame = input;
            if (frame.dropPressed) {
                dropId = ++dropSeq;
                // Consume the pulse: one tap is one drop, not one per tick it stays latched.
                frame = new InputFrame(frame.moveX, frame.moveY, frame.interactHeld, false);
            }
            commands.add(new PlayerCommand(selfId, seq, frame.moveX, frame.moveY,
                    frame.interactHeld, dropId));
            commands.add(new PlayerCommand(opponentId, seq, 0, 0, false, null));
            StepResult step = engine.step(new StepInput(seq, commands, new ArrayList<>()));
            if (step.ended) {
                return step.result;
            }
        }
        return null;
    }
}
