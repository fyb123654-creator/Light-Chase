package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.StepInput;
import com.lightchase.contracts.v2.StepResult;

import java.util.Collections;
import java.util.List;

/** R3 engine: one step is one authoritative simulation tick. */
final class V2MatchEngine implements MatchEngine {

    private final V2MatchState state;
    private final V2ViewProjector views;

    V2MatchEngine(V2MatchState state) {
        this.state = state;
        this.views = new V2ViewProjector(state);
    }

    @Override
    public StepResult step(StepInput input) {
        if (state.result != null) {
            return new StepResult(state.tick, false, true, state.result,
                    Collections.emptyList(), Collections.emptyList());
        }
        if (input == null) {
            throw new IllegalArgumentException("StepInput must not be null");
        }
        if (input.tick() != state.tick + 1) {
            throw new IllegalArgumentException("Non-consecutive tick: expected "
                    + (state.tick + 1) + ", got " + input.tick());
        }

        // Validate everything that could throw before touching any state.
        V2InputMerger.Plan plan = V2InputMerger.merge(input.commands(), state.actors);
        List<Departure> departures = V2InputMerger.mergeDepartures(input.departures(), state.actors);
        state.validateActions(input.actions());

        String before = state.visibilitySignature();
        state.tick++;
        state.beginStep();

        state.processActions(input.actions(), plan);
        state.move(plan);
        state.updateLight();
        if (!state.captured) {
            state.applyDrop(plan);
            state.updateInteractions(plan);
        }
        state.resolveOutcome(departures);

        boolean ended = state.result != null;
        if (ended) {
            state.clearForEnd();
        }
        String after = state.visibilitySignature();
        boolean changed = state.tick == 1 || ended || !before.equals(after);

        List<com.lightchase.contracts.v2.PlayerActionResult> actionResults =
                state.takeActionResults();
        List<com.lightchase.contracts.v2.PublicEvent> events = state.takeEvents();
        return new StepResult(state.tick, changed, ended, state.result, actionResults, events);
    }

    @Override
    public PlayerView viewFor(String playerId) {
        return views.project(playerId);
    }

    @Override
    public MatchResult result() {
        return state.result;
    }

    @Override
    public MatchResult abort() {
        return state.abort();
    }
}
