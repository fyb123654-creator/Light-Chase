package com.lightchase.contracts;
public interface MatchEngine {
StepResult step(StepInput input);
PlayerView viewFor(String playerId);
MatchResult result();
MatchResult abort();
}
