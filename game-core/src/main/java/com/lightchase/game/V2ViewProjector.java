package com.lightchase.game;

import com.lightchase.contracts.GroundMaterial;
import com.lightchase.contracts.OriginStatus;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.v2.ActorPose;
import com.lightchase.contracts.v2.BeamView;
import com.lightchase.contracts.v2.CardInstance;
import com.lightchase.contracts.v2.EffectType;
import com.lightchase.contracts.v2.EffectView;
import com.lightchase.contracts.v2.GroundCard;
import com.lightchase.contracts.v2.GroundItem;
import com.lightchase.contracts.v2.InteractionTarget;
import com.lightchase.contracts.v2.InteractionType;
import com.lightchase.contracts.v2.InteractionView;
import com.lightchase.contracts.v2.ItemInstance;
import com.lightchase.contracts.v2.Motion;
import com.lightchase.contracts.v2.PlayerView;
import com.lightchase.contracts.v2.PortalState;
import com.lightchase.contracts.v2.Position;
import com.lightchase.contracts.v2.RuleConfig;
import com.lightchase.contracts.v2.ThiefPrivate;
import com.lightchase.contracts.v2.TransitView;
import com.lightchase.contracts.v2.VisibleOpponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Projects an immutable, role-filtered v2 view. It never mutates the match state and each call
 * returns fresh DTOs, so a snapshot already handed to the transport can never change.
 */
final class V2ViewProjector {

    private final V2MatchState state;

    V2ViewProjector(V2MatchState state) {
        this.state = state;
    }

    PlayerView project(String playerId) {
        V2ActorState actor = state.actor(playerId);
        if (actor == null) {
            throw new IllegalArgumentException("Unknown player: " + playerId);
        }

        List<OriginStatus> origins = new ArrayList<>();
        List<GroundMaterial> groundMaterials = new ArrayList<>();
        for (MaterialState material : state.materialsSorted()) {
            origins.add(new OriginStatus(material.id, material.everPicked));
            if (material.status == MaterialState.Status.GROUND) {
                groundMaterials.add(new GroundMaterial(material.id, material.x, material.y));
            }
        }

        List<String> deliveredIds = new ArrayList<>();
        for (MaterialState material : state.delivered) {
            deliveredIds.add(material.id);
        }
        Collections.sort(deliveredIds);

        VisibleOpponent opponent = null;
        if (actor.role == Role.POLICE) {
            if (state.policeSeesThief && !state.isInTransit(state.thief)) {
                opponent = new VisibleOpponent(state.thief.playerId, Role.THIEF,
                        state.thief.x, state.thief.y, state.thief.headingRad,
                        null, null, state.carrying != null);
            }
        } else if (state.thiefSeesPolice) {
            opponent = new VisibleOpponent(state.police.playerId, Role.POLICE,
                    state.police.x, state.police.y, state.police.headingRad,
                    state.police.aimRad,
                    new BeamView(state.effectiveRange(), V2RuleMath.halfBeamAngle(state.rules)),
                    false);
        }

        ThiefPrivate thief = null;
        if (actor.role == Role.THIEF) {
            thief = new ThiefPrivate(
                    state.carrying == null ? null : state.carrying.id,
                    state.protection(),
                    state.protectionRemainingTicks(),
                    state.invisibleUntilTick,
                    state.markImmuneUntilTick,
                    new ArrayList<>(groundMaterials),
                    deliveredIds,
                    actor.card == null ? null
                            : new CardInstance(actor.card.cardId(), actor.card.portalId()));
        }

        List<ItemInstance> inventory = Arrays.asList(actor.inventory[0], actor.inventory[1]);
        List<EffectView> effects = effects(actor);

        InteractionView interaction = new InteractionView(
                actor.interaction.candidate,
                actor.interaction.active,
                actor.interaction.progressTicks,
                requiredTicks(state.rules,
                        actor.interaction.active != null ? actor.interaction.active
                                : actor.interaction.candidate),
                actor.interaction.requiresRelease,
                actor.interaction.blockedReason);

        Position selfPosition = state.selfPosition(actor);
        ActorPose self = new ActorPose(actor.playerId, selfPosition.x(), selfPosition.y(),
                V2RuleMath.angle(actor.headingRad),
                actor.role == Role.POLICE ? actor.aimRad : null,
                actor.role == Role.POLICE
                        ? new BeamView(state.effectiveRange(), V2RuleMath.halfBeamAngle(state.rules))
                        : null,
                state.isInTransit(actor) ? Motion.TRANSIT : Motion.GROUND);

        List<GroundItem> groundItems = state.groundItemsSorted();
        List<GroundCard> groundCards = state.groundCardsSorted();
        List<PortalState> portals = state.portalStates();
        List<TransitView> transits = state.transitViews();

        int remaining = (int) Math.max(0L, (long) state.rules.durationTicks() - state.tick);
        return new PlayerView(state.init.matchId, state.tick, 0L, actor.lastInputSeq, actor.role,
                remaining, state.marks, state.delivered.size(), state.exitsOpen,
                self, opponent, origins, thief, inventory, effects, interaction,
                groundItems, groundCards, portals, transits);
    }

    private List<EffectView> effects(V2ActorState actor) {
        List<EffectView> effects = new ArrayList<>();
        if (actor.speedActive(state.tick)) {
            effects.add(new EffectView(EffectType.SPEED_BOOST, actor.speedUntilTick));
        }
        if (actor.lightActive(state.tick)) {
            effects.add(new EffectView(EffectType.LIGHT_BOOST, actor.lightUntilTick));
        }
        if (actor.role == Role.THIEF && state.shieldEffectUntilTick > state.tick
                && state.tick < state.invisibleUntilTick) {
            effects.add(new EffectView(EffectType.SHIELD, state.invisibleUntilTick));
        }
        effects.sort(Comparator.comparing(EffectView::type));
        return effects;
    }

    private static int requiredTicks(RuleConfig rules, InteractionTarget target) {
        if (target == null) {
            return 0;
        }
        InteractionType type = target.type();
        switch (type) {
            case PICKUP:
            case DELIVER:
            case ESCAPE:
                return rules.interactTicks();
            case PICKUP_ITEM:
                return rules.itemPickupTicks();
            case PICKUP_CARD:
                return rules.cardPickupTicks();
            case UNLOCK_PORTAL:
                return rules.portalUnlockTicks();
            case ENTER_PORTAL:
                return rules.portalEnterTicks();
            default:
                return 0;
        }
    }
}
