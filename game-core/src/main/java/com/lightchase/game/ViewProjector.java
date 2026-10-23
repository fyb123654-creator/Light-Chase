package com.lightchase.game;

import com.lightchase.contracts.ActorPose;
import com.lightchase.contracts.GroundMaterial;
import com.lightchase.contracts.InteractionView;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.OriginStatus;
import com.lightchase.contracts.PlayerView;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.ThiefPrivate;
import com.lightchase.contracts.VisibleOpponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Projects an immutable role-filtered view. It never mutates match state, and every
 * returned DTO is independent of the next step.
 */
final class ViewProjector {
    private final RuleConfig rules;
    private final MatchInit init;
    private final MatchState state;

    ViewProjector(RuleConfig rules, MatchInit init, MatchState state) {
        this.rules = rules;
        this.init = init;
        this.state = state;
    }

    PlayerView project(String playerId) {
        ActorState actor = state.actors.get(playerId);
        if (actor == null) {
            throw new IllegalArgumentException("Unknown player: " + playerId);
        }

        List<OriginStatus> origins = new ArrayList<>();
        List<GroundMaterial> ground = new ArrayList<>();
        for (MaterialState material : state.materials.values()) {
            origins.add(new OriginStatus(material.id, material.everPicked));
            if (material.status == MaterialState.Status.GROUND) {
                ground.add(new GroundMaterial(material.id, material.x, material.y));
            }
        }
        origins.sort(Comparator.comparing(origin -> origin.materialId));
        ground.sort(Comparator.comparing(material -> material.materialId));

        List<String> deliveredIds = new ArrayList<>();
        for (MaterialState material : state.delivered) {
            deliveredIds.add(material.id);
        }
        Collections.sort(deliveredIds);

        VisibleOpponent opponent;
        if (actor.role == Role.POLICE) {
            opponent = state.policeSeesThief
                    ? new VisibleOpponent(state.thief.playerId, Role.THIEF,
                            state.thief.x, state.thief.y, state.thief.headingRad,
                            state.carrying != null)
                    : null;
        } else {
            opponent = state.thiefSeesPolice
                    ? new VisibleOpponent(state.police.playerId, Role.POLICE,
                            state.police.x, state.police.y, state.police.headingRad, false)
                    : null;
        }

        String carriedId = state.carrying == null ? null : state.carrying.id;
        ThiefPrivate thief = actor.role == Role.THIEF
                ? new ThiefPrivate(carriedId, state.protection, state.protectionRemainingTicks(),
                        ground, deliveredIds,
                        new InteractionView(state.interaction.candidate, state.interaction.active,
                                state.interaction.progressTicks, state.interaction.requiresRelease,
                                state.interaction.blockedReason))
                : null;

        int remaining = (int) Math.max(0L, (long) rules.durationTicks - state.tick);
        ActorPose self = new ActorPose(actor.playerId, actor.x, actor.y, actor.headingRad);
        return new PlayerView(init.matchId, state.tick, 0, 0, actor.role, remaining,
                state.marks, state.delivered.size(), state.exitsOpen,
                self, opponent, origins, thief);
    }
}
