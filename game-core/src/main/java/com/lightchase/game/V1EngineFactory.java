package com.lightchase.game;

import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MatchEngine;
import com.lightchase.contracts.MatchEngineFactory;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.RuleConfig;

/** Entry point for the full V1 rules engine. The old prototype factory is kept for B. */
public final class V1EngineFactory implements MatchEngineFactory {
    @Override
    public MatchEngine create(MapDefinition map, RuleConfig rules, MatchInit init) {
        return new V1MatchEngine(map, rules, init);
    }
}
