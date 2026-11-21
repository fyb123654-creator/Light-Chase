package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.v2.PlayerCommand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges all commands of one tick per player. A tick moves once no matter how many INPUT
 * messages arrived; the last command decides direction/held state, while aim is updated only
 * when a command actually carries a non-null angle.
 */
final class V2InputMerger {

    private V2InputMerger() {
    }

    static final class PlayerStep {
        final double moveX;
        final double moveY;
        final boolean anyNonZeroMove;
        final boolean finalHeld;
        final boolean pressEdge;
        final boolean releaseEdge;
        final Double aimRad;
        final List<Long> dropIds;
        final long inputSeq;

        PlayerStep(double moveX, double moveY, boolean anyNonZeroMove, boolean finalHeld,
                   boolean pressEdge, boolean releaseEdge, Double aimRad, List<Long> dropIds,
                   long inputSeq) {
            this.moveX = moveX;
            this.moveY = moveY;
            this.anyNonZeroMove = anyNonZeroMove;
            this.finalHeld = finalHeld;
            this.pressEdge = pressEdge;
            this.releaseEdge = releaseEdge;
            this.aimRad = aimRad;
            this.dropIds = Collections.unmodifiableList(new ArrayList<>(dropIds));
            this.inputSeq = inputSeq;
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

    static Plan merge(List<PlayerCommand> commands, Map<String, V2ActorState> actors) {
        if (commands == null) {
            throw new IllegalArgumentException("StepInput.commands must not be null");
        }
        Plan plan = new Plan();
        Map<String, MutableStep> pending = new LinkedHashMap<>();

        for (PlayerCommand command : commands) {
            if (command == null) {
                throw new IllegalArgumentException("PlayerCommand must not be null");
            }
            V2ActorState actor = actors.get(command.playerId());
            if (actor == null) {
                throw new IllegalArgumentException("Unknown player: " + command.playerId());
            }
            if (command.inputSeq() < 1) {
                throw new IllegalArgumentException("inputSeq must be >= 1");
            }
            requireMove(command.moveX());
            requireMove(command.moveY());
            if (command.aimRad() != null) {
                if (actor.role == Role.THIEF) {
                    throw new IllegalArgumentException("INVALID_INPUT: thief aimRad must be null");
                }
                if (!Double.isFinite(command.aimRad())
                        || command.aimRad() < 0.0 || command.aimRad() >= V2RuleMath.TWO_PI) {
                    throw new IllegalArgumentException("INVALID_INPUT: aimRad must be in [0, 2*pi)");
                }
            }
            if (command.dropId() != null && command.dropId() < 1) {
                throw new IllegalArgumentException("dropId must be >= 1");
            }

            MutableStep step = pending.get(actor.playerId);
            if (step == null) {
                step = new MutableStep(actor.held);
                pending.put(actor.playerId, step);
            }
            step.accept(command);
        }

        for (V2ActorState actor : actors.values()) {
            MutableStep step = pending.get(actor.playerId);
            if (step == null) {
                boolean nonZero = actor.moveX != 0.0 || actor.moveY != 0.0;
                plan.players.put(actor.playerId, new PlayerStep(actor.moveX, actor.moveY, nonZero,
                        actor.held, false, false, null, Collections.emptyList(), actor.lastInputSeq));
            } else {
                plan.players.put(actor.playerId, step.toPlayerStep());
            }
        }
        return plan;
    }

    static List<Departure> mergeDepartures(List<Departure> raw, Map<String, V2ActorState> actors) {
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

    private static void requireMove(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 1.0) {
            throw new IllegalArgumentException("move values must be finite and in [-1, 1]");
        }
    }

    private static final class MutableStep {
        double moveX;
        double moveY;
        boolean anyNonZeroMove;
        boolean held;
        boolean pressEdge;
        boolean releaseEdge;
        Double aimRad;
        final List<Long> dropIds = new ArrayList<>();
        long inputSeq;

        MutableStep(boolean held) {
            this.held = held;
        }

        void accept(PlayerCommand command) {
            moveX = command.moveX();
            moveY = command.moveY();
            if (command.moveX() != 0.0 || command.moveY() != 0.0) {
                anyNonZeroMove = true;
            }
            if (held && !command.interactHeld()) {
                releaseEdge = true;
                held = false;
            } else if (!held && command.interactHeld()) {
                pressEdge = true;
                held = true;
            }
            if (command.aimRad() != null) {
                aimRad = command.aimRad();
            }
            if (command.dropId() != null) {
                dropIds.add(command.dropId());
            }
            inputSeq = Math.max(inputSeq, command.inputSeq());
        }

        PlayerStep toPlayerStep() {
            return new PlayerStep(moveX, moveY, anyNonZeroMove, held, pressEdge, releaseEdge,
                    aimRad, dropIds, inputSeq);
        }
    }
}
