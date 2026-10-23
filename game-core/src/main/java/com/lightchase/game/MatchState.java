package com.lightchase.game;

import com.lightchase.contracts.Departure;
import com.lightchase.contracts.DepartureReason;
import com.lightchase.contracts.InteractionTarget;
import com.lightchase.contracts.InteractionType;
import com.lightchase.contracts.MapDefinition;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MatchInit;
import com.lightchase.contracts.MatchResult;
import com.lightchase.contracts.MaterialPoint;
import com.lightchase.contracts.Protection;
import com.lightchase.contracts.ResultReason;
import com.lightchase.contracts.Role;
import com.lightchase.contracts.RuleConfig;
import com.lightchase.contracts.SpawnPose;
import com.lightchase.contracts.geometry.WorldGeometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Match state: movement, visibility/marking, materials/interaction and one-shot endings. */
final class MatchState {
    final MapDefinition map;
    final RuleConfig rules;
    final MatchInit init;
    final Map<String, ActorState> actors = new LinkedHashMap<>();
    final Map<String, MaterialState> materials = new LinkedHashMap<>();
    final Map<String, MapPoint> deliveryPoints = new LinkedHashMap<>();
    final Map<String, MapPoint> exitPoints = new LinkedHashMap<>();
    final List<MaterialState> delivered = new ArrayList<>();
    final InteractionState interaction = new InteractionState();
    ActorState police;
    ActorState thief;
    MaterialState carrying;
    long tick;
    MatchResult result;
    int marks;
    Protection protection = Protection.NORMAL;
    long invisibleUntilTick;
    long immuneUntilTick;
    int beamProgress;
    boolean captured;
    boolean escaped;
    boolean exitsOpen;
    boolean markedThisStep;
    boolean policeSeesThief;
    boolean thiefSeesPolice;

    MatchState(MapDefinition map, RuleConfig rules, MatchInit init) {
        if (map == null) {
            throw new IllegalArgumentException("map must not be null");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules must not be null");
        }
        if (init == null) {
            throw new IllegalArgumentException("init must not be null");
        }
        if (init.matchId == null || init.matchId.trim().isEmpty()) {
            throw new IllegalArgumentException("matchId must not be blank");
        }
        if (init.policePlayerId == null || init.thiefPlayerId == null
                || init.policePlayerId.trim().isEmpty() || init.thiefPlayerId.trim().isEmpty()
                || init.policePlayerId.equals(init.thiefPlayerId)) {
            throw new IllegalArgumentException("two distinct player ids are required");
        }
        if (map.width <= 0 || map.height <= 0) {
            throw new IllegalArgumentException("map size must be positive");
        }
        validateRules(rules);
        this.map = map;
        this.rules = rules;
        this.init = init;
        add(init.policePlayerId, Role.POLICE);
        add(init.thiefPlayerId, Role.THIEF);
        loadTaskPoints();
        refreshVisibility();
        InteractionTarget initialCandidate = selectCandidate();
        interaction.candidate = initialCandidate;
        interaction.blockedReason = initialCandidate == null ? "NO_TARGET" : "NONE";
    }

    /**
     * Validates every rule value the engine consumes, not only movement values.
     * Exact V1 parameter freezing stays in the data layer and its hash check; this
     * guard prevents a programmatic caller from starting an unplayable match.
     */
    private static void validateRules(RuleConfig rules) {
        requirePositive(rules.speed, "speed");
        requirePositive(rules.viewHeight, "viewHeight");
        requirePositive(rules.viewAspect, "viewAspect");
        requirePositive(rules.beamRange, "beamRange");
        requirePositive(rules.beamAngleDeg, "beamAngleDeg");
        if (rules.beamAngleDeg >= 180) {
            throw new IllegalArgumentException("rules.beamAngleDeg must be < 180");
        }
        if (rules.markTicks <= 0) {
            throw new IllegalArgumentException("rules.markTicks must be > 0");
        }
        if (rules.invisibleTicks < 0) {
            throw new IllegalArgumentException("rules.invisibleTicks must be >= 0");
        }
        if (rules.immuneTicks < 0) {
            throw new IllegalArgumentException("rules.immuneTicks must be >= 0");
        }
        requirePositive(rules.interactRange, "interactRange");
        if (rules.interactTicks <= 0) {
            throw new IllegalArgumentException("rules.interactTicks must be > 0");
        }
        if (rules.maxMarks < 1 || rules.maxMarks > 3) {
            throw new IllegalArgumentException("rules.maxMarks must be in 1..3");
        }
        if (rules.durationTicks <= 0 || rules.durationTicks > 5400) {
            throw new IllegalArgumentException("rules.durationTicks must be in 1..5400");
        }
        if (rules.tickRate != 30) {
            throw new IllegalArgumentException("rules.tickRate must be 30");
        }
        if (rules.countdownTicks < 0) {
            throw new IllegalArgumentException("rules.countdownTicks must be >= 0");
        }
        requirePositive(rules.radius, "radius");
        if (rules.radius >= 0.5) {
            throw new IllegalArgumentException("rules.radius must be < 0.5");
        }
    }

    private static void requirePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("rules." + field + " must be positive and finite");
        }
    }

    private void add(String playerId, Role role) {
        if (map.spawns == null || !map.spawns.containsKey(role)) {
            throw new IllegalArgumentException("Missing spawn for " + role);
        }
        SpawnPose spawn = map.spawns.get(role);
        if (spawn == null
                || !Double.isFinite(spawn.x) || !Double.isFinite(spawn.y)
                || !Double.isFinite(spawn.headingRad)
                || !WorldGeometry.canStand(map, spawn.x, spawn.y, rules.radius)) {
            throw new IllegalArgumentException("Invalid spawn for " + role);
        }
        ActorState actor = new ActorState(playerId, role, spawn);
        actors.put(playerId, actor);
        if (role == Role.POLICE) {
            police = actor;
        } else {
            thief = actor;
        }
    }

    private void loadTaskPoints() {
        if (map.materials != null) {
            for (MaterialPoint point : map.materials) {
                materials.put(point.id, new MaterialState(point));
            }
        }
        if (map.deliveries != null) {
            for (MapPoint point : map.deliveries) {
                deliveryPoints.put(point.id, point);
            }
        }
        if (map.exits != null) {
            for (MapPoint point : map.exits) {
                exitPoints.put(point.id, point);
            }
        }
    }

    void move(InputMerger.Plan plan) {
        for (ActorState actor : actors.values()) {
            InputMerger.PlayerStep step = plan.forPlayer(actor.playerId);
            applyMovement(actor, step);
            actor.moveX = step.moveX;
            actor.moveY = step.moveY;
            actor.held = step.finalHeld;
        }
    }

    private void applyMovement(ActorState actor, InputMerger.PlayerStep step) {
        double magnitude = Math.max(1.0, Math.hypot(step.moveX, step.moveY));
        double perTick = rules.speed / rules.tickRate;
        double dx = step.moveX / magnitude * perTick;
        double dy = step.moveY / magnitude * perTick;
        int parts = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / 0.1));

        double x = actor.x;
        double y = actor.y;
        for (int i = 0; i < parts; i++) {
            if (WorldGeometry.canStand(map, x + dx / parts, y, rules.radius)) {
                x += dx / parts;
            }
            if (WorldGeometry.canStand(map, x, y + dy / parts, rules.radius)) {
                y += dy / parts;
            }
        }
        actor.x = x;
        actor.y = y;
        if (step.moveX != 0 || step.moveY != 0) {
            actor.headingRad = RuleMath.normalizeHeading(Math.atan2(step.moveY, step.moveX));
        }
    }

    /** Called at the start of a step, before movement. */
    void refreshProtection(long atTick) {
        if (protection == Protection.NORMAL) {
            return;
        }
        if (atTick >= immuneUntilTick) {
            protection = Protection.NORMAL;
            invisibleUntilTick = 0;
            immuneUntilTick = 0;
            beamProgress = 0;
        } else if (atTick >= invisibleUntilTick) {
            protection = Protection.IMMUNE;
        }
    }

    /** Called after movement. */
    void updateLight() {
        markedThisStep = false;
        thiefSeesPolice = computeThiefSeesPolice();
        boolean inBeam = computePoliceBeam();
        boolean visible = protection != Protection.INVISIBLE && inBeam;
        policeSeesThief = visible;

        if (visible && protection == Protection.NORMAL) {
            beamProgress++;
            if (beamProgress >= rules.markTicks) {
                beamProgress = 0;
                marks++;
                markedThisStep = true;
                if (marks >= rules.maxMarks) {
                    captured = true;
                    protection = Protection.NORMAL;
                    invisibleUntilTick = 0;
                    immuneUntilTick = 0;
                    interaction.clearActive();
                } else if (rules.invisibleTicks > 0) {
                    protection = Protection.INVISIBLE;
                    invisibleUntilTick = tick + rules.invisibleTicks;
                    immuneUntilTick = invisibleUntilTick + rules.immuneTicks;
                } else if (rules.immuneTicks > 0) {
                    // Zero-length invisibility still means the mark is followed by immunity.
                    protection = Protection.IMMUNE;
                    invisibleUntilTick = tick;
                    immuneUntilTick = tick + rules.immuneTicks;
                } else {
                    // Both phases are zero-length: remain ordinary and do not mark twice this step.
                    protection = Protection.NORMAL;
                    invisibleUntilTick = 0;
                    immuneUntilTick = 0;
                }
                // A mark takes effect in the same step's view.
                policeSeesThief = protection != Protection.INVISIBLE && inBeam;
            }
        } else {
            beamProgress = 0;
        }
    }

    int protectionRemainingTicks() {
        if (protection == Protection.INVISIBLE) {
            return (int) Math.max(0L, invisibleUntilTick - tick);
        }
        if (protection == Protection.IMMUNE) {
            return (int) Math.max(0L, immuneUntilTick - tick);
        }
        return 0;
    }

    private void refreshVisibility() {
        thiefSeesPolice = computeThiefSeesPolice();
        policeSeesThief = protection != Protection.INVISIBLE && computePoliceBeam();
    }

    private boolean computePoliceBeam() {
        return WorldGeometry.inBeam(map, police.x, police.y, police.headingRad,
                thief.x, thief.y, rules.beamRange, RuleMath.halfBeamAngle(rules));
    }

    private boolean computeThiefSeesPolice() {
        return WorldGeometry.inViewRect(thief.x, thief.y, police.x, police.y,
                RuleMath.viewHalfWidth(rules), RuleMath.viewHalfHeight(rules));
    }

    /**
     * Consumes new drop ids. The return value is true when at least one new id was
     * consumed (that cancels interaction even with empty hands); at most one carried
     * material actually lands on the ground.
     */
    boolean applyDrop(InputMerger.PlayerStep step) {
        if (step.dropIds.isEmpty()) {
            return false;
        }
        long last = thief.lastDropId;
        boolean pulseConsumed = false;
        boolean dropped = false;
        for (Long dropId : step.dropIds) {
            if (dropId == null || dropId <= last) {
                continue;
            }
            last = dropId;
            pulseConsumed = true;
            if (!dropped && carrying != null) {
                carrying.status = MaterialState.Status.GROUND;
                carrying.x = thief.x;
                carrying.y = thief.y;
                carrying = null;
                dropped = true;
            }
        }
        if (last > thief.lastDropId) {
            thief.lastDropId = last;
        }
        if (pulseConsumed) {
            interaction.clearActive();
            interaction.requiresRelease = step.finalHeld;
        }
        return pulseConsumed;
    }

    /**
     * Applies one step of interaction logic. A tick moves one interaction step at most.
     * Movement attempt, mark, drop and invisibility cancel and require a fresh press.
     */
    void updateInteraction(InputMerger.PlayerStep step, boolean marked, boolean dropPulse) {
        boolean invisible = protection == Protection.INVISIBLE;
        boolean moved = step.anyNonZeroMove;
        boolean cancel = invisible || marked || dropPulse || moved;

        if (step.releaseEdge) {
            interaction.clearActive();
            interaction.requiresRelease = false;
        }

        if (interaction.active != null) {
            if (cancel) {
                interaction.clearActive();
                interaction.requiresRelease = step.finalHeld;
            } else if (!step.finalHeld) {
                interaction.clearActive();
                interaction.requiresRelease = false;
            } else if (!targetValid(interaction.active)) {
                interaction.clearActive();
                interaction.requiresRelease = true;
            } else {
                interaction.progressTicks++;
                if (interaction.progressTicks >= rules.interactTicks) {
                    InteractionTarget completed = interaction.active;
                    interaction.clearActive();
                    completeInteraction(completed);
                    interaction.requiresRelease = step.finalHeld;
                }
            }
        } else if (!cancel && step.pressEdge && step.finalHeld && !interaction.requiresRelease) {
            InteractionTarget candidate = selectCandidate();
            if (candidate != null) {
                interaction.active = candidate;
                interaction.progressTicks = 1;
                if (interaction.progressTicks >= rules.interactTicks) {
                    interaction.clearActive();
                    completeInteraction(candidate);
                    interaction.requiresRelease = step.finalHeld;
                }
            }
        } else if (cancel && step.finalHeld) {
            interaction.requiresRelease = true;
        }

        InteractionTarget candidate;
        if (invisible || interaction.requiresRelease || cancel) {
            candidate = null;
        } else if (interaction.active != null) {
            candidate = interaction.active;
        } else {
            candidate = selectCandidate();
        }
        interaction.candidate = candidate;
        if (invisible) {
            interaction.blockedReason = "INVISIBLE";
        } else if (interaction.requiresRelease) {
            interaction.blockedReason = "RELEASE_REQUIRED";
        } else if (moved) {
            interaction.blockedReason = "MOVING";
        } else if (candidate == null) {
            interaction.blockedReason = "NO_TARGET";
        } else {
            interaction.blockedReason = "NONE";
        }
    }

    private boolean targetValid(InteractionTarget target) {
        if (target == null || protection == Protection.INVISIBLE) {
            return false;
        }
        switch (target.type) {
            case PICKUP:
                MaterialState material = materials.get(target.targetId);
                return material != null && material.status == MaterialState.Status.GROUND
                        && inRange(material.x, material.y);
            case DELIVER:
                if (carrying == null || carrying.deliveryId == null
                        || !carrying.deliveryId.equals(target.targetId)) {
                    return false;
                }
                MapPoint delivery = deliveryPoints.get(target.targetId);
                return delivery != null && inRange(delivery.x, delivery.y);
            case ESCAPE:
                if (!exitsOpen) {
                    return false;
                }
                MapPoint exit = exitPoints.get(target.targetId);
                return exit != null && inRange(exit.x, exit.y);
            default:
                return false;
        }
    }

    private InteractionTarget selectCandidate() {
        if (protection == Protection.INVISIBLE) {
            return null;
        }
        if (carrying != null) {
            if (carrying.deliveryId != null) {
                InteractionTarget deliver = target(InteractionType.DELIVER, carrying.deliveryId);
                if (targetValid(deliver)) {
                    return deliver;
                }
            }
            return exitsOpen ? nearestExit() : null;
        }
        InteractionTarget pickup = nearestGroundMaterial();
        if (pickup != null) {
            return pickup;
        }
        return exitsOpen ? nearestExit() : null;
    }

    private InteractionTarget nearestGroundMaterial() {
        InteractionTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MaterialState material : materials.values()) {
            if (material.status != MaterialState.Status.GROUND) {
                continue;
            }
            InteractionTarget target = target(InteractionType.PICKUP, material.id);
            if (!targetValid(target)) {
                continue;
            }
            double distance = WorldGeometry.distance(thief.x, thief.y, material.x, material.y);
            if (best == null || distance < bestDistance - WorldGeometry.EPS
                    || (Math.abs(distance - bestDistance) <= WorldGeometry.EPS
                    && target.targetId.compareTo(best.targetId) < 0)) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private InteractionTarget nearestExit() {
        InteractionTarget best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MapPoint exit : exitPoints.values()) {
            InteractionTarget target = target(InteractionType.ESCAPE, exit.id);
            if (!targetValid(target)) {
                continue;
            }
            double distance = WorldGeometry.distance(thief.x, thief.y, exit.x, exit.y);
            if (best == null || distance < bestDistance - WorldGeometry.EPS
                    || (Math.abs(distance - bestDistance) <= WorldGeometry.EPS
                    && target.targetId.compareTo(best.targetId) < 0)) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean inRange(double x, double y) {
        return WorldGeometry.distance(thief.x, thief.y, x, y) <= rules.interactRange + WorldGeometry.EPS
                && !WorldGeometry.segmentBlocked(map, thief.x, thief.y, x, y);
    }

    private void completeInteraction(InteractionTarget target) {
        switch (target.type) {
            case PICKUP:
                MaterialState picked = materials.get(target.targetId);
                if (picked != null && picked.status == MaterialState.Status.GROUND) {
                    picked.status = MaterialState.Status.CARRIED;
                    picked.everPicked = true;
                    carrying = picked;
                }
                break;
            case DELIVER:
                if (carrying != null && carrying.deliveryId != null
                        && carrying.deliveryId.equals(target.targetId)) {
                    carrying.status = MaterialState.Status.DELIVERED;
                    delivered.add(carrying);
                    carrying = null;
                    exitsOpen = delivered.size() >= 3;
                }
                break;
            case ESCAPE:
                if (exitsOpen) {
                    escaped = true;
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown interaction type: " + target.type);
        }
    }

    private static InteractionTarget target(InteractionType type, String id) {
        return new InteractionTarget(type, id);
    }

    void resolveOutcome(List<Departure> departures) {
        if (result != null) {
            return;
        }
        if (tick >= rules.durationTicks) {
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
        // The final snapshot must not advertise an interaction that can no longer start.
        interaction.reset();
        result = new MatchResult(init.matchId + ":result", init.matchId, winner, reason,
                departedPlayerId, (int) tick, marks, delivered.size(), tick);
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
}
