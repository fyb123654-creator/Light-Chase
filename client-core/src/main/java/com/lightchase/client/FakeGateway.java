package com.lightchase.client;

import com.lightchase.contracts.*;
import com.lightchase.contracts.geometry.WorldGeometry;
import com.lightchase.data.DataFiles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * C's offline UI fixture.
 *
 * <p>It reproduces the room phase sequence (LOBBY → COUNTDOWN → RUNNING → FINISHED), the
 * {@link GatewayListener} callback order and a set of representative error codes, so the whole
 * client flow can be built and demonstrated without a server. It is deliberately NOT a rule
 * engine and NOT the real network: no collisions, no lighting, no win/lose computation.
 */
public final class FakeGateway implements ClientGateway {

    /**
     * Selectable fixtures. The first five replay transport/room failures; the last two replay a
     * complete scripted match so the UI's marking, protection, delivery and result paths can be
     * exercised without a server.
     */
    public enum Scenario { NORMAL, CONNECT_FAIL, ROOM_FULL, ROLE_TAKEN, INVALID_PHASE, POLICE_WIN, THIEF_WIN }

    private static final double DEMO_MATCH_SECONDS = 12.0;
    private static final double POLICE_CAPTURE_AT = 10.0;
    private static final double THIEF_ESCAPE_AT = 11.0;
    private static final double SPEED = 3.0;
    private static final String SESSION_ID = "offline-session";
    private static final String SELF_ID = "demo-self";
    private static final String OPPONENT_ID = "demo-opponent";
    private static final String MATCH_ID = "demo-match";
    private static final String ROOM_ID = "DEMO";

    private DataFiles data;
    private GatewayListener listener;
    private Scenario scenario = Scenario.NORMAL;

    private Role selfRole = Role.THIEF;
    private boolean connected;
    private boolean ready;
    private RoomPhase phase = RoomPhase.LOBBY;
    private int countdownMs;
    private double matchSeconds;
    private double headingRad;
    private InputFrame input = InputFrame.ZERO;

    private double x;
    private double y;
    private double opponentX;
    private double opponentY;
    private long tick;
    private long revision;
    private long requestSeq;
    private int marks;
    private int delivered;
    private MatchResult lastResult;

    // Scripted match state (see Scenario.POLICE_WIN / THIEF_WIN). This is a fixture script, not a
    // rule engine: it only decides which UI states to emit at which second of the demo.
    private String carrying;
    private Protection protection = Protection.NORMAL;
    private int protectionTicks;
    private InteractionTarget interactionTarget;
    private int interactionProgress;
    private boolean exitsOpen;

    public void configure(DataFiles data) { this.data = data; }

    public void useScenario(Scenario value) { scenario = value; }
    public Scenario scenario() { return scenario; }
    public RoomPhase phase() { return phase; }
    public int countdownMs() { return countdownMs; }
    public boolean ready() { return ready; }
    public Role selfRole() { return selfRole; }
    public MatchResult lastResult() { return lastResult; }
    public Protection protection() { return protection; }
    public InputFrame lastInput() { return input; }

    @Override public void connect(ConnectionOptions options, GatewayListener listener) {
        if (data == null) throw new IllegalStateException("Configure fixture data first");
        this.listener = listener;
        resetState();

        listener.onConnection(new ConnectionStatus(ConnectionStates.CONNECTING, "offline fixture"));
        if (scenario == Scenario.CONNECT_FAIL) {
            listener.onConnection(new ConnectionStatus(ConnectionStates.DISCONNECTED, "offline fixture: host unreachable"));
            listener.onError(new GatewayError("CONNECT_TIMEOUT", "offline fixture cannot reach the host", true, null));
            return;
        }

        listener.onSession(new SessionInfo(SESSION_ID, SELF_ID));
        connected = true;
        listener.onConnection(new ConnectionStatus(ConnectionStates.CONNECTED, "offline fixture"));

        if (scenario == Scenario.ROOM_FULL) {
            listener.onError(new GatewayError("ROOM_FULL", "offline fixture: the room already has two players", false, null));
            connected = false;
            return;
        }
        emitRoom();
    }

    @Override public void selectRole(Role role) {
        if (listener == null) return;
        String id = nextRequestId();
        listener.onControl(new ControlStatus(id, "SELECT_ROLE", ControlStatus.State.PENDING, null));
        if (!connected) { fail(id, "SELECT_ROLE", "NOT_CONNECTED"); return; }
        if (phase != RoomPhase.LOBBY && phase != RoomPhase.COUNTDOWN) { fail(id, "SELECT_ROLE", "INVALID_PHASE"); return; }
        if (scenario == Scenario.ROLE_TAKEN) { fail(id, "SELECT_ROLE", "ROLE_TAKEN"); return; }
        selfRole = role;
        cancelCountdown();
        ready = false;
        emitRoom();
        listener.onControl(new ControlStatus(id, "SELECT_ROLE", ControlStatus.State.SUCCEEDED, null));
    }

    @Override public void setReady(boolean value) {
        if (listener == null) return;
        String id = nextRequestId();
        listener.onControl(new ControlStatus(id, "SET_READY", ControlStatus.State.PENDING, null));
        if (!connected) { fail(id, "SET_READY", "NOT_CONNECTED"); return; }
        if (value) {
            if (phase != RoomPhase.LOBBY) { fail(id, "SET_READY", "INVALID_PHASE"); return; }
            if (scenario == Scenario.INVALID_PHASE) { fail(id, "SET_READY", "INVALID_PHASE"); return; }
            ready = true;
            phase = RoomPhase.COUNTDOWN;
            countdownMs = countdownTotalMs();
        } else {
            // Cancelling readiness must also cancel a running countdown.
            ready = false;
            cancelCountdown();
        }
        emitRoom();
        listener.onControl(new ControlStatus(id, "SET_READY", ControlStatus.State.SUCCEEDED, null));
    }

    private void cancelCountdown() {
        if (phase == RoomPhase.COUNTDOWN) {
            phase = RoomPhase.LOBBY;
            countdownMs = 0;
        }
    }

    @Override public void submitInput(InputFrame frame) {
        if (phase != RoomPhase.RUNNING) { input = InputFrame.ZERO; return; }
        input = frame == null ? InputFrame.ZERO : frame;
        if (input.moveX != 0 || input.moveY != 0) headingRad = Math.atan2(input.moveY, input.moveX);
    }

    @Override public void returnToLobby() {
        if (listener == null) return;
        String id = nextRequestId();
        listener.onControl(new ControlStatus(id, "RETURN_LOBBY", ControlStatus.State.PENDING, null));
        if (!connected) { fail(id, "RETURN_LOBBY", "NOT_CONNECTED"); return; }
        backToLobby();
        listener.onControl(new ControlStatus(id, "RETURN_LOBBY", ControlStatus.State.SUCCEEDED, null));
    }

    @Override public void leaveRoom() {
        if (listener == null) return;
        // Contract §12: LEAVE_ROOM during RUNNING turns into a Departure, so the match is settled
        // first and the membership is only dropped afterwards. Closing straight away would hide the
        // result the server is obliged to send.
        String id = nextRequestId();
        listener.onControl(new ControlStatus(id, "LEAVE_ROOM", ControlStatus.State.PENDING, null));
        if (phase == RoomPhase.RUNNING) finish(ResultReason.QUIT);
        listener.onControl(new ControlStatus(id, "LEAVE_ROOM", ControlStatus.State.SUCCEEDED, null));
        close();
    }

    @Override public void background() {
        // Contract §12.1: backgrounding is best effort - the request may end as CANCELLED, and the
        // server is not obliged to have settled anything.
        if (listener != null) {
            listener.onControl(new ControlStatus(nextRequestId(), "LEAVE_ROOM", ControlStatus.State.CANCELLED, null));
        }
        close();
    }

    @Override public void close() {
        connected = false;
        ready = false;
        phase = RoomPhase.LOBBY;
        input = InputFrame.ZERO;
        if (listener == null) return;
        // No control event here: close() also runs for dispose(), mode switches and error paths, and
        // inventing a LEAVE_ROOM ACK for those would put an action in the UI the player never took.
        listener.onSession(null);
        listener.onConnection(new ConnectionStatus(ConnectionStates.CLOSED, "offline fixture closed"));
        listener = null;
    }

    /** Called only by the demo renderer. Drives the countdown and the scripted demo match. */
    public void advance(float seconds) {
        if (listener == null || !connected) return;
        double elapsed = Math.max(0, seconds);

        if (phase == RoomPhase.COUNTDOWN) {
            countdownMs -= (int) Math.round(elapsed * 1000.0);
            if (countdownMs <= 0) startMatch();
            else emitRoom();
            return;
        }
        if (phase != RoomPhase.RUNNING) return;

        double dt = Math.min(elapsed, 0.1);
        matchSeconds += dt;
        tick = Math.round(matchSeconds * data.rules.tickRate);

        double length = Math.max(1.0, Math.hypot(input.moveX, input.moveY));
        x = clamp(x + input.moveX / length * SPEED * dt, 1.5, data.map.width - 1.5);
        y = clamp(y + input.moveY / length * SPEED * dt, 1.5, data.map.height - 1.5);

        double orbit = matchSeconds * 0.9;
        opponentX = data.map.width / 2.0 + Math.cos(orbit) * 3.0;
        opponentY = data.map.height / 2.0 + Math.sin(orbit) * 3.0;

        if (scenario == Scenario.POLICE_WIN) {
            if (applyPoliceWinScript()) return;
        } else if (scenario == Scenario.THIEF_WIN) {
            if (applyThiefWinScript()) return;
        }

        emitView();
        if (scenario == Scenario.NORMAL && matchSeconds >= DEMO_MATCH_SECONDS) finish(ResultReason.TIMEOUT);
    }

    /**
     * Fixture script for the police victory path: mark 1, invisible 1.5 s, immune 1 s, mark 2, then
     * mark 3 ends the match. Purely a timeline - it implements no rules, it just emits the states a
     * real server would produce so the UI can be exercised.
     *
     * @return true when the match has finished and the caller must stop.
     */
    private boolean applyPoliceWinScript() {
        double t = matchSeconds;
        if (t >= POLICE_CAPTURE_AT) {
            marks = 3;
            finish(ResultReason.CAPTURED);
            return true;
        }
        marks = t >= 6.0 ? 2 : t >= 2.0 ? 1 : 0;
        double since = t - (marks == 2 ? 6.0 : marks == 1 ? 2.0 : 0.0);
        protection = Protection.NORMAL;
        protectionTicks = 0;
        if (marks > 0) {
            double invisible = data.rules.invisibleTicks / (double) data.rules.tickRate;
            double immune = data.rules.immuneTicks / (double) data.rules.tickRate;
            if (since < invisible) {
                protection = Protection.INVISIBLE;
                protectionTicks = (int) Math.round((invisible - since) * data.rules.tickRate);
            } else if (since < invisible + immune) {
                protection = Protection.IMMUNE;
                protectionTicks = (int) Math.round((invisible + immune - since) * data.rules.tickRate);
            }
        }
        interactionTarget = null;
        interactionProgress = 0;
        return false;
    }

    /**
     * Fixture script for the thief victory path: pick up and deliver three materials (each with a
     * visible interaction progress), the exits open, then the thief escapes.
     *
     * @return true when the match has finished and the caller must stop.
     */
    private boolean applyThiefWinScript() {
        double t = matchSeconds;
        if (t >= THIEF_ESCAPE_AT) {
            finish(ResultReason.ESCAPED);
            return true;
        }
        delivered = t >= 9.0 ? 3 : t >= 7.0 ? 2 : t >= 4.0 ? 1 : 0;
        exitsOpen = delivered >= 3;
        carrying = null;
        interactionTarget = null;
        interactionProgress = 0;
        protection = Protection.NORMAL;
        protectionTicks = 0;

        if (t >= 2.0 && t < 3.0) {
            interactionTarget = new InteractionTarget(InteractionType.PICKUP, "M1");
            interactionProgress = progressWithin(t - 2.0);
        } else if (t >= 3.0 && t < 4.0) {
            carrying = "M1";
        } else if (t >= 4.0 && t < 5.0) {
            interactionTarget = new InteractionTarget(InteractionType.DELIVER, "D1");
            interactionProgress = progressWithin(t - 4.0);
        } else if (t >= 5.0 && t < 6.0) {
            carrying = "M2";
        } else if (t >= 6.0 && t < 7.0) {
            interactionTarget = new InteractionTarget(InteractionType.DELIVER, "D2");
            interactionProgress = progressWithin(t - 6.0);
        } else if (t >= 7.0 && t < 8.0) {
            carrying = "M3";
        } else if (t >= 8.0 && t < 9.0) {
            interactionTarget = new InteractionTarget(InteractionType.DELIVER, "D3");
            interactionProgress = progressWithin(t - 8.0);
        } else if (exitsOpen && t < 10.0) {
            interactionTarget = new InteractionTarget(InteractionType.ESCAPE, "E1");
            interactionProgress = progressWithin(t - 9.0);
        }
        return false;
    }

    private int progressWithin(double elapsedInPhase) {
        double seconds = data.rules.interactTicks / (double) data.rules.tickRate;
        if (seconds <= 0) return 0;
        return (int) Math.round(Math.min(1.0, elapsedInPhase / seconds) * data.rules.interactTicks);
    }

    private void startMatch() {
        SpawnPose spawn = data.map.spawns.get(selfRole);
        x = spawn.x;
        y = spawn.y;
        headingRad = 0;
        countdownMs = 0;
        matchSeconds = 0;
        tick = 0;
        marks = 0;
        delivered = 0;
        carrying = null;
        protection = Protection.NORMAL;
        protectionTicks = 0;
        interactionTarget = null;
        interactionProgress = 0;
        exitsOpen = false;
        phase = RoomPhase.RUNNING;
        emitRoom();
        emitView();
    }

    private void finish(ResultReason reason) {
        phase = RoomPhase.FINISHED;
        ready = false;
        input = InputFrame.ZERO;
        lastResult = new MatchResult(nextRequestId(), MATCH_ID, winnerFor(reason), reason, null,
                (int) Math.round(matchSeconds * data.rules.tickRate), marks, delivered, tick);
        emitRoom();
        listener.onResult(lastResult);
    }

    /**
     * Whose win a given ending implies (contract §8): a departure is the opponent's win whichever
     * role leaves, escape is the thief's, capture and timeout are the police's, and a match that
     * ended without a winner (both left, server abort) must carry {@code winner = null} rather than
     * handing the police a victory nobody earned.
     */
    Role winnerFor(ResultReason reason) {
        switch (reason) {
            case ESCAPED:
                return Role.THIEF;
            case BOTH_LEFT:
            case SERVER_ABORTED:
                return null;
            case QUIT:
            case BACKGROUND:
            case DISCONNECT:
                return selfRole == Role.POLICE ? Role.THIEF : Role.POLICE;
            default:
                return Role.POLICE;   // TIMEOUT, CAPTURED
        }
    }

    private void backToLobby() {
        phase = RoomPhase.LOBBY;
        ready = false;
        countdownMs = 0;
        matchSeconds = 0;
        tick = 0;
        input = InputFrame.ZERO;
        emitRoom();
    }

    private void emitRoom() {
        List<RoomPlayer> players = new ArrayList<>(2);
        players.add(new RoomPlayer(SELF_ID, selfRole, ready, ConnectionStates.CONNECTED, false));
        Role opponentRole = selfRole == Role.POLICE ? Role.THIEF : Role.POLICE;
        players.add(new RoomPlayer(OPPONENT_ID, opponentRole, ready, ConnectionStates.CONNECTED, false));

        String matchId = phase == RoomPhase.RUNNING || phase == RoomPhase.FINISHED ? MATCH_ID : null;
        listener.onRoom(new RoomState(ROOM_ID, ++revision, phase, data.map.mapId, data.mapHash,
                data.configHash, matchId, countdownMs, players));
    }

    private void emitView() {
        List<OriginStatus> origins = new ArrayList<>();
        List<GroundMaterial> ground = new ArrayList<>();
        List<String> deliveredIds = new ArrayList<>();
        int index = 0;
        for (MaterialPoint point : data.map.materials) {
            boolean alreadyDelivered = index < delivered;
            boolean held = point.id.equals(carrying);
            if (alreadyDelivered) deliveredIds.add(point.id);
            origins.add(new OriginStatus(point.id, alreadyDelivered || held));
            // Contract §7: carried and delivered materials are not part of groundMaterials.
            if (!alreadyDelivered && !held) ground.add(new GroundMaterial(point.id, point.x, point.y));
            index++;
        }
        ThiefPrivate thief = selfRole == Role.THIEF
                ? new ThiefPrivate(carrying, protection, protectionTicks, ground, deliveredIds, interactionView())
                : null;

        // The demo ends at DEMO_MATCH_SECONDS, so that is the clock the HUD must show. Reporting
        // the rule's durationTicks here would promise 180 s and then finish at 12.
        int remaining = (int) Math.max(0,
                Math.round((DEMO_MATCH_SECONDS - matchSeconds) * data.rules.tickRate));
        listener.onView(new PlayerView(MATCH_ID, tick, tick * 33L, 0, selfRole, remaining, marks, delivered,
                exitsOpen, new ActorPose(SELF_ID, x, y, headingRad), visibleOpponent(), origins, thief));
    }

    private InteractionView interactionView() {
        if (interactionTarget == null) return new InteractionView(null, null, 0, false, "NO_TARGET");
        return new InteractionView(interactionTarget, interactionTarget, interactionProgress, false, "NONE");
    }

    /**
     * The thief sees the police by the view rectangle only. The police view is modelled solely by
     * the POLICE_WIN script, and an invisible player is not visible to the other side at all
     * (contract §7).
     */
    private VisibleOpponent visibleOpponent() {
        if (selfRole == Role.THIEF) {
            double halfHeight = data.rules.viewHeight / 2.0;
            double halfWidth = halfHeight * data.rules.viewAspect;
            if (!WorldGeometry.inViewRect(x, y, opponentX, opponentY, halfWidth, halfHeight)) return null;
            return new VisibleOpponent(OPPONENT_ID, Role.POLICE, opponentX, opponentY, headingRad, false);
        }
        if (scenario != Scenario.POLICE_WIN || protection == Protection.INVISIBLE) return null;
        return new VisibleOpponent(OPPONENT_ID, Role.THIEF, opponentX, opponentY, headingRad, carrying != null);
    }

    private void fail(String requestId, String action, String errorCode) {
        listener.onControl(new ControlStatus(requestId, action, ControlStatus.State.FAILED, errorCode));
    }

    private String nextRequestId() { return "fake-" + (++requestSeq); }

    /** Countdown length comes from {@code RuleConfig}; it must not be duplicated as a literal. */
    private int countdownTotalMs() {
        if (data.rules.tickRate <= 0) return 0;
        return (int) Math.round(data.rules.countdownTicks * 1000.0 / data.rules.tickRate);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private void resetState() {
        connected = false;
        ready = false;
        phase = RoomPhase.LOBBY;
        countdownMs = 0;
        matchSeconds = 0;
        tick = 0;
        marks = 0;
        delivered = 0;
        headingRad = 0;
        input = InputFrame.ZERO;
        lastResult = null;
        // Match state too: a new connection must not inherit a previous match's carry, protection,
        // interaction or opened exits, even though startMatch() resets them as well.
        carrying = null;
        protection = Protection.NORMAL;
        protectionTicks = 0;
        interactionTarget = null;
        interactionProgress = 0;
        exitsOpen = false;
        x = data.map.width / 2.0;
        y = data.map.height / 2.0;
        opponentX = x + 2;
        opponentY = y;
    }
}
