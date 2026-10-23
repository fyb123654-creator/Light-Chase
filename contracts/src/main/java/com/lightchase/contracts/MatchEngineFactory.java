package com.lightchase.contracts;
public interface MatchEngineFactory {
MatchEngine create(MapDefinition map, RuleConfig config, MatchInit init);
}
