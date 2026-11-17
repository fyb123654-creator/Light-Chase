package com.lightchase.contracts.v2;
import com.lightchase.contracts.MatchResult;
public interface MatchEngine { StepResult step(StepInput input); PlayerView viewFor(String playerId); MatchResult result(); MatchResult abort(); }
