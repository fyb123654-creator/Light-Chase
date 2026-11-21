package com.lightchase.game;

import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.v2.MapDefinition;
import com.lightchase.contracts.v2.MatchEngine;
import com.lightchase.contracts.v2.MatchEngineFactory;
import com.lightchase.contracts.v2.RuleConfig;

/**
 * The only public entry point for the R3 rules engine. B creates one of these in the server
 * once the v2 protocol is selected; the v1 factory remains untouched for the running build.
 */
public final class V2EngineFactory implements MatchEngineFactory {

    @Override
    public MatchEngine create(MapDefinition map, RuleConfig config, MatchInit init) {
        V2ConfigValidator.validate(map, config, init);
        return new V2MatchEngine(new V2MatchState(map, config, init));
    }
}
