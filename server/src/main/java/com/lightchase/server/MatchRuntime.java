package com.lightchase.server;

import com.lightchase.contracts.*;
import java.util.*;
import java.util.function.Consumer;

/** B's per-match clock and transport inbox. Owned exclusively by the lobby logic thread. */
final class MatchRuntime {
    private static final long INPUT_LEASE_MS = 250;
    private static final int MAX_CATCH_UP = 5;
    private final MatchEngine engine;
    private final String matchId;
    private final int tickRate;
    private final Map<String, InputState> players = new LinkedHashMap<>();
    private final List<PlayerCommand> commands = new ArrayList<>();
    private final Map<String, Departure> departures = new LinkedHashMap<>();
    private long lastTimeMs, credit, tick;
    private MatchResult result;

    private static final class InputState {
        final Role role;
        long receivedSeq, consumedSeq, dropId, lastInputAt;
        boolean leased, departed;
        InputState(Role role) { this.role = role; }
    }

    MatchRuntime(MatchEngine engine, MatchInit init, int tickRate, long now) {
        this.engine = Objects.requireNonNull(engine);
        this.matchId = init.matchId;
        this.tickRate = tickRate;
        lastTimeMs = now;
        players.put(init.policePlayerId, new InputState(Role.POLICE));
        players.put(init.thiefPlayerId, new InputState(Role.THIEF));
    }

    /** Returns a protocol error code; rejected input changes neither inbox nor input lease. */
    String input(String playerId, long inputSeq, double x, double y, boolean held, Long drop, long now) {
        InputState player = players.get(playerId);
        if (player == null || player.departed || result != null) return "INVALID_PHASE";
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 1 || Math.abs(y) > 1
                || inputSeq < 1 || (drop != null && drop < 1)) return "INVALID_INPUT";
        if (player.role == Role.POLICE && (held || drop != null)) return "INVALID_ACTION";
        if (inputSeq <= player.receivedSeq) return "STALE_INPUT";
        // A duplicate drop must not discard a new continuous movement/release command.
        Long newDrop = drop != null && drop > player.dropId ? drop : null;
        if (newDrop != null) player.dropId = newDrop;
        player.receivedSeq = inputSeq;
        player.lastInputAt = now;
        player.leased = true;
        commands.add(new PlayerCommand(playerId, inputSeq, x, y, held, newDrop));
        return null;
    }

    void depart(String playerId, DepartureReason reason) {
        InputState player = players.get(playerId);
        if (player == null || player.departed || result != null) return;
        player.departed = true;
        player.leased = false;
        commands.add(new PlayerCommand(playerId, player.receivedSeq, 0, 0, false, null));
        departures.put(playerId, new Departure(playerId, reason));
    }

    void pump(long now, Consumer<StepResult> afterStep) {
        if (result != null) return;
        long elapsed = Math.max(0, now - lastTimeMs);
        lastTimeMs = now;
        // Credit is integer milliseconds * Hz. 1000 credits buy exactly one 1/Hz step.
        // Guard before multiplying so a long scheduler pause cannot overflow the accumulator.
        if (elapsed > 1000 || credit + elapsed * tickRate > 1000L * tickRate) {
            throw new IllegalStateException("SCHEDULER_LAG");
        }
        credit += elapsed * tickRate;
        for (int count = 0; count < MAX_CATCH_UP && credit >= 1000 && result == null; count++) {
            for (Map.Entry<String, InputState> entry : players.entrySet()) {
                InputState player = entry.getValue();
                if (player.leased && now - player.lastInputAt >= INPUT_LEASE_MS) {
                    commands.add(new PlayerCommand(entry.getKey(), player.receivedSeq, 0, 0, false, null));
                    player.leased = false;
                }
            }
            // Atomic FIFO batch: callbacks/network events can only add to the next boundary.
            List<PlayerCommand> batch = new ArrayList<>(commands);
            List<Departure> leaving = new ArrayList<>(departures.values());
            commands.clear(); departures.clear();
            StepResult step = Objects.requireNonNull(engine.step(new StepInput(tick + 1, batch, leaving)));
            if (step.tick != tick + 1 || step.ended != (step.result != null)) {
                throw new IllegalStateException("INVALID_ENGINE_STEP");
            }
            tick = step.tick;
            credit -= 1000;
            for (PlayerCommand command : batch) {
                InputState player = players.get(command.playerId);
                player.consumedSeq = Math.max(player.consumedSeq, command.inputSeq);
            }
            if (step.ended) result = checkedResult(step.result);
            afterStep.accept(step);
        }
    }

    PlayerView viewFor(String playerId, long now) {
        PlayerView view = engine.viewFor(playerId);
        return new PlayerView(view.matchId, view.tick, now, players.get(playerId).consumedSeq, view.role,
                view.remainingTicks, view.marks, view.deliveredCount, view.exitsOpen,
                view.self, view.opponent, view.originStatus, view.thief);
    }

    MatchResult abort() {
        if (result == null) result = checkedResult(engine.abort());
        return result;
    }

    private MatchResult checkedResult(MatchResult value) {
        if (value == null || !matchId.equals(value.matchId)) throw new IllegalStateException("INVALID_ENGINE_RESULT");
        return value;
    }
}
