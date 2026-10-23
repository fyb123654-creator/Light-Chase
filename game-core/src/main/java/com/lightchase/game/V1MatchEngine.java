package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.StepInput;
import com.lightchase.contracts.StepResult;

import java.util.List;

/** V1 engine: movement, visibility/marking, materials/interaction and one-shot endings. */
final class V1MatchEngine implements MatchEngine {
    private final MatchState state;
    private final ViewProjector views;

    V1MatchEngine(MapDefinition map, RuleConfig rules, MatchInit init) {
        this.state = new MatchState(map, rules, init);
        this.views = new ViewProjector(rules, init, state);
    }

    @Override
    public StepResult step(StepInput input) {
        if (state.result != null) {
            return new StepResult(state.tick, false, true, state.result);
        }
        if (input == null) {
            throw new IllegalArgumentException("StepInput must not be null");
        }
        if (input.tick != state.tick + 1) {
            throw new IllegalArgumentException("Non-consecutive tick: expected "
                    + (state.tick + 1) + ", got " + input.tick);
        }

        // Validate and merge the whole batch before touching state.
        InputMerger.Plan plan = InputMerger.merge(input, state.actors);
        List<Departure> departures = InputMerger.mergeDepartures(input.departures, state.actors);

        state.tick = input.tick;
        boolean beforePoliceSeesThief = state.policeSeesThief;
        boolean beforeThiefSeesPolice = state.thiefSeesPolice;
        state.refreshProtection(state.tick);
        state.move(plan);
        state.updateLight();
        if (!state.captured) {
            InputMerger.PlayerStep thiefStep = plan.forPlayer(state.thief.playerId);
            boolean dropPulse = state.applyDrop(thiefStep);
            state.updateInteraction(thiefStep, state.markedThisStep, dropPulse);
        }
        state.resolveOutcome(departures);

        boolean visibilityChanged = state.tick == 1 || state.result != null
                || beforePoliceSeesThief != state.policeSeesThief
                || beforeThiefSeesPolice != state.thiefSeesPolice;
        return new StepResult(state.tick, visibilityChanged, state.result != null, state.result);
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
