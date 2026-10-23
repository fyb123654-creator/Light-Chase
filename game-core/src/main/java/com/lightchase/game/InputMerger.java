package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.InputFrame;
import com.lightchase.contracts.PlayerCommand;
import com.lightchase.contracts.StepInput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges all commands of one tick per player. A tick moves once no matter how many
 * INPUT messages arrived; the last command decides direction and held state.
 */
final class InputMerger {
    private InputMerger() {
    }

    static final class PlayerStep {
        final double moveX;
        final double moveY;
        final boolean anyNonZeroMove;
        final boolean finalHeld;
        final boolean pressEdge;
        final boolean releaseEdge;
        final List<Long> dropIds;

        PlayerStep(double moveX, double moveY, boolean anyNonZeroMove, boolean finalHeld,
                   boolean pressEdge, boolean releaseEdge, List<Long> dropIds) {
            this.moveX = moveX;
            this.moveY = moveY;
            this.anyNonZeroMove = anyNonZeroMove;
            this.finalHeld = finalHeld;
            this.pressEdge = pressEdge;
            this.releaseEdge = releaseEdge;
            this.dropIds = Collections.unmodifiableList(new ArrayList<>(dropIds));
        }
    }

    static final class Plan {
        final Map<String, PlayerStep> players = new LinkedHashMap<>();

        PlayerStep forPlayer(String playerId) {
            PlayerStep step = players.get(playerId);
            if (step == null) {
                throw new IllegalArgumentException("No input plan for player " + playerId);
            }
            return step;
        }
    }

    static Plan merge(StepInput input, Map<String, ActorState> actors) {
        if (input == null) {
            throw new IllegalArgumentException("StepInput must not be null");
        }
        if (input.commands == null) {
            throw new IllegalArgumentException("StepInput.commands must not be null");
        }
        Plan plan = new Plan();
        Map<String, MutableStep> pending = new LinkedHashMap<>();

        for (PlayerCommand command : input.commands) {
            if (command == null) {
                throw new IllegalArgumentException("PlayerCommand must not be null");
            }
            ActorState actor = actors.get(command.playerId);
            if (actor == null) {
                throw new IllegalArgumentException("Unknown player: " + command.playerId);
            }
            if (command.inputSeq < 0) {
                throw new IllegalArgumentException("inputSeq must be >= 0");
            }
            // Validates finite values and the [-1, 1] range.
            new InputFrame(command.moveX, command.moveY, command.interactHeld, command.dropId != null);
            if (command.dropId != null && command.dropId < 1) {
                throw new IllegalArgumentException("dropId must be >= 1");
            }

            MutableStep step = pending.get(command.playerId);
            if (step == null) {
                step = new MutableStep(actor.held);
                pending.put(command.playerId, step);
            }
            step.accept(command);
        }

        for (ActorState actor : actors.values()) {
            MutableStep step = pending.get(actor.playerId);
            if (step == null) {
                boolean nonZero = actor.moveX != 0 || actor.moveY != 0;
                plan.players.put(actor.playerId,
                        new PlayerStep(actor.moveX, actor.moveY, nonZero, actor.held, false, false,
                                Collections.emptyList()));
            } else {
                plan.players.put(actor.playerId, step.toPlayerStep());
            }
        }
        return plan;
    }

    static List<Departure> mergeDepartures(List<Departure> raw, Map<String, ActorState> actors) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Departure> unique = new LinkedHashMap<>();
        for (Departure departure : raw) {
            if (departure == null) {
                throw new IllegalArgumentException("Departure must not be null");
            }
            if (!actors.containsKey(departure.playerId)) {
                throw new IllegalArgumentException("Unknown departing player: " + departure.playerId);
            }
            if (departure.reason == null) {
                throw new IllegalArgumentException("Departure.reason must not be null");
            }
            unique.put(departure.playerId, departure);
        }
        return new ArrayList<>(unique.values());
    }

    private static final class MutableStep {
        double moveX;
        double moveY;
        boolean anyNonZeroMove;
        boolean held;
        boolean pressEdge;
        boolean releaseEdge;
        final List<Long> dropIds = new ArrayList<>();

        MutableStep(boolean held) {
            this.held = held;
        }

        void accept(PlayerCommand command) {
            moveX = command.moveX;
            moveY = command.moveY;
            if (command.moveX != 0 || command.moveY != 0) {
                anyNonZeroMove = true;
            }
            if (held && !command.interactHeld) {
                releaseEdge = true;
                held = false;
            } else if (!held && command.interactHeld) {
                pressEdge = true;
                held = true;
            }
            if (command.dropId != null) {
                dropIds.add(command.dropId);
            }
        }

        PlayerStep toPlayerStep() {
            return new PlayerStep(moveX, moveY, anyNonZeroMove, held, pressEdge, releaseEdge, dropIds);
        }
    }
}
