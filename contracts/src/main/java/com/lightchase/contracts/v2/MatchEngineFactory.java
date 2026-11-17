package com.lightchase.contracts.v2;
import com.lightchase.contracts.MatchInit;
public interface MatchEngineFactory { MatchEngine create(MapDefinition map,RuleConfig config,MatchInit init); }
