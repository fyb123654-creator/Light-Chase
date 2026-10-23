package com.lightchase.game;
import com.lightchase.contracts.*;
/** S0/S1 movement-only prototype; not the complete V1 rules. */
public final class PrototypeEngineFactory implements MatchEngineFactory {
 public MatchEngine create(MapDefinition map, RuleConfig rules, MatchInit init) {
  return new MovementPrototypeEngine(map,rules,init);
 }
}
