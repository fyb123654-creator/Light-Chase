package com.lightchase.contracts;
/** Immutable wire/domain DTO. Nullability is defined in docs/development/02_接口契约.md. */
public final class StepInput {
    public final long tick;
    public final java.util.List<PlayerCommand> commands;
    public final java.util.List<Departure> departures;
    public StepInput(long tick, java.util.List<PlayerCommand> commands, java.util.List<Departure> departures) {
        this.tick = tick;
        this.commands = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(commands));
        this.departures = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(departures));
    }
}
