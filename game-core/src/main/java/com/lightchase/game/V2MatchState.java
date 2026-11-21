package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.DepartureReason;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.MaterialPoint;
import com.lightchase.contracts.Protection;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.geometry.WorldGeometry;
import com.lightchase.contracts.v2.ActionResult;
import com.lightchase.contracts.v2.CardInstance;
import com.lightchase.contracts.v2.GroundCard;
import com.lightchase.contracts.v2.GroundItem;
import com.lightchase.contracts.v2.InteractionBlock;
import com.lightchase.contracts.v2.InteractionTarget;
import com.lightchase.contracts.v2.InteractionType;
import com.lightchase.contracts.v2.ItemInstance;
import com.lightchase.contracts.v2.ItemType;
import com.lightchase.contracts.v2.MapDefinition;
import com.lightchase.contracts.v2.PlayerAction;
import com.lightchase.contracts.v2.PlayerActionResult;
import com.lightchase.contracts.v2.PortalDefinition;
import com.lightchase.contracts.v2.PortalState;
import com.lightchase.contracts.v2.Position;
import com.lightchase.contracts.v2.PublicEvent;
import com.lightchase.contracts.v2.PublicEventType;
import com.lightchase.contracts.v2.RuleConfig;
import com.lightchase.contracts.v2.TransitView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete mutable v2 match state. Every public operation is called by the single match
 * thread; views are projected separately and never expose these objects.
 */
final class V2MatchState {

    static final class PortalRuntime {
        final PortalDefinition definition;
        boolean unlocked;
        long revision;

        PortalRuntime(PortalDefinition definition) {
            this.definition = definition;
        }
    }

    final MapDefinition map;
    final com.lightchase.contracts.MapDefinition geometryMap;
    final RuleConfig rules;
    final MatchInit init;
    final Map<String, V2ActorState> actors = new LinkedHashMap<>();
    final Map<String, MaterialState> materials = new LinkedHashMap<>();
    final Map<String, MapPoint> deliveryPoints = new LinkedHashMap<>();
    final Map<String, MapPoint> exitPoints = new LinkedHashMap<>();
    final Map<String, GroundItem> groundItems = new LinkedHashMap<>();
    final Map<String, GroundCard> groundCards = new LinkedHashMap<>();
    final Map<String, PortalRuntime> portals = new LinkedHashMap<>();
    final List<MaterialState> delivered = new ArrayList<>();

    V2ActorState police;
    V2ActorState thief;
    MaterialState carrying;
    long tick;
    MatchResult result;
    int marks;
    int beamProgress;
    long invisibleUntilTick;
    long markImmuneUntilTick;
    long shieldEffectUntilTick;
    boolean captured;
    boolean escaped;
    boolean exitsOpen;
    boolean markedThisStep;
    boolean shieldUsedThisStep;
    boolean dropConsumedThisStep;
    boolean policeSeesThief;
    boolean thiefSeesPolice;
    V2TransitState transit;
    String landedActorId;
    long transitSeq;
    final List<PlayerActionResult> actionResults = new ArrayList<>();
    final List<PublicEvent> events = new ArrayList<>();

    V2MatchState(MapDefinition map, RuleConfig rules, MatchInit init) {
        this.map = map;
        this.geometryMap = V2ConfigValidator.baseMap(map);
        this.rules = rules;
        this.init = init;

        add(init.policePlayerId, Role.POLICE);
        add(init.thiefPlayerId, Role.THIEF);
        loadTaskPoints();
        loadGroundObjects();
        refreshProtection();
        policeSeesThief = computePoliceBeam();
        thiefSeesPolice = computeThiefSeesPolice();
        for (V2ActorState actor : actors.values()) {
            InteractionTarget candidate = selectCandidate(actor);
            actor.interaction.candidate = candidate;
            actor.interaction.blockedReason = candidate == null
                    ? relatedReason(actor) : InteractionBlock.NONE;
        }
    }

    private void add(String playerId, Role role) {
        SpawnPose spawn = map.spawns().get(role);
        if (spawn == null) {
            throw new IllegalArgumentException("Missing spawn for " + role);
        }
        V2ActorState actor = new V2ActorState(playerId, role, spawn);
        actors.put(playerId, actor);
        if (role == Role.POLICE) {
            police = actor;
        } else {
            thief = actor;
        }
    }

    private void loadTaskPoints() {
        for (MaterialPoint point : map.materials()) {
            materials.put(point.id, new MaterialState(point));
        }
        for (MapPoint point : map.deliveries()) {
            deliveryPoints.put(point.id, point);
        }
        for (MapPoint point : map.exits()) {
            exitPoints.put(point.id, point);
        }
    }

    private void loadGroundObjects() {
        for (GroundItem item : map.itemSpawns()) {
            groundItems.put(item.itemId(), item);
        }
        for (GroundCard card : map.cardSpawns()) {
            groundCards.put(card.cardId(), card);
        }
        for (PortalDefinition portal : map.portals()) {
            portals.put(portal.portalId(), new PortalRuntime(portal));
        }
    }

    // ------------------------------------------------------------------ step stages

    void beginStep() {
        refreshProtection();
        landedActorId = null;
        if (transit != null && tick >= transit.endTick) {
            land();
        }
        markedThisStep = false;
        shieldUsedThisStep = false;
        dropConsumedThisStep = false;
        actionResults.clear();
        events.clear();
        for (V2ActorState actor : actors.values()) {
            actor.interaction.forcedReason = null;
        }
    }

    private void land() {
        V2ActorState actor = actors.get(transit.playerId);
        if (actor != null) {
            actor.x = transit.to.x();
            actor.y = transit.to.y();
            actor.moveX = 0.0;
            actor.moveY = 0.0;
            actor.held = false;
            actor.interaction.clearActive();
            actor.interaction.requiresRelease = true;
            actor.interaction.blockedReason = InteractionBlock.RELEASE_REQUIRED;
            landedActorId = actor.playerId;
        }
        transit = null;
    }

    private void refreshProtection() {
        if (protection() == Protection.NORMAL
                && (invisibleUntilTick != 0 || markImmuneUntilTick != 0
                || shieldEffectUntilTick != 0)) {
            invisibleUntilTick = 0;
            markImmuneUntilTick = 0;
            shieldEffectUntilTick = 0;
            beamProgress = 0;
        }
    }

    void validateActions(List<PlayerAction> actions) {
        if (actions == null) {
            throw new IllegalArgumentException("StepInput.actions must not be null");
        }
        for (PlayerAction action : actions) {
            if (action == null) {
                throw new IllegalArgumentException("PlayerAction must not be null");
            }
            V2ActorState actor = actors.get(action.playerId());
            if (actor == null) {
                throw new IllegalArgumentException("Unknown player: " + action.playerId());
            }
            if (action.actionId() < 1) {
                throw new IllegalArgumentException("actionId must be >= 1");
            }
            if (action.slot() < 0 || action.slot() > 1) {
                throw new IllegalArgumentException("slot must be 0 or 1");
            }
            if (action.expectedItemId() == null || action.expectedItemId().trim().isEmpty()) {
                throw new IllegalArgumentException("expectedItemId must not be blank");
            }
        }
    }

    void processActions(List<PlayerAction> actions, V2InputMerger.Plan plan) {
        for (PlayerAction action : actions) {
            V2ActorState actor = actors.get(action.playerId());
            actionResults.add(new PlayerActionResult(actor.playerId,
                    applyAction(actor, action, plan.forPlayer(actor.playerId))));
        }
    }

    private ActionResult applyAction(V2ActorState actor, PlayerAction action,
                                     V2InputMerger.PlayerStep step) {
        if (result != null) {
            return failure(action, "MATCH_ENDED");
        }
        ActionResult cached = actor.cachedAction(action.actionId());
        if (cached != null
                && cached.slot() == action.slot()
                && cached.expectedItemId().equals(action.expectedItemId())) {
            return cached;
        }
        if (action.actionId() <= actor.highestActionId) {
            return failure(action, "STALE_ACTION");
        }

        ItemInstance item = actor.inventory[action.slot()];
        ActionResult result;
        if (item == null) {
            result = failure(action, "EMPTY_SLOT");
        } else if (!item.itemId().equals(action.expectedItemId())) {
            result = failure(action, "ITEM_INSTANCE_MISMATCH");
        } else if (!roleCanUse(actor.role, item.type())) {
            result = failure(action, "ROLE_FORBIDDEN");
        } else if (isInTransit(actor)) {
            result = failure(action, "IN_TRANSIT");
        } else if (item.type() != ItemType.SHIELD && effectActive(actor, item.type())) {
            result = failure(action, "EFFECT_ACTIVE");
        } else {
            applyItem(actor, item, step);
            actor.inventory[action.slot()] = null;
            result = new ActionResult(init.matchId, action.actionId(), action.slot(),
                    action.expectedItemId(), true, "OK", tick);
        }
        actor.rememberAction(action.actionId(), result);
        return result;
    }

    private void applyItem(V2ActorState actor, ItemInstance item,
                           V2InputMerger.PlayerStep step) {
        switch (item.type()) {
            case SPEED_BOOST:
                actor.speedUntilTick = tick + rules.speedBoostTicks();
                break;
            case LIGHT_BOOST:
                actor.lightUntilTick = tick + rules.lightBoostTicks();
                break;
            case SHIELD:
                beamProgress = 0;
                shieldUsedThisStep = true;
                shieldEffectUntilTick = Math.max(tick + rules.shieldTicks(), invisibleUntilTick);
                invisibleUntilTick = shieldEffectUntilTick;
                actor.interaction.clearActive();
                actor.interaction.requiresRelease = step.finalHeld;
                break;
            default:
                throw new IllegalArgumentException("Unknown item type: " + item.type());
        }
    }

    private static boolean roleCanUse(Role role, ItemType type) {
        if (type == ItemType.LIGHT_BOOST) return role == Role.POLICE;
        if (type == ItemType.SHIELD) return role == Role.THIEF;
        return true;
    }

    private boolean effectActive(V2ActorState actor, ItemType type) {
        if (type == ItemType.SPEED_BOOST) return actor.speedActive(tick);
        if (type == ItemType.LIGHT_BOOST) return actor.lightActive(tick);
        return false;
    }

    private ActionResult failure(PlayerAction action, String code) {
        return new ActionResult(init.matchId, action.actionId(), action.slot(),
                action.expectedItemId(), false, code, tick);
    }

    void move(V2InputMerger.Plan plan) {
        for (V2ActorState actor : actors.values()) {
            V2InputMerger.PlayerStep step = plan.forPlayer(actor.playerId);
            boolean inTransit = isInTransit(actor);
            boolean landed = actor.playerId.equals(landedActorId);
            if (inTransit || landed) {
                if (!landed && step.aimRad != null) {
                    actor.aimRad = V2RuleMath.angle(step.aimRad);
                }
                actor.held = landed ? false : step.finalHeld;
                actor.lastInputSeq = Math.max(actor.lastInputSeq, step.inputSeq);
                continue;
            }
            if (step.aimRad != null) {
                actor.aimRad = V2RuleMath.angle(step.aimRad);
            }
            applyMovement(actor, step);
            actor.moveX = step.moveX;
            actor.moveY = step.moveY;
            actor.held = step.finalHeld;
            actor.lastInputSeq = Math.max(actor.lastInputSeq, step.inputSeq);
        }
    }

    private void applyMovement(V2ActorState actor, V2InputMerger.PlayerStep step) {
        double magnitude = Math.max(1.0, Math.hypot(step.moveX, step.moveY));
        double perTick = effectiveSpeed(actor) / rules.tickRate();
        double dx = step.moveX / magnitude * perTick;
        double dy = step.moveY / magnitude * perTick;
        int parts = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 0.1));

        double x = actor.x;
        double y = actor.y;
        for (int i = 0; i < parts; i++) {
            if (WorldGeometry.canStand(geometryMap, x + dx / parts, y, rules.radius())) {
                x += dx / parts;
            }
            if (WorldGeometry.canStand(geometryMap, x, y + dy / parts, rules.radius())) {
                y += dy / parts;
            }
        }
        actor.x = x;
        actor.y = y;
        if (step.moveX != 0.0 || step.moveY != 0.0) {
            actor.headingRad = V2RuleMath.angle(Math.atan2(step.moveY, step.moveX));
        }
    }

    void updateLight() {
        markedThisStep = false;
        thiefSeesPolice = computeThiefSeesPolice();
        boolean inBeam = computePoliceBeam();
        boolean surface = !isInTransit(thief);
        boolean visible = surface && protection() != Protection.INVISIBLE && inBeam;
        policeSeesThief = visible;

        if (visible && protection() == Protection.NORMAL) {
            beamProgress++;
            if (beamProgress >= rules.markTicks()) {
                beamProgress = 0;
                marks++;
                markedThisStep = true;
                shieldEffectUntilTick = 0;
                if (marks >= rules.maxMarks()) {
                    captured = true;
                    invisibleUntilTick = 0;
                    markImmuneUntilTick = 0;
                    beamProgress = 0;
                    policeSeesThief = inBeam;
                } else if (rules.invisibleTicks() > 0) {
                    protectionAfterMark(inBeam, tick + rules.invisibleTicks(),
                            tick + rules.invisibleTicks() + rules.immuneTicks());
                } else if (rules.immuneTicks() > 0) {
                    protectionAfterMark(inBeam, tick, tick + rules.immuneTicks());
                } else {
                    invisibleUntilTick = 0;
                    markImmuneUntilTick = 0;
                    policeSeesThief = inBeam;
                }
            }
        } else {
            beamProgress = 0;
        }
    }

    private void protectionAfterMark(boolean inBeam, long invisibleUntil, long immuneUntil) {
        invisibleUntilTick = invisibleUntil;
        markImmuneUntilTick = immuneUntil;
        policeSeesThief = protection() != Protection.INVISIBLE && inBeam;
    }

    void applyDrop(V2InputMerger.Plan plan) {
        V2InputMerger.PlayerStep step = plan.forPlayer(thief.playerId);
        if (step.dropIds.isEmpty()) {
            return;
        }
        long last = thief.lastDropId;
        boolean any = false;
        for (Long id : step.dropIds) {
            if (id == null || id <= last) {
                continue;
            }
            last = id;
            any = true;
        }
        if (!any) {
            return;
        }
        thief.lastDropId = last;
        if (isInTransit(thief)) {
            // The drop id is consumed so a delayed pulse cannot fire after landing, but no
            // material may leave the thief during the trip.
            return;
        }
        dropConsumedThisStep = true;
        if (carrying != null) {
            carrying.status = MaterialState.Status.GROUND;
            carrying.x = thief.x;
            carrying.y = thief.y;
            carrying = null;
        }
        thief.interaction.clearActive();
        thief.interaction.requiresRelease = step.finalHeld;
    }

    void updateInteractions(V2InputMerger.Plan plan) {
        for (V2ActorState actor : actors.values()) {
            updateInteraction(actor, plan.forPlayer(actor.playerId));
        }
    }

    private void updateInteraction(V2ActorState actor, V2InputMerger.PlayerStep step) {
        V2InteractionState in = actor.interaction;
        boolean inTransit = isInTransit(actor);
        boolean landed = actor.playerId.equals(landedActorId);
        boolean invisible = actor.role == Role.THIEF && protection() == Protection.INVISIBLE;
        boolean moved = step.anyNonZeroMove && !inTransit && !landed;
        boolean dropped = actor.role == Role.THIEF && dropConsumedThisStep;
        boolean shielded = actor.role == Role.THIEF && shieldUsedThisStep;
        boolean marked = actor.role == Role.THIEF && markedThisStep;
        boolean cancel = inTransit || landed || invisible || moved || marked || dropped || shielded;

        in.forcedReason = null;
        if (step.releaseEdge) {
            in.clearActive();
            in.requiresRelease = false;
        }
        if (in.requiresRelease && !step.finalHeld) {
            in.requiresRelease = false;
        }

        if (in.active != null) {
            if (cancel) {
                in.clearActive();
                in.requiresRelease = step.finalHeld;
            } else if (!step.finalHeld) {
                in.clearActive();
                in.requiresRelease = false;
            } else if (!targetValid(actor, in.active)) {
                in.clearActive();
                in.requiresRelease = step.finalHeld;
                in.forcedReason = InteractionBlock.TARGET_GONE;
            } else {
                in.progressTicks++;
                if (in.progressTicks >= requiredTicks(in.active)) {
                    InteractionTarget completed = in.active;
                    in.clearActive();
                    if (!completeInteraction(actor, completed)) {
                        in.forcedReason = InteractionBlock.TARGET_GONE;
                    }
                    in.requiresRelease = step.finalHeld;
                }
            }
        } else if (!cancel && !in.requiresRelease && step.pressEdge && step.finalHeld) {
            InteractionTarget candidate = selectCandidate(actor);
            if (candidate != null) {
                in.active = candidate;
                in.progressTicks = 1;
                if (in.progressTicks >= requiredTicks(candidate)) {
                    in.clearActive();
                    if (!completeInteraction(actor, candidate)) {
                        in.forcedReason = InteractionBlock.TARGET_GONE;
                    }
                    in.requiresRelease = step.finalHeld;
                }
            }
        } else if (cancel && step.finalHeld) {
            in.requiresRelease = true;
        }

        InteractionTarget display;
        boolean nowInTransit = isInTransit(actor);
        if (cancel || nowInTransit) {
            display = null;
        } else if (in.active != null) {
            display = in.active;
        } else if (in.requiresRelease) {
            display = null;
        } else {
            display = selectCandidate(actor);
        }
        in.candidate = display;
        if (in.forcedReason != null) {
            in.candidate = null;
            in.blockedReason = in.forcedReason;
        } else if (display != null) {
            in.blockedReason = InteractionBlock.NONE;
        } else {
            in.blockedReason = blockedReason(actor, in, nowInTransit, invisible, moved, landed);
        }
    }

    private InteractionBlock blockedReason(V2ActorState actor, V2InteractionState in,
                                           boolean inTransit, boolean invisible, boolean moved,
                                           boolean landed) {
        if (inTransit) return InteractionBlock.IN_TRANSIT;
        if (invisible) return InteractionBlock.INVISIBLE;
        if (moved) return InteractionBlock.MOVING;
        if (in.requiresRelease) return InteractionBlock.RELEASE_REQUIRED;
        if (landed) return InteractionBlock.RELEASE_REQUIRED;
        return relatedReason(actor);
    }

    private InteractionBlock relatedReason(V2ActorState actor) {
        if (actor == null) return InteractionBlock.NO_TARGET;
        InteractionTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        InteractionBlock reason = InteractionBlock.NO_TARGET;

        for (GroundItem item : groundItems.values()) {
            double d = WorldGeometry.distance(actor.x, actor.y, item.x(), item.y());
            if (!withinReach(actor, item.x(), item.y())) continue;
            InteractionBlock candidate = item.ownerRole() != actor.role
                    ? InteractionBlock.ROLE_FORBIDDEN
                    : actor.emptySlot() < 0 ? InteractionBlock.INVENTORY_FULL : null;
            if (candidate != null && better(best, bestDistance, item.itemId(), d)) {
                best = new InteractionTarget(InteractionType.PICKUP_ITEM, item.itemId(), null);
                bestDistance = d;
                reason = candidate;
            }
        }
        for (GroundCard card : groundCards.values()) {
            double d = WorldGeometry.distance(actor.x, actor.y, card.x(), card.y());
            if (!withinReach(actor, card.x(), card.y())) continue;
            InteractionBlock candidate;
            if (actor.role != Role.THIEF) {
                candidate = InteractionBlock.ROLE_FORBIDDEN;
            } else if (actor.card != null) {
                candidate = InteractionBlock.CARD_SLOT_FULL;
            } else {
                continue;
            }
            if (better(best, bestDistance, card.cardId(), d)) {
                best = new InteractionTarget(InteractionType.PICKUP_CARD, card.cardId(), null);
                bestDistance = d;
                reason = candidate;
            }
        }
        for (PortalRuntime portal : portals.values()) {
            Position entry = portal.definition.entry();
            double d = WorldGeometry.distance(actor.x, actor.y, entry.x(), entry.y());
            if (!withinReach(actor, entry.x(), entry.y())) continue;
            InteractionBlock candidate = null;
            if (actor.role != Role.THIEF) {
                candidate = InteractionBlock.ROLE_FORBIDDEN;
            } else if (!portal.unlocked) {
                if (actor.card == null) {
                    candidate = InteractionBlock.CARD_REQUIRED;
                } else if (!actor.card.portalId().equals(portal.definition.portalId())) {
                    candidate = InteractionBlock.WRONG_CARD;
                }
            }
            if (candidate != null && better(best, bestDistance, portal.definition.portalId(), d)) {
                best = new InteractionTarget(InteractionType.UNLOCK_PORTAL,
                        portal.definition.portalId(), null);
                bestDistance = d;
                reason = candidate;
            }
        }
        return reason;
    }

    private static boolean better(InteractionTarget best, double bestDistance, String id,
                                  double distance) {
        if (best == null) return true;
        if (distance < bestDistance - WorldGeometry.EPS) return true;
        return Math.abs(distance - bestDistance) <= WorldGeometry.EPS
                && id.compareTo(best.targetId()) < 0;
    }

    private InteractionTarget selectCandidate(V2ActorState actor) {
        if (isInTransit(actor)) return null;
        if (actor.role == Role.THIEF && protection() == Protection.INVISIBLE) return null;

        InteractionTarget best = null;
        double bestDistance = Double.MAX_VALUE;

        if (actor.role == Role.THIEF) {
            if (carrying == null) {
                for (MaterialState material : materials.values()) {
                    if (material.status != MaterialState.Status.GROUND) continue;
                    InteractionTarget target = new InteractionTarget(
                            InteractionType.PICKUP, material.id, null);
                    if (withinReach(actor, material.x, material.y)) {
                        best = pick(best, bestDistance, target, material.x, material.y, actor);
                        if (best == target) {
                            bestDistance = WorldGeometry.distance(actor.x, actor.y,
                                    material.x, material.y);
                        }
                    }
                }
            } else if (carrying.deliveryId != null) {
                MapPoint point = deliveryPoints.get(carrying.deliveryId);
                if (point != null) {
                    InteractionTarget target = new InteractionTarget(
                            InteractionType.DELIVER, point.id, null);
                    if (withinReach(actor, point.x, point.y)) {
                        best = pick(best, bestDistance, target, point.x, point.y, actor);
                        if (best == target) {
                            bestDistance = WorldGeometry.distance(actor.x, actor.y,
                                    point.x, point.y);
                        }
                    }
                }
            }
            if (exitsOpen) {
                for (MapPoint point : exitPoints.values()) {
                    InteractionTarget target = new InteractionTarget(
                            InteractionType.ESCAPE, point.id, null);
                    if (withinReach(actor, point.x, point.y)) {
                        best = pick(best, bestDistance, target, point.x, point.y, actor);
                        if (best == target) {
                            bestDistance = WorldGeometry.distance(actor.x, actor.y,
                                    point.x, point.y);
                        }
                    }
                }
            }
        }

        if (actor.emptySlot() >= 0) {
            for (GroundItem item : groundItems.values()) {
                if (item.ownerRole() != actor.role) continue;
                InteractionTarget target = new InteractionTarget(
                        InteractionType.PICKUP_ITEM, item.itemId(), null);
                if (withinReach(actor, item.x(), item.y())) {
                    best = pick(best, bestDistance, target, item.x(), item.y(), actor);
                    if (best == target) {
                        bestDistance = WorldGeometry.distance(actor.x, actor.y,
                                item.x(), item.y());
                    }
                }
            }
        }

        if (actor.role == Role.THIEF && actor.card == null) {
            for (GroundCard card : groundCards.values()) {
                InteractionTarget target = new InteractionTarget(
                        InteractionType.PICKUP_CARD, card.cardId(), null);
                if (withinReach(actor, card.x(), card.y())) {
                    best = pick(best, bestDistance, target, card.x(), card.y(), actor);
                    if (best == target) {
                        bestDistance = WorldGeometry.distance(actor.x, actor.y,
                                card.x(), card.y());
                    }
                }
            }
        }

        if (actor.role == Role.THIEF) {
            for (PortalRuntime portal : portals.values()) {
                Position entry = portal.definition.entry();
                if (!withinReach(actor, entry.x(), entry.y())) continue;
                InteractionTarget target;
                if (!portal.unlocked) {
                    if (actor.card == null
                            || !actor.card.portalId().equals(portal.definition.portalId())) {
                        continue;
                    }
                    target = new InteractionTarget(InteractionType.UNLOCK_PORTAL,
                            portal.definition.portalId(), actor.card.cardId());
                } else {
                    target = new InteractionTarget(InteractionType.ENTER_PORTAL,
                            portal.definition.portalId(), null);
                }
                best = pick(best, bestDistance, target, entry.x(), entry.y(), actor);
                if (best == target) {
                    bestDistance = WorldGeometry.distance(actor.x, actor.y,
                            entry.x(), entry.y());
                }
            }
        }
        return best;
    }

    private InteractionTarget pick(InteractionTarget best, double bestDistance,
                                   InteractionTarget candidate, double x, double y,
                                   V2ActorState actor) {
        double distance = WorldGeometry.distance(actor.x, actor.y, x, y);
        if (best == null || distance < bestDistance - WorldGeometry.EPS
                || (Math.abs(distance - bestDistance) <= WorldGeometry.EPS
                && candidate.targetId().compareTo(best.targetId()) < 0)) {
            return candidate;
        }
        return best;
    }

    private boolean targetValid(V2ActorState actor, InteractionTarget target) {
        if (target == null || isInTransit(actor)) return false;
        Position point = targetPosition(target);
        if (point == null || !withinReach(actor, point.x(), point.y())) return false;
        switch (target.type()) {
            case PICKUP: {
                if (actor.role != Role.THIEF || carrying != null) return false;
                MaterialState material = materials.get(target.targetId());
                return material != null && material.status == MaterialState.Status.GROUND;
            }
            case DELIVER: {
                if (actor.role != Role.THIEF || carrying == null
                        || carrying.deliveryId == null
                        || !carrying.deliveryId.equals(target.targetId())) {
                    return false;
                }
                return deliveryPoints.containsKey(target.targetId());
            }
            case ESCAPE:
                return actor.role == Role.THIEF && exitsOpen
                        && exitPoints.containsKey(target.targetId());
            case PICKUP_ITEM: {
                GroundItem item = groundItems.get(target.targetId());
                return item != null && item.ownerRole() == actor.role && actor.emptySlot() >= 0;
            }
            case PICKUP_CARD: {
                if (actor.role != Role.THIEF || actor.card != null) return false;
                return groundCards.containsKey(target.targetId());
            }
            case UNLOCK_PORTAL: {
                if (actor.role != Role.THIEF || actor.card == null) return false;
                PortalRuntime portal = portals.get(target.targetId());
                return portal != null && !portal.unlocked
                        && actor.card.portalId().equals(portal.definition.portalId())
                        && target.expectedCardId() != null
                        && target.expectedCardId().equals(actor.card.cardId());
            }
            case ENTER_PORTAL: {
                if (actor.role != Role.THIEF) return false;
                PortalRuntime portal = portals.get(target.targetId());
                return portal != null && portal.unlocked;
            }
            default:
                return false;
        }
    }

    private boolean completeInteraction(V2ActorState actor, InteractionTarget target) {
        if (!targetValid(actor, target)) {
            return false;
        }
        switch (target.type()) {
            case PICKUP: {
                MaterialState material = materials.get(target.targetId());
                material.status = MaterialState.Status.CARRIED;
                material.everPicked = true;
                carrying = material;
                return true;
            }
            case DELIVER: {
                if (carrying == null || !target.targetId().equals(carrying.deliveryId)) {
                    return false;
                }
                carrying.status = MaterialState.Status.DELIVERED;
                delivered.add(carrying);
                carrying = null;
                exitsOpen = delivered.size() >= 3;
                return true;
            }
            case ESCAPE: {
                if (!exitsOpen) return false;
                escaped = true;
                return true;
            }
            case PICKUP_ITEM: {
                GroundItem item = groundItems.get(target.targetId());
                int slot = actor.emptySlot();
                if (item == null || slot < 0 || item.ownerRole() != actor.role) return false;
                groundItems.remove(target.targetId());
                actor.inventory[slot] = new ItemInstance(item.itemId(), item.type());
                return true;
            }
            case PICKUP_CARD: {
                GroundCard card = groundCards.get(target.targetId());
                if (card == null || actor.card != null) return false;
                groundCards.remove(target.targetId());
                actor.card = new CardInstance(card.cardId(), card.portalId());
                return true;
            }
            case UNLOCK_PORTAL: {
                PortalRuntime portal = portals.get(target.targetId());
                if (portal == null || portal.unlocked || actor.card == null
                        || !actor.card.portalId().equals(portal.definition.portalId())
                        || target.expectedCardId() == null
                        || !target.expectedCardId().equals(actor.card.cardId())) {
                    return false;
                }
                portal.unlocked = true;
                portal.revision = 1;
                actor.card = null;
                return true;
            }
            case ENTER_PORTAL: {
                PortalRuntime portal = portals.get(target.targetId());
                if (portal == null || !portal.unlocked) return false;
                startTransit(actor, portal);
                return true;
            }
            default:
                return false;
        }
    }

    private void startTransit(V2ActorState actor, PortalRuntime portal) {
        transitSeq++;
        String transitId = "T" + transitSeq;
        long start = tick;
        long end = tick + rules.transitTicks();
        transit = new V2TransitState(transitId, portal.definition.portalId(), actor.playerId,
                portal.definition.entry(), portal.definition.exit(), start, end);
        events.add(new PublicEvent(transitId, PublicEventType.PORTAL_STARTED,
                init.matchId, tick, portal.definition.portalId(), transitId));
        policeSeesThief = false;
        thiefSeesPolice = computeThiefSeesPolice();
    }

    private Position targetPosition(InteractionTarget target) {
        switch (target.type()) {
            case PICKUP: {
                MaterialState material = materials.get(target.targetId());
                return material == null ? null : new Position(material.x, material.y);
            }
            case DELIVER: {
                MapPoint point = deliveryPoints.get(target.targetId());
                return point == null ? null : new Position(point.x, point.y);
            }
            case ESCAPE: {
                MapPoint point = exitPoints.get(target.targetId());
                return point == null ? null : new Position(point.x, point.y);
            }
            case PICKUP_ITEM: {
                GroundItem item = groundItems.get(target.targetId());
                return item == null ? null : new Position(item.x(), item.y());
            }
            case PICKUP_CARD: {
                GroundCard card = groundCards.get(target.targetId());
                return card == null ? null : new Position(card.x(), card.y());
            }
            case UNLOCK_PORTAL:
            case ENTER_PORTAL: {
                PortalRuntime portal = portals.get(target.targetId());
                return portal == null ? null : portal.definition.entry();
            }
            default:
                return null;
        }
    }

    private int requiredTicks(InteractionTarget target) {
        switch (target.type()) {
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

    private boolean withinReach(V2ActorState actor, double x, double y) {
        return WorldGeometry.distance(actor.x, actor.y, x, y) <= rules.interactRange()
                + WorldGeometry.EPS
                && !WorldGeometry.segmentBlocked(geometryMap, actor.x, actor.y, x, y);
    }

    void resolveOutcome(List<Departure> departures) {
        if (result != null) {
            return;
        }
        if (tick >= rules.durationTicks()) {
            end(Role.POLICE, ResultReason.TIMEOUT, null);
            return;
        }
        if (captured) {
            end(Role.POLICE, ResultReason.CAPTURED, null);
            return;
        }
        if (escaped) {
            end(Role.THIEF, ResultReason.ESCAPED, null);
            return;
        }
        if (departures == null || departures.isEmpty()) {
            return;
        }
        if (departures.size() >= 2) {
            end(null, ResultReason.BOTH_LEFT, null);
            return;
        }
        Departure departure = departures.get(0);
        Role winner = departure.playerId.equals(init.policePlayerId) ? Role.THIEF : Role.POLICE;
        end(winner, reasonOf(departure.reason), departure.playerId);
    }

    MatchResult abort() {
        if (result == null) {
            end(null, ResultReason.SERVER_ABORTED, null);
        }
        return result;
    }

    private void end(Role winner, ResultReason reason, String departedPlayerId) {
        if (result != null) {
            return;
        }
        for (V2ActorState actor : actors.values()) {
            actor.interaction.reset();
        }
        transit = null;
        events.clear();
        result = new MatchResult(init.matchId + ":result", init.matchId, winner, reason,
                departedPlayerId, (int) tick, marks, delivered.size(), tick);
    }

    void clearForEnd() {
        for (V2ActorState actor : actors.values()) {
            actor.interaction.reset();
        }
        transit = null;
        events.clear();
    }

    private static ResultReason reasonOf(DepartureReason reason) {
        switch (reason) {
            case QUIT:
                return ResultReason.QUIT;
            case BACKGROUND:
                return ResultReason.BACKGROUND;
            case DISCONNECT:
                return ResultReason.DISCONNECT;
            default:
                throw new IllegalArgumentException("Unknown departure reason: " + reason);
        }
    }

    List<PlayerActionResult> takeActionResults() {
        List<PlayerActionResult> copy = new ArrayList<>(actionResults);
        actionResults.clear();
        return copy;
    }

    List<PublicEvent> takeEvents() {
        List<PublicEvent> copy = new ArrayList<>(events);
        events.clear();
        return copy;
    }

    // ------------------------------------------------------------------ derived state

    Protection protection() {
        if (tick < invisibleUntilTick) return Protection.INVISIBLE;
        if (tick < markImmuneUntilTick) return Protection.IMMUNE;
        return Protection.NORMAL;
    }

    int protectionRemainingTicks() {
        if (protection() == Protection.INVISIBLE) {
            return (int) Math.max(0L, invisibleUntilTick - tick);
        }
        if (protection() == Protection.IMMUNE) {
            return (int) Math.max(0L, markImmuneUntilTick - tick);
        }
        return 0;
    }

    boolean isInTransit(V2ActorState actor) {
        return transit != null && transit.playerId.equals(actor.playerId);
    }

    Position observationPosition(V2ActorState actor) {
        if (isInTransit(actor)) {
            return transit.positionAt(tick);
        }
        return new Position(actor.x, actor.y);
    }

    Position selfPosition(V2ActorState actor) {
        return observationPosition(actor);
    }

    double effectiveSpeed(V2ActorState actor) {
        return rules.speed() * (actor.speedActive(tick) ? rules.speedBoostMultiplier() : 1.0);
    }

    double effectiveRange() {
        return rules.beamRange()
                * (police.lightActive(tick) ? rules.lightBoostMultiplier() : 1.0);
    }

    private boolean computePoliceBeam() {
        if (isInTransit(thief)) return false;
        return WorldGeometry.inBeam(geometryMap, police.x, police.y, police.aimRad,
                thief.x, thief.y, effectiveRange(), V2RuleMath.halfBeamAngle(rules));
    }

    private boolean computeThiefSeesPolice() {
        Position observation = observationPosition(thief);
        return WorldGeometry.inViewRect(observation.x(), observation.y(),
                police.x, police.y, V2RuleMath.viewHalfWidth(rules),
                V2RuleMath.viewHalfHeight(rules));
    }

    V2ActorState actor(String playerId) {
        return actors.get(playerId);
    }

    List<MaterialState> materialsSorted() {
        List<MaterialState> list = new ArrayList<>(materials.values());
        list.sort(Comparator.comparing(material -> material.id));
        return list;
    }

    List<GroundItem> groundItemsSorted() {
        List<GroundItem> list = new ArrayList<>(groundItems.values());
        list.sort(Comparator.comparing(GroundItem::itemId));
        return list;
    }

    List<GroundCard> groundCardsSorted() {
        List<GroundCard> list = new ArrayList<>(groundCards.values());
        list.sort(Comparator.comparing(GroundCard::cardId));
        return list;
    }

    List<PortalState> portalStates() {
        List<PortalState> list = new ArrayList<>();
        for (PortalRuntime portal : portals.values()) {
            list.add(new PortalState(portal.definition.portalId(), portal.unlocked,
                    portal.revision));
        }
        list.sort(Comparator.comparing(PortalState::portalId));
        return list;
    }

    List<TransitView> transitViews() {
        if (transit == null) return Collections.emptyList();
        return Collections.singletonList(new TransitView(transit.transitId, transit.portalId,
                transit.playerId, transit.from, transit.to, transit.startTick, transit.endTick));
    }

    String visibilitySignature() {
        StringBuilder b = new StringBuilder(256);
        b.append(policeSeesThief).append('|').append(thiefSeesPolice)
                .append('|').append(protection())
                .append('|').append(marks)
                .append('|').append(delivered.size())
                .append('|').append(exitsOpen)
                .append('|').append(transit == null ? "-" : transit.transitId)
                .append('|').append(carrying == null ? "-" : carrying.id);

        for (MaterialState material : materialsSorted()) {
            b.append("|M").append(material.id).append(':').append(material.status)
                    .append(':').append(material.x).append(':').append(material.y)
                    .append(':').append(material.everPicked);
        }
        for (GroundItem item : groundItemsSorted()) {
            b.append("|I").append(item.itemId()).append(':').append(item.type())
                    .append(':').append(item.ownerRole());
        }
        for (GroundCard card : groundCardsSorted()) {
            b.append("|C").append(card.cardId()).append(':').append(card.portalId());
        }
        for (PortalState portal : portalStates()) {
            b.append("|P").append(portal.portalId()).append(':').append(portal.unlocked())
                    .append(':').append(portal.revision());
        }
        for (V2ActorState actor : actors.values()) {
            b.append("|A").append(actor.playerId).append(':');
            for (ItemInstance item : actor.inventory) {
                b.append(item == null ? "-" : item.itemId()).append(',');
            }
            b.append(':').append(actor.card == null ? "-" : actor.card.cardId())
                    .append(':').append(actor.speedUntilTick)
                    .append(':').append(actor.lightUntilTick)
                    .append(':').append(actor.speedActive(tick))
                    .append(':').append(actor.lightActive(tick))
                    .append(':').append(actor.role == Role.THIEF ? invisibleUntilTick : 0)
                    .append(':').append(actor.role == Role.THIEF ? markImmuneUntilTick : 0);
        }
        b.append('|').append(shieldEffectUntilTick);
        return b.toString();
    }
}
