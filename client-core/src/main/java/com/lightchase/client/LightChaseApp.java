package com.lightchase.client;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.lightchase.contracts.*;
import com.lightchase.data.DataFiles;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * C's client shell: connect form, lobby, countdown, match and result.
 *
 * <p>The visible page is a pure function of the gateway state ({@link RoomState#phase} plus the
 * connection status); the app keeps no second copy of the phase. Rendering consumes role-filtered
 * {@link PlayerView} data only — the authoritative match state is never available here, and a null
 * opponent clears every trace of the previous frame.
 */
public final class LightChaseApp extends ApplicationAdapter {

    private enum Page { CONNECT, LOBBY, COUNTDOWN, MATCH, RESULT }
    private enum Mode { OFFLINE, LAN }

    private static final long INTERP_WINDOW_MS = 100;

    /** Quit button on the match screen. Kept clear of the stick and the action buttons. */
    // Lobby geometry, shared by the shape pass, the text pass and the hit boxes.
    private static final float ROLE_CX = 250f;
    private static final float ROLE1_TOP = 234f;
    private static final float ROLE2_TOP = 397f;
    private static final float RULE1_TOP = 176f;
    private static final float RULE2_TOP = 528f;
    /** One gap used everywhere: above the boxes, between them, and below them. */
    private static final float GRID_GAP = 44f;
    private static final float RULE_H = 8f;
    private static final float CTA_TOP = 560f;
    private static final float CTA_W = 228f;
    private static final float CTA_H = 64f;
    private static final float ICON_DX = 232f;
    private static final float ICON_R = 30f;
    private static final float BOX_H = 76f;

    /** The scene curtain: mist grey instead of pure white, and never fully opaque. */
    private static final Color COVER = new Color(0.039f, 0.039f, 0.039f, 1f);   // #0A0A0A, the page background
    // Black must be fully opaque: at 0.92 the outgoing page still shows through and the swap
    // reads as a flicker.
    private static final float COVER_PEAK = 1f;

    /** The curtain sinks in this fast, holds, then takes COVER_OUT to clear. */
    private static final float COVER_IN = 0.26f;
    private static final float COVER_OUT = 1.14f;

    /** A press washes the slab white in this long, then falls back navy in the same time. */
    private static final float WASH_UP = 0.20f;
    private static final float WASH_DOWN = 0.20f;

    // Layout shared by the non-lobby pages.
    private static final float PAGE_KICKER = 136f;
    private static final float CONNECT_RULE1 = 176f;
    private static final float CONNECT_RULE2 = 470f;
    private static final float CONNECT_CHIP_TOP = 210f;
    private static final float CONNECT_ACTION_TOP = 336f;
    private static final float MATCH_RULE = 96f;
    private static final float RESULT_ACTION_TOP = 552f;
    private static final float BOX_RADIUS = 0.0f;

    private static final Color LOBBY_GREY = new Color(0.62f, 0.62f, 0.62f, 1f);
    private static final Color LOBBY_GREY_DIM = new Color(0.50f, 0.50f, 0.50f, 1f);
    private static final Color LOBBY_UNDER = new Color(0.30f, 0.30f, 0.30f, 1f);
    private static final Color LOBBY_INK = new Color(0.05f, 0.05f, 0.05f, 1f);
    private static final Color CAP_BLUE = new Color(0.56f, 0.78f, 0.92f, 1f);
    private static final Color CAP_DARK = new Color(0.22f, 0.40f, 0.52f, 1f);
    private static final Color BEAM_BLUE = new Color(0.72f, 0.88f, 1f, 1f);
    private static final Color GEM_BLUE = new Color(0.55f, 0.88f, 1f, 1f);
    /** The primary action: deep navy, same family as the POLICE chip colour. */
    private static final Color READY_BLUE = new Color(0.13f, 0.25f, 0.40f, 1f);

    /** Role names on the white chips: readable on white without glaring. */
    private static final Color ROLE_POLICE = new Color(0.20f, 0.42f, 0.62f, 1f);
    private static final Color ROLE_THIEF = new Color(0.68f, 0.23f, 0.23f, 1f);

    private static final float QUIT_X = 520f, QUIT_Y = 30f, QUIT_W = 240f, QUIT_H = 56f;

    private ClientGateway gateway;
    private final ClientGateway offlineGateway;
    private final java.util.function.Supplier<ClientGateway> lanGatewayFactory;
    private final LanPermission permission;
    private Mode mode = Mode.OFFLINE;
    private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    private final OrthographicCamera worldCamera = new OrthographicCamera();
    private final OrthographicCamera uiCamera = new OrthographicCamera();
    private final FitViewport world = new FitViewport(128f / 9f, 8f, worldCamera);
    // Extend keeps the UI at 720 units tall but widens it to the device aspect, so a
    // 20:9 phone fills its screen instead of leaving black bars left and right.
    private final ExtendViewport ui = new ExtendViewport(TouchController.UI_WIDTH, TouchController.UI_HEIGHT, uiCamera);
    private final TouchController touch = new TouchController();

    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private UiFonts fonts;
    private final UiRound uiRound = new UiRound();
    private BitmapFont font;
    /** Which control was touched last, and when, so the press is visible for a moment. */
    private String pressTarget = null;
    private long pressAtMs = 0L;

    // ---- lobby animation ----------------------------------------------------------------
    /** Idle clock for the wandering flashlight and the gem. */
    private float lobbyClock;
    private float hoverPolice, hoverThief, hoverReady, hoverLeave;
    private float selectPolice, selectThief;
    /** 0 = flashlight off, 1 = fully on. Turns off, aims, then comes back up. */
    private float beamOn;
    /** Idle sweep intensity, faded separately so leaving the role does not snap the light on. */
    private float idleOn;
    /** 0 = wandering, 1 = aimed at the text: driven by the switch sequence, not by the click. */
    private float turn;
    /** Seconds since POLICE was chosen; negative when it is not the current role. */
    private float toPoliceT = -1f;
    /** Seconds since POLICE was left; negative when it is the current role. */
    private float fromPoliceT = -1f;
    /** How far the idle beam has grown out of the lens, 0..1. */
    private float idleReach;
    /** LEAVE ROOM's rule animation: rises, holds, then falls back. */
    private float leaveHot;
    private float leaveT = -1f;
    private boolean leaveQueued;
    /** Which connection field was just tapped, and when: the chip flashes so the tap lands. */
    private int editedChip = -1;
    /** Which secondary action is mid-pulse, and how far along. */
    /** Set when the lobby must show "nothing chosen" until the player taps a role. */
    private boolean suppressRoomRole = true;
    /** 0..1: how far the READY button has faded in (it only exists once a role is picked). */
    private float readyReveal;
    /** Button wash states: 0 = CONNECT, 1 = BACK TO LOBBY. */
    private final float[] wash = new float[2];
    private final boolean[] washing = new boolean[2];
    /** Second half of a press: the slab is falling back from white to navy. */
    private final boolean[] returning = new boolean[2];
    private boolean readyReturning;
    /** The label the READY button was showing when it was pressed; it keeps it until the
     *  wash reaches its peak, so the text never changes mid-animation. */
    private String readyLabelAtPress = "READY";
    private int pulseId = -1;
    private float pulseT;
    private long editedAtMs;
    /** READY plays its press animation first and only then sends the request. */
    private boolean readyQueued;
    private float readyPress;   // 0..1 over the animation

    /** Screen transitions: fade to white, run the action, fade back from white. */
    private int fadePhase;      // 0 idle, 1 out, 2 hold, 3 in
    private float fadeT;
    /** Per-chip cross-fade: previous value, current value, and the blend between them. */
    private final String[] chipShown = new String[4];
    private final String[] chipPrev = new String[4];
    private final float[] chipFade = {1f, 1f, 1f, 1f};
    /** Pointer in UI space, refreshed by mouseMoved / touchDown. */
    private float pointerX = -1f, pointerY = -1f;
    private boolean pointerSeen;
    private DataFiles data;

    private SessionInfo session;
    private RoomState room;
    private PlayerView view;
    private MatchResult result;
    private String connectionState = ConnectionStates.DISCONNECTED;
    private String connectionDetail = "";
    private String notice = "";
    private String errorText = "";

    private String host = "10.0.2.2";
    private String port = "8080";
    private String roomId = "DEMO";
    private FakeGateway.Scenario scenario = FakeGateway.Scenario.NORMAL;
    private boolean dialogOpen;

    /** Null until the player (re)picks: the lobby always starts without a role. */
    private Role selectedRole;
    /** What is actually on screen. It lags behind {@link #page()} until the cover is white,
     *  so a scene can never appear before the transition that hides it. */
    private Page shownPage;
    private String lastViewMatchId;
    private long lastViewTick = Long.MIN_VALUE;
    private final ViewInterpolator interpolator = new ViewInterpolator(INTERP_WINDOW_MS);
    private final java.util.Set<String> pendingControls = new java.util.HashSet<>();
    private WorldRenderer worldRenderer;
    private final MatchQuit quit = new MatchQuit();

    public LightChaseApp(ClientGateway offlineGateway) {
        this(offlineGateway, null, null);
    }

    /**
     * @param lanGatewayFactory builds a fresh real gateway per connection; {@code null} means this
     *                          build can only run the offline fixture.
     * @param permission        platform gate for local-network access; {@code null} on non-Android.
     */
    public LightChaseApp(ClientGateway offlineGateway,
                         java.util.function.Supplier<ClientGateway> lanGatewayFactory,
                         LanPermission permission) {
        this.offlineGateway = offlineGateway;
        this.gateway = offlineGateway;
        this.lanGatewayFactory = lanGatewayFactory;
        this.permission = permission;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override public void create() {
        // Assets come from the Android asset folder (game-data/resources). Which map is loaded is
        // decided in one place, ClientAssets.MAP, so a switch to the formal map is all-or-nothing.
        data = DataFiles.read(Gdx.files.internal(ClientAssets.MAP).readBytes(),
                Gdx.files.internal(ClientAssets.RULES).readBytes());
        if (offlineGateway instanceof FakeGateway) ((FakeGateway) offlineGateway).configure(data);

        // Without blending every translucent shape renders opaque, which turned the flashlight
        // beam into a solid cream wedge instead of light.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        // Blackout layout needs a real size hierarchy; the stock 15 px bitmap font cannot give it.
        fonts = UiFonts.load();
        font = fonts.mono;
        uiRound.create();
        worldRenderer = new WorldRenderer(shapes, worldCamera, uiCamera, world, ui, data);

        Gdx.input.setInputProcessor(new InputAdapter() {

            @Override public boolean touchDown(int sx, int sy, int pointer, int button) {
                Vector2 p = unproject(sx, sy);
                if (p.x < 0 || p.x > ui.getWorldWidth() || p.y < 0 || p.y > TouchController.UI_HEIGHT) return false;
                if (page() != Page.MATCH) return tap(p, pointer);
                // A page-level button on the match screen (QUIT) must win over the touch controls,
                // but every other touch still belongs to the stick/hold/drop.
                if (tap(p, pointer)) return true;
                return touch.down(pointer, p.x, p.y, selectedRole == Role.THIEF);
            }
            @Override public boolean touchDragged(int sx, int sy, int pointer) {
                if (page() != Page.MATCH) return false;
                Vector2 p = unproject(sx, sy);
                return touch.drag(pointer, p.x, p.y);
            }
            @Override public boolean touchUp(int sx, int sy, int pointer, int button) {
                touch.up(pointer);
                return true;
            }
            @Override public boolean touchCancelled(int sx, int sy, int pointer, int button) {
                touch.cancel(pointer);
                return true;
            }
            @Override public boolean keyDown(int key) {
                if (key == Input.Keys.ESCAPE) { touch.clear(); gateway.returnToLobby(); return true; }
                return false;
            }
        });
    }

    @Override public void resize(int width, int height) {
        world.update(width, height);
        ui.update(width, height, true);
    }

    @Override public void pause() {
        touch.clear();
        gateway.background();
    }

    @Override public void resume() {
        if (data == null) return;
        callbacks.clear();
        resetSession();
        // The previous session is already closed by pause(); without this reset the next connect()
        // would be rejected locally as ALREADY_CONNECTED and the app would sit on a dead page.
        connectionState = ConnectionStates.DISCONNECTED;
        connectionDetail = "";
        // Coming back from the background always starts on a fresh menu: no half-chosen role, no
        // half-played READY button, exactly the state of a first launch.
        selectedRole = null;
        suppressRoomRole = true;
        readyReveal = 0f;
        readyPress = 0f;
        readyQueued = false;
        readyReturning = false;
        shownPage = null;
        fadePhase = 0;
        fadeT = 0f;
        connect();
    }

    @Override public void dispose() {
        touch.clear();
        gateway.close();
        if (shapes != null) shapes.dispose();
        if (batch != null) batch.dispose();
        uiRound.dispose();
        if (fonts != null) fonts.dispose();
        else if (font != null) font.dispose();
    }

    // ---------------------------------------------------------------- gateway

    private void connect() {
        if (ConnectionStates.isSessionAlive(connectionState)
                || ConnectionStates.CONNECTING.equals(connectionState)
                || ConnectionStates.HANDSHAKING.equals(connectionState)
                || ConnectionStates.JOINING.equals(connectionState)) {
            errorText = "ALREADY_CONNECTED - a session already exists";
            return;
        }
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.trim());
            if (portNumber < 1 || portNumber > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            errorText = "INVALID_ADDRESS - port must be 1-65535";
            notice = "";
            return;
        }
        if (roomId.trim().isEmpty()) {
            errorText = "INVALID_ADDRESS - room id must not be empty";
            notice = "";
            return;
        }
        // Pick the gateway for this connection. A LAN attempt always gets a fresh instance so a
        // previous socket, listener and request table cannot leak into the new session.
        ClientGateway next;
        if (mode == Mode.LAN) {
            if (lanGatewayFactory == null) {
                errorText = "LAN mode is not available in this build";
                return;
            }
            if (permission != null && !permission.granted()) {
                permission.request();
                errorText = "ACCESS_LOCAL_NETWORK " + permission.describe() + " - grant it, then connect again";
                return;
            }
            next = lanGatewayFactory.get();
            if (next == null) {
                // A factory that cannot build a gateway must not become a null transport.
                errorText = "LAN gateway unavailable";
                return;
            }
        } else {
            next = offlineGateway;
        }
        if (next != gateway) {
            // Two live transports would both push callbacks into the same queue; close the old one.
            try {
                gateway.close();
            } catch (RuntimeException ignored) {
                // A failing close must not block the new connection.
            }
            gateway = next;
        }
        if (gateway instanceof FakeGateway) {
            FakeGateway fixture = (FakeGateway) gateway;
            fixture.configure(data);
            fixture.useScenario(scenario);
        }

        errorText = "";
        notice = "Connecting to " + host + ":" + portNumber + " ...";
        connectionState = ConnectionStates.CONNECTING;
        connectionDetail = "";
        resetSession();

        gateway.connect(new ConnectionOptions(host.trim(), portNumber, roomId.trim(), "s0",
                data.mapHash, data.configHash), new GatewayListener() {
            public void onSession(SessionInfo s) { callbacks.add(() -> {
                session = s;
                // 02_接口契约.md §12.1: a null session must clear identity, the old room and every
                // cached match artifact, so nothing from the previous connection can be rendered.
                if (s == null) resetSession();
            }); }
            public void onControl(ControlStatus s) { callbacks.add(() -> onControlStatus(s)); }
            public void onConnection(ConnectionStatus s) { callbacks.add(() -> {
                connectionState = s.state;
                connectionDetail = s.reason == null ? "" : s.reason;
                if (!ConnectionStates.isSessionAlive(s.state)) { dropFrame(); touch.clear(); }
            }); }
            public void onRoom(RoomState s) { callbacks.add(() -> {
                room = s;
                if (s.phase == RoomPhase.LOBBY || s.phase == RoomPhase.COUNTDOWN) {
                    dropFrame();
                    touch.clear();
                }
                for (RoomPlayer p : s.players) {
                    if (!suppressRoomRole && session != null
                            && session.playerId.equals(p.playerId) && p.role != null) {
                        selectedRole = p.role;
                    }
                }
            }); }
            public void onView(PlayerView s) { callbacks.add(() -> {
                // §10: accept only a strictly newer tick; a new match restarts the counter and must
                // never be interpolated from the previous match (view itself is replaced right after,
                // so this is not a frame drop).
                if (s.matchId == null || !s.matchId.equals(lastViewMatchId)) {
                    lastViewMatchId = s.matchId;
                    lastViewTick = Long.MIN_VALUE;
                    interpolator.clear();
                }
                if (s.tick <= lastViewTick) return;
                lastViewTick = s.tick;
                interpolator.accept(s, System.nanoTime() / 1_000_000L);
                view = s;
            }); }
            public void onResult(MatchResult s) { callbacks.add(() -> {
                // A quit ends the session in the same frame: the server sends GAME_OVER and then
                // drops the connection, so onSession(null) clears `result` before anything is drawn.
                quit.onResult(s);
                result = s;
                dropFrame();
                touch.clear();
            }); }
            public void onError(GatewayError e) { callbacks.add(() -> {
                errorText = e.code + (e.message == null ? "" : " - " + e.message);
                notice = "";
                // The transport reports failures through onError only; it does not move
                // ConnectionStatus to a terminal state. Force it down here, otherwise the UI would
                // stay in CONNECTING and its own ALREADY_CONNECTED guard would block every retry.
                boolean transportFailed = !e.recoverable
                        || "CONNECTION_LOST".equals(e.code)
                        || "CONNECT_TIMEOUT".equals(e.code);
                if (transportFailed) {
                    try {
                        gateway.close();
                    } catch (RuntimeException ignored) {
                        // A failing close must not hide the original error.
                    }
                }
                if (!e.recoverable) {
                    // §9: fatal codes send the client back to the connect page.
                    resetSession();
                    connectionState = ConnectionStates.DISCONNECTED;
                }
            }); }
        });
    }

    private void onControlStatus(ControlStatus s) {
        // §12.1: PENDING/WAITING disable the action, any terminal state re-enables it.
        if (s.state == ControlStatus.State.PENDING || s.state == ControlStatus.State.WAITING) {
            pendingControls.add(s.action);
        } else {
            pendingControls.remove(s.action);
        }
        // The gateway drives the handshake itself; reporting HELLO/JOIN_ROOM here would spam the
        // notice line with steps the player never took.
        if ("HELLO".equals(s.action) || "JOIN_ROOM".equals(s.action) || "HEARTBEAT".equals(s.action)) return;
        quit.onControl(s.action, s.state);
        // Only failures are worth a line. A successful SELECT_ROLE is already visible as the
        // highlighted role row - printing "SELECT_ROLE: SUCCEEDED" under it says nothing.
        switch (s.state) {
            case SUCCEEDED:
                errorText = "";
                notice = "";
                break;
            case FAILED:
                errorText = "";
                notice = readableError(s.errorCode);
                break;
            case TIMED_OUT:
                errorText = "";
                notice = "THE SERVER DID NOT ANSWER";
                break;
            default:
                break;
        }
    }

    private boolean pending(String action) { return pendingControls.contains(action); }

    /** Server error codes are shouting identifiers; the notice line speaks in sentences. */
    private static String readableError(String code) {
        if (code == null || code.isEmpty()) return "THAT DID NOT WORK";
        switch (code) {
            case "ROLE_TAKEN": return "THAT ROLE IS TAKEN";
            case "ROOM_FULL": return "THE ROOM IS FULL";
            case "NOT_CONNECTED": return "NOT CONNECTED";
            case "INVALID_PHASE": return "NOT AVAILABLE RIGHT NOW";
            case "INVALID_STATE": return "NOT AVAILABLE RIGHT NOW";
            default: return code.replace('_', ' ');
        }
    }

    /**
     * Clears every per-connection artifact. Shared by disconnect, fatal error, transport switch and
     * resume so the four paths cannot drift apart.
     */
    private void resetSession() {
        session = null;
        room = null;
        dropFrame();
        result = null;
        touch.clear();
        pendingControls.clear();
        quit.reset();
        lastViewMatchId = null;
        lastViewTick = Long.MIN_VALUE;
    }

    /**
     * Drops the rendered frame. The interpolator must never outlive the view it belongs to, so the
     * two are always cleared together.
     */
    private void dropFrame() {
        view = null;
        interpolator.clear();
    }

    // ---------------------------------------------------------------- input

    private Vector2 unproject(int screenX, int screenY) {
        return ui.unproject(new Vector2(screenX, screenY));
    }

    private Page page() {
        if (!ConnectionStates.isSessionAlive(connectionState) || session == null) return Page.CONNECT;
        if (room == null) return Page.CONNECT;
        switch (room.phase) {
            case LOBBY: return Page.LOBBY;
            case COUNTDOWN: return Page.COUNTDOWN;
            case RUNNING: return Page.MATCH;
            case FINISHED: return Page.RESULT;
            default: return Page.CONNECT;
        }
    }

    private boolean tap(Vector2 p, int pointer) {
        if (fadePhase != 0) return true;   // the screen is covered
        Page page = shownPage == null ? page() : shownPage;

        if (page == Page.CONNECT) {
            float cw3 = connectColumn(ui.getWorldWidth());
            float chipY = UiTheme.yFromTop(CONNECT_CHIP_TOP, BOX_H);
            if (inside(p, UiTheme.MARGIN, chipY, cw3, BOX_H)) {
                editedChip = 0; editedAtMs = System.nanoTime() / 1_000_000L;
                edit("Server host", host, "10.0.2.2", v -> host = v); return true;
            }
            if (inside(p, UiTheme.MARGIN + cw3 + GRID_GAP, chipY, cw3, BOX_H)) {
                editedChip = 1; editedAtMs = System.nanoTime() / 1_000_000L;
                edit("Port", port, "8080", v -> port = v); return true;
            }
            if (inside(p, UiTheme.MARGIN + 2f * (cw3 + GRID_GAP), chipY, cw3, BOX_H)) {
                editedChip = 2; editedAtMs = System.nanoTime() / 1_000_000L;
                edit("Room id", roomId, "DEMO", v -> roomId = v); return true;
            }
            float actY = UiTheme.yFromTop(CONNECT_ACTION_TOP, CTA_H);
            if (inside(p, UiTheme.MARGIN, actY, CTA_W, CTA_H)) { startWash(0); return true; }
            if (inside(p, scenarioX(ui.getWorldWidth()) - 12f, actY, 260f, CTA_H)) {
                pulse(0);
                FakeGateway.Scenario[] all = FakeGateway.Scenario.values();
                scenario = all[(scenario.ordinal() + 1) % all.length];
                return true;
            }
            if (inside(p, modeX(ui.getWorldWidth()) - 12f, actY, 260f, CTA_H)) {
                pulse(1);
                mode = mode == Mode.OFFLINE ? Mode.LAN : Mode.OFFLINE;
                // Switching transport drops the current one: leaving a live LAN socket behind while
                // the UI shows OFFLINE would let two transports deliver callbacks.
                if (gateway != offlineGateway) {
                    try {
                        gateway.close();
                    } catch (RuntimeException ignored) {
                        // Closing must not block the mode switch.
                    }
                    gateway = offlineGateway;
                    resetSession();
                    connectionState = ConnectionStates.DISCONNECTED;
                    connectionDetail = "";
                    notice = "";
                }
                if (mode == Mode.LAN && permission != null && !permission.granted()) permission.request();
                return true;
            }
            return true;
        }

        if (page == Page.LOBBY) {
            // Boxes mirror the mockup: the whole role row is tappable, not just the name.
            if (inside(p, ROLE_CX - 200f, UiTheme.yFromTop(ROLE1_TOP - 8f, 108f), 420f, 108f)) {
                recordPress("role-police");
                suppressRoomRole = false;
                if (!pending("SELECT_ROLE")) gateway.selectRole(Role.POLICE);
                return true;
            }
            if (inside(p, ROLE_CX - 200f, UiTheme.yFromTop(ROLE2_TOP - 8f, 108f), 420f, 108f)) {
                recordPress("role-thief");
                suppressRoomRole = false;
                if (!pending("SELECT_ROLE")) gateway.selectRole(Role.THIEF);
                return true;
            }
            if (inside(p, UiTheme.MARGIN, UiTheme.yFromTop(CTA_TOP, CTA_H), CTA_W, CTA_H)) {
                recordPress("ready");
                // The click starts the animation; the request goes out when it lands, so the
                // button reacts before the network does.
                if (selectedRole == null || readyReveal < 0.5f) return true;
                if (!pending("SET_READY") && !readyQueued) {
                    readyLabelAtPress = isSelfReady() ? "CANCEL" : "READY";
                    readyQueued = true;
                    readyPress = 0f;
                }
                return true;
            }
            if (inside(p, leaveCentre(ui.getWorldWidth()) - UiDraw.width(fonts.mono, "LEAVE ROOM", 1.5f) / 2f - 24f, UiTheme.yFromTop(CTA_TOP, CTA_H), UiDraw.width(fonts.mono, "LEAVE ROOM", 1.5f) + 48f, CTA_H)) {
                recordPress("leave");
                // The animation runs first; the request goes out once the rule is at full size,
                // otherwise the screen changes before anything can be seen.
                if (!pending("LEAVE_ROOM") && !leaveQueued) {
                    leaveQueued = true;
                    leaveT = 0f;
                }
                return true;
            }
            return true;
        }

        if (page == Page.RESULT) {
            if (inside(p, UiTheme.MARGIN, UiTheme.yFromTop(RESULT_ACTION_TOP + bottomShift(), CTA_H),
                    300f, CTA_H)) {
                if (!pending("RETURN_LOBBY")) startWash(1);
                return true;
            }
            return true;
        }

        if (page == Page.MATCH) {
            if (inside(p, QUIT_X, QUIT_Y, QUIT_W, QUIT_H)) {
                requestQuit();
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Quitting mid-match is not a local disconnect: the contract turns LEAVE_ROOM in RUNNING into a
     * Departure that the server commits on the next step, then it sends GAME_OVER to both sides.
     * The connection therefore has to stay open until the result arrives.
     */
    private void requestQuit() {
        if (pending("LEAVE_ROOM")) return;
        if (!quit.request(System.nanoTime() / 1_000_000L)) return;
        touch.clear();
        notice = "Leaving the match - waiting for the server to settle it";
        gateway.leaveRoom();
    }

    private void edit(String title, String current, String hint, java.util.function.Consumer<String> apply) {
        if (dialogOpen) return;
        dialogOpen = true;
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override public void input(String text) { dialogOpen = false; apply.accept(text); }
            @Override public void canceled() { dialogOpen = false; }
        }, title, current, hint);
    }

    private boolean isSelfReady() {
        RoomPlayer self = selfPlayer();
        return self != null && self.ready;
    }

    private RoomPlayer selfPlayer() {
        if (room == null || session == null) return null;
        for (RoomPlayer p : room.players) if (session.playerId.equals(p.playerId)) return p;
        return null;
    }

    private RoomPlayer opponentPlayer() {
        if (room == null || session == null) return null;
        for (RoomPlayer p : room.players) if (!session.playerId.equals(p.playerId)) return p;
        return null;
    }

    // ---------------------------------------------------------------- render

    @Override public void render() {
        Runnable next;
        while ((next = callbacks.poll()) != null) next.run();

        if (quit.tick(System.nanoTime() / 1_000_000L)) {
            // The server never acknowledged the departure; keep the match usable rather than
            // leaving the button spinning forever.
            notice = "Quit was not acknowledged; the match is still running";
        }
        Page target = page();
        if (shownPage == null) {
            shownPage = target;                       // first frame: there is nothing to hide
        } else if (target != shownPage && fadePhase == 0 && !washing()) {
            // The room moved on. Turn the screen white *while the old page is still the one on
            // screen*; the new page is only adopted once the cover is opaque. Waiting for a press
            // animation first is what keeps "wash, then white" in that order.
            coverNow();
        }
        boolean inMatch = shownPage == Page.MATCH;
        gateway.submitInput(inMatch ? touch.frame() : InputFrame.ZERO);
        if (gateway instanceof FakeGateway) ((FakeGateway) gateway).advance(Gdx.graphics.getDeltaTime());

        Gdx.gl.glClearColor(UiTheme.BG.r, UiTheme.BG.g, UiTheme.BG.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        interpolator.sample(System.nanoTime() / 1_000_000L);
        if (inMatch) worldRenderer.renderWorld(view, interpolator);
        updateLobby(Gdx.graphics.getDeltaTime());
        drawUi(shownPage);
        if (inMatch) worldRenderer.renderMinimap(view, interpolator);
        drawText(shownPage);
    }

    private void drawUi(Page page) {
        ui.apply();
        shapes.setProjectionMatrix(uiCamera.combined);
        // ShapeRenderer does not turn blending on for itself; without this every translucent
        // beam renders as an opaque slab.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        switch (page) {
            case CONNECT: {
                float w = ui.getWorldWidth();
                pageRule(shapes, w, CONNECT_RULE1);
                pageRule(shapes, w, CONNECT_RULE2 + bottomShift());
                secondaryRule(shapes, scenarioX(w), actionTextTop(), scenarioLabel(), scenarioActive(),
                        pulseOf(0));
                secondaryRule(shapes, modeX(w), actionTextTop(), modeLabel(), mode == Mode.LAN, pulseOf(1));
                break;
            }
            case LOBBY: {
                float w = ui.getWorldWidth();
                float bs = bottomShift();
                // Rounded, pure white, eight units: a hairline disappears on a phone.
                lobbyCapsule(shapes, UiTheme.MARGIN, RULE1_TOP, w - 2 * UiTheme.MARGIN, RULE_H, Color.WHITE);
                lobbyCapsule(shapes, UiTheme.MARGIN, RULE2_TOP + bs, w - 2 * UiTheme.MARGIN, RULE_H, Color.WHITE);
                lobbyRoleMarker(shapes, ROLE1_TOP, "POLICE", selectPolice);
                lobbyRoleMarker(shapes, ROLE2_TOP, "THIEF", selectThief);
                lobbyPoliceIcon(shapes, ROLE_CX + ICON_DX, UiTheme.H - (ROLE1_TOP + 38f));
                lobbyThiefIcon(shapes, ROLE_CX + ICON_DX, UiTheme.H - (ROLE2_TOP + 38f), selectThief);
                // Five slots at rest, the full label when pressed; 1.5 units under the text.
                float leaveW = UiDraw.width(fonts.mono, "LEAVE ROOM", 1.5f);
                float unit = UiDraw.width(fonts.mono, "M", 1.5f);
                float hot = UiAnim.smooth(leaveHot);
                float ctop = CTA_TOP + bs + (CTA_H - UiDraw.capHeight(fonts.cta)) / 2f;
                float leaveTop = ctop + (UiDraw.capHeight(fonts.cta) - UiDraw.capHeight(fonts.mono)) / 2f;
                // Half of the gap beneath the POLICE name (name ink ends at +35, its marker at +46).
                float policeGap = 46f - 6f - UiDraw.capHeight(fonts.displayMid);
                float ruleTop = leaveTop + UiDraw.capHeight(fonts.mono) + policeGap / 2f * 1.3f;
                float ruleW = UiAnim.lerp(unit * 5f, leaveW, hot);
                scratchColor.set(LOBBY_UNDER);
                scratchColor.lerp(UiTheme.ACCENT, hot);
                lobbyCapsule(shapes, leaveCentre(w) - ruleW / 2f, ruleTop, ruleW,
                        5f + 4f * hot, scratchColor);
                break;
            }
            case COUNTDOWN: {
                float w = ui.getWorldWidth();
                pageRule(shapes, w, CONNECT_RULE1);
                // Three dots, one per second: the current beat is white, the rest wait their turn.
                int seconds = room == null ? 0 : (int) Math.ceil(room.countdownRemainingMs / 1000.0);
                int total = Math.max(1, (int) Math.round(countdownTotalMs() / 1000.0));
                int done = Math.max(0, total - seconds);
                for (int i = 0; i < total; i++) {
                    float cx = w / 2f + (i - (total - 1) / 2f) * 64f;
                    shapes.setColor(i < done ? Color.WHITE : new Color(0.24f, 0.24f, 0.24f, 1f));
                    shapes.circle(cx, UiTheme.H - 320f, 14f, 24);
                }
                break;
            }
            case MATCH: {
                float w = ui.getWorldWidth();
                pageRule(shapes, w, MATCH_RULE);
                // Stick: a thin ring plus a dot that follows the thumb.
                shapes.setColor(1f, 1f, 1f, 0.10f);
                shapes.circle(TouchController.JOY_X, TouchController.JOY_Y, TouchController.JOY_R, 48);
                shapes.setColor(1f, 1f, 1f, 0.22f);
                shapes.circle(TouchController.JOY_X, TouchController.JOY_Y, TouchController.JOY_R, 48);
                shapes.setColor(0.043f, 0.063f, 0.090f, 1f);
                shapes.circle(TouchController.JOY_X, TouchController.JOY_Y, TouchController.JOY_R - 2f, 48);
                shapes.setColor(Color.WHITE);
                shapes.circle(TouchController.JOY_X + (float) touch.moveX() * 55f,
                        TouchController.JOY_Y + (float) touch.moveY() * 55f, 26f, 32);
                if (selectedRole == Role.THIEF) {
                    ring(shapes, TouchController.HOLD_X, TouchController.HOLD_Y, TouchController.HOLD_R,
                            touch.interacting() ? 1f : 0.28f);
                    ring(shapes, TouchController.DROP_X, TouchController.DROP_Y, TouchController.DROP_R, 0.28f);
                    float progress = interactionProgress();
                    if (progress > 0) {
                        float bw = 180f;
                        lobbyCapsule(shapes, TouchController.HOLD_X - bw / 2f,
                                UiTheme.H - (TouchController.HOLD_Y + 96f), bw, 10f,
                                new Color(1f, 1f, 1f, 0.22f));
                        lobbyCapsule(shapes, TouchController.HOLD_X - bw / 2f,
                                UiTheme.H - (TouchController.HOLD_Y + 96f), bw * progress, 10f, Color.WHITE);
                    }
                }
                float quitTextTop = UiTheme.H - QUIT_Y - QUIT_H
                        + (QUIT_H - UiDraw.capHeight(fonts.mono)) / 2f;
                float quitLabelW = UiDraw.width(fonts.mono, "QUIT MATCH", 1.5f);
                scratchColor.set(quit.active() ? UiTheme.ACCENT : LOBBY_UNDER);
                float quitRuleW = quitLabelW * 0.42f;
                lobbyCapsule(shapes, QUIT_X + QUIT_W / 2f - quitRuleW / 2f,
                        quitTextTop + UiDraw.capHeight(fonts.mono) + 6f, quitRuleW, 5f, scratchColor);
                break;
            }
            case RESULT: {
                float w = ui.getWorldWidth();
                pageRule(shapes, w, CONNECT_RULE1);
                pageRule(shapes, w, CONNECT_RULE2 + bottomShift());
                break;
            }
            default:
                break;
        }
        shapes.end();
    }

    private float interactionProgress() {
        if (view == null || view.thief == null) return 0f;
        int total = data.rules.interactTicks;
        if (total <= 0) return 0f;
        return Math.min(1f, view.thief.interaction.progressTicks / (float) total);
    }

    /** Tick rate comes from {@code RuleConfig}; hardcoding it would be a second source of truth. */
    private double ticksPerSecond() { return data.rules.tickRate; }

    private float countdownTotalMs() {
        if (data.rules.tickRate <= 0) return 1f;
        return data.rules.countdownTicks * 1000f / data.rules.tickRate;
    }

    private void drawText(Page page) {
        batch.setProjectionMatrix(uiCamera.combined);
        batch.begin();

        switch (page) {
            case CONNECT: drawConnectText(); break;
            case LOBBY: drawLobbyText(); break;
            case COUNTDOWN: drawCountdownText(); break;
            case MATCH: drawMatchText(); break;
            case RESULT: drawResultText(); break;
            default: break;
        }

        if (!errorText.isEmpty()) {
            String line = "ERROR  " + errorText;
            float x = 30f;
            UiDraw.text(batch, fonts.mono, line, x, 720f - 60f - UiDraw.capHeight(fonts.mono),
                    UiTheme.ACCENT, 1.5f);
        } else if (!notice.isEmpty()) {
            UiDraw.text(batch, fonts.mono, notice, 30f, 720f - 60f - UiDraw.capHeight(fonts.mono),
                    UiTheme.TEXT_SOFT, 1.5f);
        }

        // The cover goes last, on top of everything including the notice line: nothing may show
        // through the white while a scene changes.
        float cover = fadeCover();
        if (cover > 0.001f) {
            scratchColor.set(COVER);
            scratchColor.a = cover;
            uiRound.rect(batch, scratchColor, 0f, 0f,
                    ui.getWorldWidth(), UiTheme.H);
        }
        batch.end();
    }

    private void drawConnectText() {
        float w = ui.getWorldWidth();
        float m = UiTheme.MARGIN;
        float bs = bottomShift();

        UiDraw.text(batch, fonts.display, "LIGHT \u00b7 CHASE", m, 64f, UiTheme.TEXT, 1.5f);
        UiDraw.text(batch, fonts.monoBig, "CONNECT", m + 2f, PAGE_KICKER, UiTheme.ACCENT, 2.5f);

        String modeLabel = "MODE  " + mode.name();
        UiDraw.text(batch, fonts.mono, modeLabel,
                w - m - UiDraw.width(fonts.mono, modeLabel, 1f),
                64f + (UiDraw.capHeight(fonts.display) - UiDraw.capHeight(fonts.mono)) / 2f,
                LOBBY_GREY, 1f);

        // Three equal columns: the gaps between them are the same as every other gap.
        float colW = connectColumn(w);
        long since = System.nanoTime() / 1_000_000L - editedAtMs;
        chip(m, CONNECT_CHIP_TOP, colW, BOX_H, "SERVER HOST", host, editedChip == 0 && since < 700);
        chip(m + colW + GRID_GAP, CONNECT_CHIP_TOP, colW, BOX_H, "PORT", port,
                editedChip == 1 && since < 700);
        chip(m + 2f * (colW + GRID_GAP), CONNECT_CHIP_TOP, colW, BOX_H, "ROOM", roomId,
                editedChip == 2 && since < 700);

        primaryButton(m, CONNECT_ACTION_TOP, CTA_W, CTA_H, "CONNECT", lanGatewayFactory != null,
                washOf(0));
        secondaryAction(scenarioX(w), actionTextTop(), scenarioLabel(), scenarioActive(), 0);
        secondaryAction(modeX(w), actionTextTop(), modeLabel(), mode == Mode.LAN, 1);

        kv(m, CONNECT_RULE2 + bs + 44f, "STATUS", connectionState, UiTheme.TEXT);
        if (!connectionDetail.isEmpty()) {
            UiDraw.text(batch, fonts.mono, connectionDetail, m + 260f, CONNECT_RULE2 + bs + 68f,
                    LOBBY_GREY, 1f);
        }
        if (mode == Mode.LAN) {
            boolean ok = permission == null || permission.granted();
            UiDraw.text(batch, fonts.mono,
                    "ACCESS_LOCAL_NETWORK  " + (permission == null ? "N/A" : permission.describe()),
                    m, CONNECT_RULE2 + bs + 112f, ok ? LOBBY_GREY : UiTheme.ACCENT, 1f);
        }
        UiDraw.text(batch, fonts.mono, "TAP A FIELD TO EDIT \u00b7 SCENARIO REPLAYS FIXTURE FAILURES",
                m, 652f + bs, LOBBY_GREY, 1f);
    }

    private void drawLobbyText() {
        float w = ui.getWorldWidth();
        float m = UiTheme.MARGIN;
        float bs = bottomShift();

        UiDraw.text(batch, fonts.display, "LIGHT \u00b7 CHASE", m, 64f, UiTheme.TEXT, 1.5f);
        UiDraw.text(batch, fonts.monoBig, "LOBBY", m + 2f, 136f, UiTheme.ACCENT, 2.5f);

        String modeLabel = "MODE  " + mode.name();
        float modeTop = 64f + (UiDraw.capHeight(fonts.display) - UiDraw.capHeight(fonts.mono)) / 2f;
        UiDraw.text(batch, fonts.mono, modeLabel,
                w - m - UiDraw.width(fonts.mono, modeLabel, 1f), modeTop, LOBBY_GREY, 1f);

        // ---- role cards ------------------------------------------------------------------
        // The lit field is stacked rectangles: the centre saturates, the rim fades into the black,
        // so the text sits in light rather than in a box.
        float lit = UiAnim.smooth(selectPolice);
        lobbyRoleCard(ROLE1_TOP, "POLICE", "flashlight / mark three times", selectPolice, beamOn);
        lobbyRoleCard(ROLE2_TOP, "THIEF", "three drops / then escape", selectThief, 0f);

        // ---- fact boxes ------------------------------------------------------------------
        RoomPlayer self = selfPlayer();
        RoomPlayer other = opponentPlayer();
        float x0 = w * 0.54f;
        float gap = GRID_GAP;
        float boxW = (w - m - x0 - gap) / 2f;
        lobbyBox(0, x0, ROLE1_TOP, boxW, "ROOM", room.roomId);
        lobbyBox(1, x0 + boxW + gap, ROLE1_TOP, boxW, "MAP", room.mapId);
        // Both chips follow the local choice: until the player picks, the room may still hold the
        // role from the previous match, and echoing it would look like the choice was kept.
        lobbyBox(2, x0, ROLE2_TOP, boxW, "YOU",
                selectedRole == null ? "-" : selectedRole.name());
        lobbyBox(3, x0 + boxW + gap, ROLE2_TOP, boxW, "OPPONENT",
                selectedRole == null || other == null || other.role == null ? "-" : other.role.name());

        // ---- primary action ---------------------------------------------------------------
        // The action only exists once a role has been chosen; before that it would be a button
        // that cannot do its job, so it is simply not on screen.
        boolean busy = pending("SET_READY");
        float reveal = UiAnim.smooth(readyReveal);
        float pressLit = UiAnim.smooth(readyPress);
        float bw = CTA_W;
        float bh = CTA_H;
        float bx = m;
        float btop = CTA_TOP + bs + (1f - reveal) * 10f;
        if (reveal > 0.01f) {
            scratchColor.set(busy ? UiTheme.LINE : READY_BLUE);
            scratchColor.lerp(Color.WHITE, pressLit);
            scratchColor.a = reveal;
            uiRound.fill(batch, scratchColor, bx, btop, bw, bh);

            boolean pressing = readyQueued || readyReturning;
            String cta;
            if (busy) {
                cta = "WAITING...";
            } else if (pressing) {
                cta = readyLabelAtPress;                 // no text change under the finger
            } else {
                cta = isSelfReady() ? "CANCEL" : "READY";
            }
            // The label stays white: as the slab whitens the two converge and the button washes
            // out into the incoming white screen rather than switching colour.
            scratchColor.set(busy ? LOBBY_GREY : Color.WHITE);
            scratchColor.a = reveal;
            fitText(fonts.cta, cta, bx, btop, bw, bh, scratchColor);
        }
        float ctop = CTA_TOP + bs + (CTA_H - UiDraw.capHeight(fonts.cta)) / 2f;

        // Same line as READY, centred under the OPPONENT chip.
        float leaveTop = ctop + (UiDraw.capHeight(fonts.cta) - UiDraw.capHeight(fonts.mono)) / 2f;
        float leaveW2 = UiDraw.width(fonts.mono, "LEAVE ROOM", 1.5f);
        scratchColor.set(LOBBY_GREY);
        scratchColor.lerp(Color.WHITE, UiAnim.smooth(leaveHot));
        UiDraw.text(batch, fonts.mono, "LEAVE ROOM", leaveCentre(w) - leaveW2 / 2f, leaveTop,
                scratchColor, 1.5f);

        // ---- footer ------------------------------------------------------------------------
        int ready = 0;
        int total = 0;
        for (RoomPlayer p : room.players) {
            total++;
            if (p.ready) ready++;
        }
        String status;
        Color statusColor = LOBBY_GREY;
        if (total <= 1) {
            status = "WAITING FOR A SECOND PLAYER";
        } else if (ready >= total) {
            status = "STARTING...";
            statusColor = UiTheme.ACCENT;
        } else if (ready == 0) {
            status = "0 / " + total + " READY \u00b7 BOTH MUST READY";
        } else {
            status = ready + " / " + total + " READY \u00b7 WAITING FOR OPPONENT";
        }
        UiDraw.text(batch, fonts.mono, status, m, 652f + bs, statusColor, 1f);

        if (!errorText.isEmpty()) {
            String line = "ERROR  " + errorText;
            UiDraw.text(batch, fonts.mono, line,
                    w - m - UiDraw.width(fonts.mono, line, 1f), 198f, UiTheme.ACCENT, 1f);
        }
    }

    private final Color scratchColor = new Color();

    /** 0 at rest, 1 at the bottom of the press; down fast, hold, then spring back. */
    private float pressCurve(float t) {
        if (t <= 0f) return 0f;
        if (t < 0.34f) return UiAnim.smooth(t / 0.34f);
        if (t < 0.62f) return 1f;
        return Usm(1f - (t - 0.62f) / 0.38f);
    }

    private float Usm(float t) {
        return UiAnim.smooth(t);
    }

    // ---- shared page pieces ----------------------------------------------------------------

    /** Full-width white rule with round caps. */
    private void pageRule(ShapeRenderer shapes, float w, float top) {
        lobbyCapsule(shapes, UiTheme.MARGIN, top, w - 2 * UiTheme.MARGIN, RULE_H, Color.WHITE);
    }

    /** The marker under a text-only action: a short capsule that brightens when it is live. */
    private void secondaryRule(ShapeRenderer shapes, float x, float textTop, String label,
                              boolean live, float pulse) {
        float textW = UiDraw.width(fonts.mono, label, 1.5f);
        float ruleW = UiAnim.lerp(textW * 0.5f, textW * 0.8f, pulse);
        float ruleTop = textTop + UiDraw.capHeight(fonts.mono) + 6f;
        scratchColor.set(live ? Color.WHITE : LOBBY_UNDER);
        scratchColor.lerp(UiTheme.ACCENT, pulse);
        lobbyCapsule(shapes, x + textW / 2f - ruleW / 2f, ruleTop, ruleW,
                5f + 3f * pulse, scratchColor);
    }

    /** 0..1 envelope of the tap pulse for a secondary action. */
    private float pulseOf(int id) {
        if (pulseId != id) return 0f;
        return (float) Math.sin(Math.PI * Math.min(1f, pulseT / 0.45f));
    }

    private void startWash(int id) {
        if (washing[id]) return;
        washing[id] = true;
        wash[id] = 0f;
    }

    /** 0..1 wash strength for a button; 1 means it has gone fully white. */
    private float washOf(int id) {
        return UiAnim.smooth(Math.min(1f, wash[id]));
    }

    private void pulse(int id) {
        pulseId = id;
        pulseT = 0f;
    }

    /** Top of a label that is vertically centred inside the primary button's band. */
    private float actionTextTop() {
        return CONNECT_ACTION_TOP + (CTA_H - UiDraw.capHeight(fonts.mono)) / 2f;
    }

    /** A ring, used for the hold/drop controls. */
    private void ring(ShapeRenderer shapes, float cx, float cy, float r, float alpha) {
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.circle(cx, cy, r, 48);
        shapes.setColor(0.043f, 0.063f, 0.090f, 1f);   // the floor's own colour
        shapes.circle(cx, cy, r - 3f, 48);
    }

    /** White rounded chip with a label over a value, both centred. */
    private void chip(float x, float top, float w, float h, String label, String value) {
        chip(x, top, w, h, label, value, false);
    }

    private void chip(float x, float top, float w, float h, String label, String value, boolean justTapped) {
        uiRound.fill(batch, Color.WHITE, x, top, w, h);
        if (justTapped) {
            // A short accent bar along the bottom edge: the tap is acknowledged even though the
            // actual editing happens in a system dialog.
            float unit = UiDraw.width(fonts.mono, "M", 0.4f);
            uiRound.fill(batch, UiTheme.ACCENT, x + w / 2f - unit * 2f, top + h - 5f, unit * 4f, 4f);
        }
        float cx = x + w / 2f;
        float hLabel = UiDraw.capHeight(fonts.mono);
        float hValue = UiDraw.capHeight(fonts.displaySmall);
        float pad = (h - hLabel - hValue) / 3f;
        centered(batch, fonts.mono, label, cx, top + pad, new Color(0.22f, 0.22f, 0.22f, 1f), 0.4f);
        centered(batch, fonts.displaySmall, UiDraw.fit(fonts.displaySmall, value, w - 32f, 0.5f),
                cx, top + pad * 2f + hLabel, UiTheme.ON_LIGHT, 0.5f);
    }

    /** Deep navy slab with a white label, the same primary as the lobby. */
    private void primaryButton(float x, float top, float w, float h, String label, boolean enabled) {
        primaryButton(x, top, w, h, label, enabled, 0f);
    }

    /** Deep navy slab that washes to white while it is being pressed, like the lobby's READY. */
    private void primaryButton(float x, float top, float w, float h, String label, boolean enabled,
                               float lit) {
        scratchColor.set(enabled ? READY_BLUE : UiTheme.LINE);
        scratchColor.lerp(Color.WHITE, lit);
        uiRound.fill(batch, scratchColor, x, top, w, h);
        float cw = UiDraw.width(fonts.cta, label, 1.5f);
        float top2 = top + (h - UiDraw.capHeight(fonts.cta)) / 2f;
        UiDraw.text(batch, fonts.cta, label, x + (w - cw) / 2f, top2,
                enabled ? Color.WHITE : LOBBY_GREY, 1.5f);
    }

    /**
     * Draws a label centred in a box, shrinking it when it would not fit. Labels are the one place
     * where a longer word must not spill outside its slab, and the boxes are fixed sizes.
     */
    private void fitText(BitmapFont font, String label, float x, float top, float w, float h,
                         Color color) {
        float avail = w - 28f;
        float full = UiDraw.width(font, label, 1.5f);
        float k = full > avail ? Math.max(0.60f, avail / full) : 1f;
        BitmapFont.BitmapFontData d = font.getData();
        float base = d.scaleX;
        if (k < 1f) d.setScale(base * k);
        float cap = UiDraw.capHeight(font) * k;
        float ty = top + (h - cap) / 2f;
        UiDraw.text(batch, font, label, x + (w - full * k) / 2f, ty, color, 1.5f);
        if (k < 1f) d.setScale(base);
    }

    /** Text-only action with a marker underneath. */
    private void secondaryAction(float x, float top, String label, boolean live, int pulseId) {
        float p = pulseOf(pulseId);
        scratchColor.set(live ? Color.WHITE : LOBBY_GREY);
        scratchColor.lerp(Color.WHITE, p);
        UiDraw.text(batch, fonts.mono, label, x, top, scratchColor, 1.5f);
    }

    /** Label above a large value, aligned left. */
    private void kv(float x, float top, String label, String value, Color valueColor) {
        UiDraw.text(batch, fonts.mono, label, x, top, LOBBY_GREY, 1.2f);
        UiDraw.text(batch, fonts.displaySmall, value, x, top + 24f, valueColor, 0.5f);
    }

    private String scenarioLabel() { return "SCENARIO  " + scenario; }

    private String modeLabel() { return "MODE  " + mode.name(); }

    private boolean scenarioActive() {
        return scenario != FakeGateway.Scenario.NORMAL;
    }

    private float connectColumn(float w) {
        return (w - 2 * UiTheme.MARGIN - 2 * GRID_GAP) / 3f;
    }

    private float scenarioX(float w) {
        return UiTheme.MARGIN + CTA_W + 72f;
    }

    private float modeX(float w) {
        return scenarioX(w) + 300f;
    }

    /** Horizontal centre of the OPPONENT chip; LEAVE ROOM is parked under it. */
    private float leaveCentre(float w) {
        float x0 = w * 0.54f;
        float boxW = (w - UiTheme.MARGIN - x0 - GRID_GAP) / 2f;
        return x0 + boxW + GRID_GAP + boxW / 2f;
    }

    private static Color blended(Color a, Color b, float t) {
        return new Color(a).lerp(b, UiAnim.clamp01(t));
    }

    /** Role name over its hint, both centred on the column; the marker under them is a shape. */
    /**
     * Role name over its hint, both centred. When the flashlight is on, each character brightens
     * once the beam has reached it, so the words light up from right to left as the light travels.
     */
    private void lobbyRoleCard(float top, String name, String hint, float selected, float lit) {
        float chosen = UiAnim.smooth(selected);
        // Unselected stays readable but clearly dimmer; selected goes all the way to white.
        scratchColor.set(0.50f, 0.50f, 0.50f, 1f);
        scratchColor.lerp(Color.WHITE, chosen);
        Color warm = Color.WHITE;
        centeredLit(batch, fonts.displayMid, name, ROLE_CX, top + 6f, scratchColor, warm, 1f, lit);
        scratchColor.set(0.42f, 0.42f, 0.42f, 1f);
        scratchColor.lerp(new Color(0.86f, 0.86f, 0.86f, 1f), chosen);
        centeredLit(batch, fonts.mono, hint, ROLE_CX, top + 62f, scratchColor, warm, 0.4f, lit);
    }

    /**
     * Draws a centred string character by character, mixing in the lit colour for the glyphs the
     * beam has already passed. {@code lit} is 0..1 of how far across the string the light reached.
     */
    private void centeredLit(Batch batch, BitmapFont font, String s, float cx, float top,
                             Color base, Color litColor, float tracking, float lit) {
        float width = UiDraw.width(font, s, tracking);
        float x = cx - width / 2f;
        float edge = width * UiAnim.clamp01(lit * 1.25f);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            float advance = advance(font, c, tracking);
            // how far this glyph sits along the string, measured from the right edge
            float fromRight = width - (x - (cx - width / 2f)) - advance / 2f;
            float t = UiAnim.clamp01((edge - fromRight) / 26f);
            scratchLit.set(base);
            if (lit > 0.001f) scratchLit.lerp(litColor, t);
            font.setColor(scratchLit);
            font.draw(batch, String.valueOf(c), x, UiTheme.H - top);
            x += advance;
        }
    }

    private float advance(BitmapFont font, char c, float tracking) {
        BitmapFont.Glyph g = font.getData().getGlyph(c);
        return (g != null ? g.xadvance : font.getSpaceXadvance()) + tracking;
    }

    private final Color scratchLit = new Color();

    /**
     * White chip with black text. When the value changes, the old string fades out while the new
     * one fades in, so a role swap reads as a change rather than a flicker.
     */
    private void lobbyBox(int index, float x, float top, float w, String label, String value) {
        uiRound.fill(batch, Color.WHITE, x, top, w, BOX_H);
        float cx = x + w / 2f;
        // Equal space above the label, between the two lines, and below the value.
        float hLabel = UiDraw.capHeight(fonts.mono);
        float hValue = UiDraw.capHeight(fonts.displaySmall);
        float pad = (BOX_H - hLabel - hValue) / 3f;
        centered(batch, fonts.mono, label, cx, top + pad, new Color(0.22f, 0.22f, 0.22f, 1f), 0.4f);

        if (value != null && !value.equals(chipShown[index])) {
            chipPrev[index] = chipShown[index];
            chipShown[index] = value;
            chipFade[index] = 0f;
        }
        float fade = UiAnim.clamp01(chipFade[index]);
        float valueTop = top + pad * 2f + hLabel;
        if (chipPrev[index] != null && fade < 0.999f) {
            centeredAlpha(batch, fonts.displaySmall, chipPrev[index], cx, valueTop,
                    roleColor(chipPrev[index]), 0.5f, 1f - fade, w - 32f);
        }
        centeredAlpha(batch, fonts.displaySmall, chipShown[index], cx, valueTop,
                roleColor(chipShown[index]), 0.5f, fade, w - 32f);
    }

    private static Color roleColor(String value) {
        if ("POLICE".equals(value)) return ROLE_POLICE;
        if ("THIEF".equals(value)) return ROLE_THIEF;
        return UiTheme.ON_LIGHT;
    }

    private static void centeredAlpha(Batch batch, BitmapFont font, String s, float cx, float top,
                                      Color color, float tracking, float alpha, float maxWidth) {
        if (s == null || alpha <= 0.01f) return;
        scratchAlpha.set(color);
        scratchAlpha.a = alpha;
        UiDraw.text(batch, font, UiDraw.fit(font, s, maxWidth, tracking),
                cx - UiDraw.width(font, UiDraw.fit(font, s, maxWidth, tracking), tracking) / 2f,
                top, scratchAlpha, tracking);
    }

    private static final Color scratchAlpha = new Color();

    /**
     * Like {@link #centered} but pulls 'A' closer after 'M'. Varela Round has no kerning pair there
     * and MAP read as "M AP" at this size.
     */
    private static void centeredKerned(Batch batch, BitmapFont font, String s, float cx, float top,
                                       Color color, float tracking) {
        float width = 0f;
        for (int i = 0; i < s.length(); i++) {
            width += advanceOf(font, s.charAt(i), tracking);
            if (i > 0 && s.charAt(i - 1) == 'M' && s.charAt(i) == 'A') width -= 3.0f;
        }
        float x = cx - width / 2f;
        font.setColor(color);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && s.charAt(i - 1) == 'M' && c == 'A') x -= 3.0f;
            font.setColor(color);
            font.draw(batch, String.valueOf(c), x, UiTheme.H - top);
            x += advanceOf(font, c, tracking);
        }
    }

    private static float advanceOf(BitmapFont font, char c, float tracking) {
        BitmapFont.Glyph g = font.getData().getGlyph(c);
        return (g != null ? g.xadvance : font.getSpaceXadvance()) + tracking;
    }

    private static void centered(Batch batch, BitmapFont font, String s, float cx, float top,
                                 Color color, float tracking) {
        UiDraw.text(batch, font, s, cx - UiDraw.width(font, s, tracking) / 2f, top, color, tracking);
    }

    /** Rounded marker under the role name: grey by default, red once chosen. */
    private void lobbyRoleMarker(ShapeRenderer shapes, float top, String name, float selected) {
        float nameW = UiDraw.width(fonts.displayMid, name, 1f);
        float grow = UiAnim.smooth(selected);
        float w = UiAnim.lerp(nameW * 0.5f, nameW, grow);
        float h = UiAnim.lerp(5f, 7f, grow);
        scratchColor.set(LOBBY_UNDER);
        scratchColor.lerp(UiTheme.ACCENT, grow);
        lobbyCapsule(shapes, ROLE_CX - w / 2f, top + 46f, w, h, scratchColor);
    }

    // ---- rounded geometry -------------------------------------------------------------------

    /** A rule with round caps: rectangle body plus a circle at each end. */
    private void lobbyCapsule(ShapeRenderer shapes, float x, float top, float w, float h, Color color) {
        float y = UiTheme.yFromTop(top, h);
        float r = h / 2f;
        shapes.setColor(color);
        shapes.rect(x + r, y, Math.max(0f, w - 2 * r), h);
        shapes.circle(x + r, y + r, r, 20);
        shapes.circle(x + w - r, y + r, r, 20);
    }

    // ---- characters -------------------------------------------------------------------------

    /**
     * Police cap from above with a flashlight in front.
     *
     * <p>Idle: the light sweeps slowly across the room. Chosen: it cuts out, the cap swings round,
     * and the beam grows back out towards the words. Brightness falls off with distance, so the
     * beam is strongest at the lens.
     */
    private void lobbyPoliceIcon(ShapeRenderer shapes, float cx, float cy) {
        float sel = UiAnim.smooth(selectPolice);
        // Unselected: the lamp is off and the cap simply faces the role name. Selected: the lamp
        // comes on and the head sweeps side to side, looking around.
        float sweep = UiAnim.sway(lobbyClock, 0.9f) * 0.20f * sel;
        float angle = (float) Math.PI + sweep;

        float lx = cx + (float) Math.cos(angle) * (ICON_R - 1f);
        float ly = cy + (float) Math.sin(angle) * (ICON_R - 1f);

        if (beamOn > 0.01f) {
            beamToText(shapes, lx, ly, angle, beamOn);
        }

        // Rim: the same diameter as the thief's, with a bite taken out where the flashlight is,
        // so the barrel can stick through without the two shapes cutting into each other.
        policeRim(shapes, cx, cy, ICON_R, angle, 0.30f);

        // barrel and lens, drawn after the rim so they sit on top of the gap
        float bx = cx + (float) Math.cos(angle) * 5f;
        float by = cy + (float) Math.sin(angle) * 5f;
        shapes.setColor(0.30f, 0.33f, 0.38f, 1f);
        shapes.rectLine(bx, by, lx, ly, 12f);
        shapes.setColor(0.64f, 0.67f, 0.72f, 1f);
        shapes.circle(lx, ly, 7.5f, 18);
        shapes.setColor(1f, 1f, 0.96f, 0.20f + 0.80f * beamOn);
        shapes.circle(lx, ly, 4.6f, 16);

        policeCap(shapes, cx, cy);
    }

    /**
     * White ring of the given radius with a gap centred on {@code gapAngle}. Drawn as overlapping
     * capsules rather than a full circle so the gap is a real bite, not an overlay.
     */
    private void policeRim(ShapeRenderer shapes, float cx, float cy, float r, float gapAngle,
                           float gap) {
        float thickness = 3.4f;
        int steps = 72;
        float from = gapAngle + gap;
        float to = gapAngle + (float) Math.PI * 2f - gap;
        float px = 0f;
        float py = 0f;
        shapes.setColor(1f, 1f, 1f, 1f);
        for (int i = 0; i <= steps; i++) {
            float a = from + (to - from) * i / steps;
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            if (i > 0) shapes.rectLine(px, py, x, y, thickness);
            px = x;
            py = y;
        }
    }

    /** The beam: bright at the lens, fading out along its length, tapering to a soft end. */
    private void beamToText(ShapeRenderer shapes, float lx, float ly, float angle, float on) {
        float full = 258f;
        float reach = full * on;
        float half = 0.30f;
        float nearAlpha = 0.90f * on;
        float farAlpha = 0.10f * on;

        float ex = lx + (float) Math.cos(angle) * reach;
        float ey = ly + (float) Math.sin(angle) * reach;
        float w = (float) Math.tan(half) * reach;

        // main body: strong at the lens, gone at the tip
        gradTri(shapes, lx, ly, nearAlpha,
                ex + (float) Math.cos(angle) * 30f, ey + (float) Math.sin(angle) * 30f + w * 0.55f, farAlpha,
                ex + (float) Math.cos(angle) * 30f, ey + (float) Math.sin(angle) * 30f - w * 0.55f, farAlpha);
    }

    /** Cap seen from above: crown, peak in front, badge on top. */
    private void policeCap(ShapeRenderer shapes, float cx, float cy) {
        final float r = ICON_R - 5f;          // leaves room for the rim outside it

        shapes.setColor(CAP_DARK.r, CAP_DARK.g, CAP_DARK.b, 0.20f);
        shapes.circle(cx, cy, r + 3f, 32);

        // peak, straight in front of the crown (left) and short, so it does not stick out below
        shapes.setColor(0.34f, 0.52f, 0.64f, 1f);
        shapes.rectLine(cx - r * 0.92f, cy + 2f, cx + 3f, cy + 2f, 10f);

        shapes.setColor(CAP_BLUE);
        shapes.circle(cx, cy + 1f, r, 32);
        shapes.setColor(0.44f, 0.62f, 0.73f, 1f);
        shapes.circle(cx, cy + 3f, r * 0.74f, 30);
        shapes.setColor(CAP_BLUE);
        shapes.circle(cx, cy + 1f, r * 0.56f, 28);

        shapes.setColor(0.92f, 0.97f, 1f, 1f);
        shapes.circle(cx, cy + 2f, 6f, 18);
        shapes.setColor(0.30f, 0.47f, 0.58f, 1f);
        shapes.circle(cx, cy + 2f, 3.3f, 14);
    }

    /** Triangle with a different alpha at each vertex: the whole gradient without stair steps. */
    private void gradTri(ShapeRenderer shapes, float x1, float y1, float a1,
                         float x2, float y2, float a2, float x3, float y3, float a3) {
        shapeNear.set(1f, 0.985f, 0.94f, a1);
        shapeMid.set(1f, 0.985f, 0.94f, a2);
        shapeFar.set(1f, 0.985f, 0.94f, a3);
        shapes.triangle(x1, y1, x2, y2, x3, y3, shapeNear, shapeMid, shapeFar);
    }

    private final Color shapeNear = new Color();
    private final Color shapeMid = new Color();
    private final Color shapeFar = new Color();

    /**
     * Ninja from above: hood, hair with a headband knotted at the side, scarf trailing behind, and
     * two hands. Choosing the thief plays the gem going into the bag; leaving puts it back.
     */
    private void lobbyThiefIcon(ShapeRenderer shapes, float cx, float cy, float selected) {
        float sel = UiAnim.smooth(selected);
        float bob = UiAnim.sway(lobbyClock, 2.1f) * 4f;
        float wave = UiAnim.sway(lobbyClock, 1.6f) * 8f;

        // scarf ribbons trailing behind
        shapes.setColor(0.58f, 0.16f, 0.18f, 1f);
        shapes.triangle(cx + 14f, cy + 10f, cx + 56f, cy + 26f + wave, cx + 54f, cy + 6f + wave);
        shapes.setColor(0.44f, 0.11f, 0.13f, 1f);
        shapes.triangle(cx + 14f, cy + 2f, cx + 52f, cy + 2f + wave, cx + 48f, cy - 12f + wave);

        // hood: white rim, dark cloth
        shapes.setColor(Color.WHITE);
        shapes.circle(cx, cy, ICON_R, 32);
        shapes.setColor(0.09f, 0.09f, 0.11f, 1f);
        shapes.circle(cx, cy, ICON_R - 2.2f, 32);
        shapes.setColor(0.17f, 0.17f, 0.21f, 1f);
        shapes.circle(cx - 4f, cy + 6f, ICON_R * 0.48f, 26);

        // hair across the face, and the headband tied at the side
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(cx - 13f, cy + 1f, 26f, 2.6f);
        shapes.rect(cx - 11f, cy - 4f, 22f, 2.0f);
        shapes.setColor(0.72f, 0.18f, 0.20f, 1f);
        shapes.rect(cx - 14f, cy + 8f, 28f, 3.2f);
        shapes.circle(cx + 15f, cy + 9.5f, 4.0f, 14);
        shapes.triangle(cx + 15f, cy + 9f, cx + 30f, cy + 15f + wave * 0.5f,
                cx + 29f, cy + 5f + wave * 0.5f);

        float hx = cx - 34f;
        float hy = cy - 30f + bob * 0.5f;

        // gem travels from the hands into the bag as the role is chosen
        float t = UiAnim.clamp01((sel - 0.15f) / 0.65f);
        float bagTop = hy + 16f;
        float gx = UiAnim.lerp(hx, hx, t);
        float gy = UiAnim.lerp(hy + 8f + bob, bagTop, t);

        shapes.setColor(0.93f, 0.93f, 0.96f, 1f - t);
        if (t < 0.99f) {
            shapes.circle(hx - 8f, hy, 5.5f, 16);
            shapes.circle(hx + 8f, hy, 5.5f, 16);
        }

        if (t > 0.01f) {
            // a single hand gripping a round sack of loot, not a rucksack
            float grow = UiAnim.clamp01(t * 1.3f);
            shapes.setColor(0.93f, 0.93f, 0.96f, 1f);
            shapes.circle(hx - 2f, hy + 2f, 5.5f, 16);
            shapes.setColor(0.36f, 0.37f, 0.42f, 1f);
            shapes.circle(hx, hy - 12f, 13f * grow, 22);
            shapes.setColor(0.22f, 0.22f, 0.26f, 1f);
            shapes.circle(hx - 4f, hy - 16f, 8f * grow, 18);
            shapes.setColor(0.62f, 0.62f, 0.68f, 1f);
            shapes.circle(hx, hy - 2f, 4.5f * grow, 14);
        }

        float gemAlpha = t < 0.85f ? 1f : 1f - (t - 0.85f) / 0.15f;
        if (gemAlpha > 0.01f) {
            float s2 = 10f;
            shapes.setColor(0.50f, 0.86f, 1f, gemAlpha);
            shapes.triangle(gx, gy + s2, gx - s2, gy, gx, gy - s2);
            shapes.triangle(gx, gy + s2, gx + s2, gy, gx, gy - s2);
            shapes.setColor(0.86f, 0.98f, 1f, gemAlpha * 0.85f);
            shapes.triangle(gx, gy + s2 * 0.55f, gx - s2 * 0.45f, gy, gx, gy - s2 * 0.55f);
            // highlight so it reads as a faceted stone rather than a flat diamond
            shapes.setColor(1f, 1f, 1f, gemAlpha);
            shapes.triangle(gx, gy + s2 * 0.72f, gx - s2 * 0.30f, gy + s2 * 0.10f,
                    gx + s2 * 0.10f, gy + s2 * 0.05f);
        }
    }

    // ---- press feedback -------------------------------------------------------------------

    /**
     * How much taller the world became because the device is narrower than 16:9. The top group
     * stays anchored to the top edge; everything below the middle rides this offset so the CTA
     * keeps the same distance from the bottom on a tablet.
     */
    private float bottomShift() {
        return ui.getWorldHeight() - TouchController.UI_HEIGHT;
    }

    /**
     * Covers the screen with white. Only page changes do this: a press is a personal action whose
     * result the player must be able to see, while the white screen hides a change of scene, which
     * in a real match is decided by the room (both players ready, both returned) and not by one
     * player's tap.
     */
    private void coverNow() {
        if (fadePhase != 0) return;
        fadePhase = 1;      // sink into the curtain first: the switch itself is a cut only in phase 2
        fadeT = 0f;
    }

    /** Swaps in the page the room has been asking for; called only while the screen is white. */
    private void adoptPage() {
        Page target = page();
        if (target == shownPage) return;
        // Coming back from a match: the role is not a property of the room any more than it is of
        // the player, so the lobby asks again, exactly like the first time.
        if (target == Page.LOBBY) {
            selectedRole = null;
            suppressRoomRole = true;
        }
        String summary = quit.takeSummary();
        notice = summary == null ? "" : summary;
        touch.clear();
        shownPage = target;
    }

    /**
     * True while a press is in flight, in either direction. The white cover waits for the whole
     * press - up and back down - so a scene change can never cut a button off mid-animation.
     */
    private boolean washing() {
        if (readyQueued || readyReturning) return true;
        for (int i = 0; i < washing.length; i++) {
            if (washing[i] || returning[i]) return true;
        }
        return false;
    }

    private void updateTransition(float dt) {
        switch (fadePhase) {
            case 1:
                fadeT += dt / COVER_IN;
                if (fadeT >= 1f) {
                    // Opaque now, so the swap is invisible - and the destination starts rendering
                    // behind the curtain while it holds, which is what lets its entrance settle.
                    adoptPage();
                    fadePhase = 2;
                    fadeT = 0f;
                }
                break;
            case 2:
                fadeT += dt / 0.26f;
                if (fadeT >= 1f) {
                    fadePhase = 3;
                    fadeT = 0f;
                }
                break;
            case 3:
                fadeT += dt / COVER_OUT;
                if (fadeT >= 1f) {
                    fadePhase = 0;
                    fadeT = 0f;
                }
                break;
            default:
                break;
        }
    }

    /** 0..1 strength of the curtain; it tops out below 1 so the switch never flashes white. */
    private float fadeCover() {
        switch (fadePhase) {
            case 1: return COVER_PEAK * UiAnim.smooth(fadeT);
            case 2: return COVER_PEAK;
            case 3: return COVER_PEAK * (1f - UiAnim.smooth(fadeT));
            default: return 0f;
        }
    }

    private void updateLobby(float dt) {

        updateTransition(dt);
        readyReveal = UiAnim.approach(readyReveal, selectedRole == null ? 0f : 1f, dt, 4.5f);
        for (int i = 0; i < wash.length; i++) {
            if (washing[i]) {
                wash[i] = Math.min(1f, wash[i] + dt / WASH_UP);
                if (wash[i] >= 1f) {
                    // Reaching white sends the request. The button then falls back to navy on its
                    // own; that return trip is the wash played backwards, and the room may answer
                    // and cover the screen while it runs.
                    washing[i] = false;
                    returning[i] = true;
                    if (i == 0) {
                        connect();
                    } else {
                        touch.clear();
                        gateway.returnToLobby();
                    }
                }
            } else if (returning[i]) {
                wash[i] = Math.max(0f, wash[i] - dt / WASH_DOWN);
                if (wash[i] <= 0f) {
                    wash[i] = 0f;
                    returning[i] = false;
                }
            }
        }
        if (pulseId >= 0) {
            pulseT += dt;
            if (pulseT > 0.50f) pulseId = -1;
        }
        lobbyClock += dt;
        // Touch only: there is no pointer to hover with on a phone, so the state machine is
        // driven entirely by what is selected.
        int hover = 0;
        selectPolice = UiAnim.approach(selectPolice, selectedRole == Role.POLICE ? 1f : 0f, dt, 5.5f);
        selectThief = UiAnim.approach(selectThief, selectedRole == Role.THIEF ? 1f : 0f, dt, 6f);

        // The lamp is simply on or off. The head keeps facing the role name either way; only the
        // sweep above needs a selection.
        if (selectedRole == Role.POLICE) {
            beamOn = UiAnim.approach(beamOn, 1f, dt, 3.2f);
        } else {
            beamOn = UiAnim.approach(beamOn, 0f, dt, 9f);
        }


        // LEAVE ROOM's rule: 0.14s up, 0.10s hold, 0.26s back down. One shot, no loop.
        if (leaveT >= 0f) {
            leaveT += dt;
            if (leaveT < 0.14f) {
                leaveHot = leaveT / 0.14f;
            } else if (leaveT < 0.30f) {
                leaveHot = 1f;
            } else if (leaveT < 0.62f) {
                leaveHot = 1f - (leaveT - 0.30f) / 0.32f;
            } else {
                leaveHot = 0f;
                leaveT = -1f;
            }
            if (leaveQueued && leaveT >= 0.34f) {
                leaveQueued = false;
                touch.clear();
                gateway.leaveRoom();
            }
        } else {
            leaveHot = 0f;
        }

        for (int i = 0; i < chipFade.length; i++) {
            chipFade[i] = Math.min(1f, chipFade[i] + dt * 3.4f);
        }

        if (readyQueued) {
            readyPress = Math.min(1f, readyPress + dt / WASH_UP);
            if (readyPress >= 1f) {
                readyQueued = false;
                readyReturning = true;
                if (!pending("SET_READY")) gateway.setReady(!isSelfReady());
            }
        } else if (readyReturning) {
            readyPress = Math.max(0f, readyPress - dt / WASH_DOWN);
            if (readyPress <= 0f) {
                readyPress = 0f;
                readyReturning = false;
            }
        } else {
            readyPress = UiAnim.approach(readyPress, 0f, dt, 6f);
        }
    }
    private void recordPress(String target) {
        pressTarget = target;
        pressAtMs = System.nanoTime() / 1_000_000L;
    }

    private boolean pressed(String target) {
        return target.equals(pressTarget) && System.nanoTime() / 1_000_000L - pressAtMs < UiTheme.PRESS_MS;
    }

    private boolean takenByOther(Role role) {
        RoomPlayer other = opponentPlayer();
        return other != null && other.role == role;
    }

    private String describe(RoomPlayer player) {
        String role = player.role == null ? "-" : player.role.name();
        String ready = player.ready ? "READY" : "not ready";
        String connection = ConnectionStates.CONNECTED.equals(player.connection) ? "" : "   [" + player.connection + "]";
        // The lobby column has ~300 px: the id is noise, role and state are the useful facts.
        return role + "  /  " + ready + connection;
    }

    private void drawCountdownText() {
        float w = ui.getWorldWidth();
        float m = UiTheme.MARGIN;
        float bs = bottomShift();
        int seconds = room == null ? 0 : (int) Math.ceil(room.countdownRemainingMs / 1000.0);

        UiDraw.text(batch, fonts.display, "LIGHT \u00b7 CHASE", m, 64f, UiTheme.TEXT, 1.5f);
        UiDraw.text(batch, fonts.monoBig, "STARTING", m + 2f, PAGE_KICKER, UiTheme.ACCENT, 2.5f);

        String roomLabel = "ROOM  " + (room == null ? "-" : room.roomId);
        UiDraw.text(batch, fonts.mono, roomLabel,
                w - m - UiDraw.width(fonts.mono, roomLabel, 1f),
                64f + (UiDraw.capHeight(fonts.display) - UiDraw.capHeight(fonts.mono)) / 2f,
                LOBBY_GREY, 1f);

        // The count itself, big enough to read from across the room, under the dots.
        String count = Integer.toString(Math.max(0, seconds));
        float cw = UiDraw.width(fonts.huge, count, 4f);
        UiDraw.text(batch, fonts.huge, count, w / 2f - cw / 2f, 392f, UiTheme.TEXT, 4f);

        String caption = "INPUT IS IGNORED UNTIL THE COUNTDOWN ENDS";
        UiDraw.text(batch, fonts.mono, caption,
                w / 2f - UiDraw.width(fonts.mono, caption, 1.5f) / 2f, 546f, LOBBY_GREY, 1.5f);
    }

    private void drawMatchText() {
        float w = ui.getWorldWidth();
        float m = UiTheme.MARGIN;
        String time = view == null ? "--" : String.format(Locale.ROOT, "%.1f", view.remainingTicks / ticksPerSecond());
        String marks = view == null ? "-" : Integer.toString(view.marks);
        String delivered = view == null ? "-" : Integer.toString(view.deliveredCount);
        String exits = view == null ? "-" : (view.exitsOpen ? "OPEN" : "CLOSED");

        // Header: four facts on the same baseline grid.
        float col = (w - 2 * m - 3 * GRID_GAP) / 4f;
        kv(m, 30f, "TIME", time, UiTheme.TEXT);
        kv(m + col + GRID_GAP, 30f, "MARKS", marks + "/" + data.rules.maxMarks, UiTheme.TEXT);
        kv(m + 2f * (col + GRID_GAP), 30f, "DELIVERED",
                delivered + "/" + data.map.materials.size(), UiTheme.TEXT);
        kv(m + 3f * (col + GRID_GAP), 30f, "EXITS", exits,
                UiTheme.TEXT);


        if (!ConnectionStates.CONNECTED.equals(connectionState)) {
            UiDraw.text(batch, fonts.mono,
                    "NET  " + connectionState + (connectionDetail.isEmpty() ? "" : "  " + connectionDetail),
                    m, 108f, UiTheme.ACCENT, 1f);
        }

        ThiefPrivate thief = view == null ? null : view.thief;
        float infoTop = 118f;
        if (thief != null) {
            String carried = thief.carriedMaterialId == null ? "none" : thief.carriedMaterialId;
            String protection = thief.protection + (thief.protectionRemainingTicks > 0
                    ? String.format(Locale.ROOT, " %.1fs", thief.protectionRemainingTicks / ticksPerSecond()) : "");
            line("CARRIED", carried, m, infoTop, UiTheme.TEXT);
            line("PROTECTION", protection, m + 300f, infoTop, UiTheme.TEXT);


            InteractionTarget chosen = thief.interaction.active != null
                    ? thief.interaction.active : thief.interaction.candidate;
            String target = chosen == null ? "none" : chosen.type + " " + chosen.targetId;
            line("TARGET", target, m, infoTop + 44f, UiTheme.TEXT);
            line("PROGRESS", Math.round(interactionProgress() * 100f) + "%",
                    m + 300f, infoTop + 44f, UiTheme.TEXT);
            line("BLOCKED", thief.interaction.blockedReason, m + 470f, infoTop + 44f, LOBBY_GREY);

            StringBuilder taken = new StringBuilder();
            for (OriginStatus status : view.originStatus) {
                if (status.everPicked) {
                    if (taken.length() > 0) taken.append("  ");
                    taken.append(status.materialId);
                }
            }
            line("ORIGINS TAKEN", taken.length() == 0 ? "none" : taken.toString(), m, infoTop + 88f,
                    LOBBY_GREY);
        }

        // Controls: the ring shapes are in the shape pass, these are just their captions.
        centered(batch, fonts.mono, "MOVE", TouchController.JOY_X,
                UiTheme.H - TouchController.JOY_Y + TouchController.JOY_R + 14f, LOBBY_GREY, 1.5f);
        if (selectedRole == Role.THIEF) {
            centered(batch, fonts.mono, "HOLD", TouchController.HOLD_X,
                    UiTheme.H - TouchController.HOLD_Y + TouchController.HOLD_R + 14f, LOBBY_GREY, 1.5f);
            centered(batch, fonts.mono, "DROP", TouchController.DROP_X,
                    UiTheme.H - TouchController.DROP_Y + TouchController.DROP_R + 14f, LOBBY_GREY, 1.5f);
        }
        if (view != null) {
            UiDraw.text(batch, fonts.mono,
                    String.format(Locale.ROOT, "POS %.2f, %.2f", view.self.x, view.self.y),
                    w / 2f - 60f, UiTheme.H - 40f, LOBBY_GREY, 1f);
        }

        // Quit: the same text-only action as the lobby's secondary button.
        float quitTop = UiTheme.H - QUIT_Y - QUIT_H + (QUIT_H - UiDraw.capHeight(fonts.mono)) / 2f;
        String quitLabel = quit.active() ? "LEAVING..." : "QUIT MATCH";
        float quitW = UiDraw.width(fonts.mono, quitLabel, 1.5f);
        UiDraw.text(batch, fonts.mono, quitLabel, QUIT_X + (QUIT_W - quitW) / 2f, quitTop,
                quit.active() ? UiTheme.ACCENT : LOBBY_GREY, 1.5f);
    }

    /** One grey label followed by a white value on the same line; the gap follows the label. */
    private void line(String label, String value, float x, float top, Color valueColor) {
        UiDraw.text(batch, fonts.mono, label, x, top, LOBBY_GREY, 1.2f);
        UiDraw.text(batch, fonts.mono, value, x + UiDraw.width(fonts.mono, label, 1.2f) + 22f, top,
                valueColor, 0.6f);
    }

    private void drawResultText() {
        float w = ui.getWorldWidth();
        float m = UiTheme.MARGIN;
        float bs = bottomShift();

        UiDraw.text(batch, fonts.display, "LIGHT \u00b7 CHASE", m, 64f, UiTheme.TEXT, 1.5f);
        UiDraw.text(batch, fonts.monoBig, "RESULT", m + 2f, PAGE_KICKER, UiTheme.ACCENT, 2.5f);

        if (result == null) {
            UiDraw.text(batch, fonts.mono, "WAITING FOR THE RESULT MESSAGE", m, 220f, LOBBY_GREY, 1.5f);
            return;
        }

        boolean noWinner = result.winner == null;
        boolean iWon = !noWinner && result.winner == selectedRole;

        // The outcome, at masthead size: this is the one line that must be read first.
        String headline = noWinner ? "DRAW" : result.winner.name() + " WINS";
        UiDraw.text(batch, fonts.display, headline, m, 210f,
                noWinner ? LOBBY_GREY : UiTheme.TEXT, 1.5f);
        UiDraw.text(batch, fonts.monoBig, noWinner ? "NO DECISION" : (iWon ? "YOU WIN" : "YOU LOSE"),
                m + 2f, 262f, noWinner ? LOBBY_GREY : UiTheme.ACCENT, 2.5f);

        // Who was who, on the chip grid.
        float colW = (w - 2 * m - GRID_GAP) / 2f;
        chip(m, 300f, w - 2 * m, BOX_H, "WINNER",
                result.winner == null ? "NONE" : result.winner.name());

        // Facts, evenly spaced.
        float col4 = (w - 2 * m - 3 * GRID_GAP) / 4f;
        kv(m, 402f, "REASON", String.valueOf(result.reason), UiTheme.TEXT);
        kv(m + col4 + GRID_GAP, 402f, "MARKS", result.marks + "/" + data.rules.maxMarks, UiTheme.TEXT);
        kv(m + 2f * (col4 + GRID_GAP), 402f, "DELIVERED",
                result.deliveredCount + "/" + data.map.materials.size(), UiTheme.TEXT);
        kv(m + 3f * (col4 + GRID_GAP), 402f, "ELAPSED",
                String.format(Locale.ROOT, "%.1fs", result.elapsedTicks / ticksPerSecond()),
                UiTheme.TEXT);

        primaryButton(m, RESULT_ACTION_TOP + bs, 300f, CTA_H, "BACK TO LOBBY",
                !pending("RETURN_LOBBY"), washOf(1));

        UiDraw.text(batch, fonts.mono,
                "RESULT " + result.resultId + "   MATCH " + result.matchId,
                m, 652f + bs, LOBBY_GREY, 1f);
    }

    // ---------------------------------------------------------------- helpers

    private void field(float x, float y, float w, float h) {
        shapes.setColor(0.10f, 0.15f, 0.22f, 1f);
        shapes.rect(x, y, w, h);
    }

    private void slot(float x, float y, float w, float h, boolean highlight) {
        shapes.setColor(highlight ? new Color(0.13f, 0.22f, 0.30f, 1f) : new Color(0.09f, 0.14f, 0.20f, 1f));
        shapes.rect(x, y, w, h);
    }

    private void button(float x, float y, float w, float h, boolean highlight) {
        button(x, y, w, h, highlight, true);
    }

    private void button(float x, float y, float w, float h, boolean highlight, boolean enabled) {
        Color color;
        if (!enabled) color = new Color(0.07f, 0.11f, 0.16f, 1f);
        else if (highlight) color = new Color(0.14f, 0.40f, 0.47f, 1f);
        else color = new Color(0.10f, 0.18f, 0.26f, 1f);
        shapes.setColor(color);
        shapes.rect(x, y, w, h);
    }

    private static boolean inside(Vector2 p, float x, float y, float w, float h) {
        return p.x >= x && p.x <= x + w && p.y >= y && p.y <= y + h;
    }
}
