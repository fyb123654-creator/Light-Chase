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
    /** Centred in the band between the chip row and the second rule. */
    private static final float CONNECT_ACTION_TOP = 346f;

    // The inline editor's box, shared by drawing and hit testing so they cannot drift.
    // The on-screen keyboard eats the bottom half of a landscape phone, so the whole modal has
    // to live in the top band: title, field and buttons stacked in ~165 units.
    private static final float EDITOR_W = 780f;
    private static final float EDITOR_H = 164f;
    private static final float EDITOR_TOP = 8f;
    private static final float EDITOR_BTN_W = 150f;
    private static final float EDITOR_BTN_H = 44f;
    private static final float EDITOR_BTN_TOP = 108f;
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

    private static final float QUIT_W = 240f, QUIT_H = 56f;
    private static final float QUIT_TOP = 40f;

    /** QUIT sits in the top-right corner, flush with the right margin. */
    private float quitX(float w) {
        return w - UiTheme.MARGIN - QUIT_W;
    }

    private float quitY() {
        return UiTheme.H - QUIT_TOP - QUIT_H;
    }

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
    /** Shared text primitives; also what the extracted pages draw with. */
    private Widgets widgets;
    private PageContext pageContext;
    private BitmapFont font;
    /** Which control was touched last, and when, so the press is visible for a moment. */
    private String pressTarget = null;
    private long pressAtMs = 0L;

    // ---- lobby animation ----------------------------------------------------------------
    /** Idle clock for the wandering flashlight and the gem. */
    private float hoverPolice, hoverThief, hoverReady, hoverLeave;
    /** 0 = flashlight off, 1 = fully on. Turns off, aims, then comes back up. */
    /** Idle sweep intensity, faded separately so leaving the role does not snap the light on. */
    private float idleOn;
    /** 0 = wandering, 1 = aimed at the text: driven by the switch sequence, not by the click. */
    /** Seconds since POLICE was chosen; negative when it is not the current role. */
    private float toPoliceT = -1f;
    /** Seconds since POLICE was left; negative when it is the current role. */
    private float fromPoliceT = -1f;
    /** How far the idle beam has grown out of the lens, 0..1. */
    private float idleReach;
    /** LEAVE ROOM's rule animation: rises, holds, then falls back. */
    /** Which connection field was just tapped, and when: the chip flashes so the tap lands. */
    private int editedChip = -1;
    /** Which secondary action is mid-pulse, and how far along. */
    /** Set when the lobby must show "nothing chosen" until the player taps a role. */
    /** 0..1: how far the READY button has faded in (it only exists once a role is picked). */
    /** Button wash states: 0 = CONNECT, 1 = BACK TO LOBBY. */
    /** 0 CONNECT, 1 BACK TO LOBBY, 2 SCENARIO, 3 MODE, 4..6 fields, 7 OK, 8 CANCEL, 9 QUIT. */
    private final float[] wash = new float[10];
    private final boolean[] washing = new boolean[10];
    /** Second half of a press: the slab is falling back from white to navy. */
    private final boolean[] returning = new boolean[10];
    /** The label the READY button was showing when it was pressed; it keeps it until the
     *  wash reaches its peak, so the text never changes mid-animation. */
    /** The label that replaces it once the slab is white - hidden by the wash. */
    private int pulseId = -1;
    private float pulseT;
    private long editedAtMs;
    /** READY plays its press animation first and only then sends the request. */

    /** Screen transitions: fade to white, run the action, fade back from white. */
    private int fadePhase;      // 0 idle, 1 out, 2 hold, 3 in
    /** Per-transition curtain timings: the countdown page is short, so it gets a fast cut. */
    private float phaseIn = 0.26f;
    private float phaseHold = 0.26f;
    private float phaseOut = 1.14f;
    private float fadeT;
    /** Per-chip cross-fade: previous value, current value, and the blend between them. */
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

    // What is actually on screen for the two message lines. A change fades the old text out
    // first and only then fades the new one in, so a message never pops.
    private String noteShown = "";

    /** A rejected role pick, shown beside the role it belongs to instead of the footer. */
    /** SCENARIO only exists in OFFLINE mode; it fades with the mode instead of popping. */
    private float scenarioFade = 1f;
    private long actionDownMs;
    /** Alpha of the green fill: it fades out as a whole instead of rewinding. */
    private float fillAlpha = 1f;
    /** 0..1 icon strength: the glyph fades with the button, never pops in. */
    /** 0..1 weight of the carrying offset on the gem's height: eased, never switched. */
    /** Seconds into the success tick, or negative when it is not playing. */

    /** The countdown kicker fades in with the page instead of appearing at full strength. */
    private float countdownReveal;
    private Role rolePickPending;
    private String errorText = "";

    private String host = "10.0.2.2";
    private String port = "8080";
    private String roomId = "DEMO";
    private FakeGateway.Scenario scenario = FakeGateway.Scenario.NORMAL;
    /** Which field the inline editor is editing, or -1 when it is closed. */
    /**
     * Set by the Android backend. libGDX keeps a hidden EditText focused after the keyboard is
     * dismissed, and that focus makes Android raise the keyboard again on the next tap anywhere.
     * The backend clears that focus for us here.
     */
    public static Runnable hideKeyboardHook;

    /** Field whose editor opens once its press animation has finished. */
    private int pendingEditor = -1;
    private int editingField = -1;
    private final StringBuilder editText = new StringBuilder();

    /** Null until the player (re)picks: the lobby always starts without a role. */
    /** What is actually on screen. It lags behind {@link #page()} until the cover is white,
     *  so a scene can never appear before the transition that hides it. */
    private Page shownPage;
    private String lastViewMatchId;
    private long lastViewTick = Long.MIN_VALUE;
    private final ViewInterpolator interpolator = new ViewInterpolator(INTERP_WINDOW_MS);
    private final java.util.Set<String> pendingControls = new java.util.HashSet<>();
    private WorldRenderer worldRenderer;
    private final MatchQuit quit = new MatchQuit();
    /** Everything about the in-match action button lives in its own class. */
    private final ActionButton actionButton = new ActionButton();
    /** The two lobby characters. */
    private final LobbyArt lobbyArt = new LobbyArt();
    private final ConnectView.Frame connectFrame = new ConnectView.Frame();
    private final ResultView.Frame resultFrame = new ResultView.Frame();
    private final LobbyView lobbyView = new LobbyView();
    /** Everything the lobby remembers lives here. */
    private final LobbyState lobby = new LobbyState();
    /** 0..1 the inline editor's own fade, so it never pops in or out. */
    private float editorFade;
    private final LobbyView.Frame lobbyFrame = new LobbyView.Frame();

    /** Fills the lobby page's frame from the app's state. */
    private LobbyView.Frame lobbyFrame(float w) {
        LobbyView.Frame f = lobbyFrame;
        if (room == null) {
            f.roomId = "";
            f.mapId = "";
        } else {
            f.roomId = room.roomId;
            f.mapId = room.mapId;
        }
        RoomPlayer other = opponentPlayer();
        f.youLabel = lobby.selectedRole == null ? "-" : lobby.selectedRole.name();
        f.opponentLabel = lobby.selectedRole == null || other == null || other.role == null
                ? "-" : other.role.name();
        f.selectPolice = lobby.selectPolice;
        f.selectThief = lobby.selectThief;
        f.beamOn = lobby.beamOn;
        f.roleNoticeT = lobby.roleNoticeT;
        f.roleNoticeRole = lobby.roleNoticeRole;
        f.roleNoticeText = lobby.roleNoticeText;
        f.busy = pending("SET_READY");
        f.reveal = UiAnim.smooth(lobby.readyReveal);
        f.pressLit = UiAnim.smooth(lobby.readyPress);
        f.cta = currentCta();
        f.ctaMuted = "WAITING...".equals(f.cta);
        f.leaveHot = lobby.leaveHot;
        f.lobbyStatus = lobbyStatusFade;
        f.modeLabel = "MODE  " + mode.name();
        f.bottomShift = bottomShift();
        f.margin = UiTheme.MARGIN;
        f.worldWidth = w;
        f.kickerTop = PAGE_KICKER;
        f.roleCx = ROLE_CX;
        f.role1Top = ROLE1_TOP;
        f.role2Top = ROLE2_TOP;
        f.iconDx = ICON_DX;
        f.iconR = LobbyArt.ICON_R;
        f.ctaTop = CTA_TOP;
        f.ctaW = CTA_W;
        f.ctaH = CTA_H;
        f.boxH = BOX_H;
        f.gridGap = GRID_GAP;
        f.leaveCentreX = leaveCentre(w);
        f.footerTop = 652f;
        return f;
    }

    /** The one word the primary action shows, decided once so the view cannot disagree. */
    private String currentCta() {
        if (lobby.readyQueued) return lobby.readyLabelAtPress;
        if (lobby.readyReturning) return lobby.readyLabelAfter;
        if (pending("SET_READY")) return "WAITING...";
        return isSelfReady() ? "CANCEL" : "READY";
    }

    /** Fills the connect page's frame from the app's own state; the view keeps nothing. */
    private ConnectView.Frame connectFrame() {
        float w = ui.getWorldWidth();
        ConnectView.Frame f = connectFrame;
        f.host = host;
        f.port = port;
        f.room = roomId;
        f.editedChip = editedChip;
        f.editedAtMs = editedAtMs;
        f.scenarioFade = scenarioFade;
        f.scenarioLabel = scenarioLabel();
        f.modeLabel = modeLabel();
        f.lanAvailable = lanGatewayFactory != null;
        f.permission = permission;
        f.status = statusFade;
        f.detail = detailFade;
        f.permissionLine = permFade;
        f.wash = this::washOf;
        f.margin = UiTheme.MARGIN;
        f.chipTop = CONNECT_CHIP_TOP;
        f.chipW = connectColumn(w);
        f.chipH = BOX_H;
        f.gap = GRID_GAP;
        f.actionTop = CONNECT_ACTION_TOP;
        f.ctaW = CTA_W;
        f.ctaH = CTA_H;
        f.scenarioW = SCENARIO_W;
        f.scenarioX = scenarioX(w);
        f.modeW = MODE_W;
        f.modeX = modeX(w);
        f.rule2Top = CONNECT_RULE2;
        f.bottomShift = bottomShift();
        f.kickerTop = PAGE_KICKER;
        return f;
    }

    /** Fills the result page's frame the same way. */
    private ResultView.Frame resultFrame() {
        ResultView.Frame f = resultFrame;
        f.result = result;
        f.selfRole = lobby.selectedRole;
        f.data = data;
        f.ticksPerSecond = (float) ticksPerSecond();
        f.bottomShift = bottomShift();
        f.canLeave = !pending("RETURN_LOBBY");
        f.leaveWash = washOf(1);
        f.margin = UiTheme.MARGIN;
        f.boxH = BOX_H;
        f.gap = GRID_GAP;
        f.actionTop = RESULT_ACTION_TOP;
        f.actionH = CTA_H;
        f.actionW = 300f;
        f.kickerTop = PAGE_KICKER;
        return f;
    }

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
        if (offlineGateway instanceof FakeGateway) {
            FakeGateway fixture = (FakeGateway) offlineGateway;
            fixture.configure(data);
            // The demo opponent waits a beat, so the lobby's READY -> CANCEL state is visible.
            fixture.useOpponentReadyDelay(2.0f);
        }

        // Without blending every translucent shape renders opaque, which turned the flashlight
        // beam into a solid cream wedge instead of light.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        // Blackout layout needs a real size hierarchy; the stock 15 px bitmap font cannot give it.
        fonts = UiFonts.load();
        widgets = new Widgets(batch, fonts, uiRound);
        font = fonts.mono;
        uiRound.create();
        touch.setWorldWidth(ui.getWorldWidth());
        worldRenderer = new WorldRenderer(shapes, worldCamera, uiCamera, world, ui, data);

        Gdx.input.setInputProcessor(new InputAdapter() {

            @Override public boolean touchDown(int sx, int sy, int pointer, int button) {
                Vector2 p = unproject(sx, sy);
                if (p.x < 0 || p.x > ui.getWorldWidth() || p.y < 0 || p.y > TouchController.UI_HEIGHT) return false;
                if (page() != Page.MATCH) return tap(p, pointer);
                actionDownMs = System.nanoTime() / 1_000_000L;
                // A page-level button on the match screen (QUIT) must win over the touch controls,
                // but every other touch still belongs to the stick/hold/drop.
                if (tap(p, pointer)) return true;
                return touch.down(pointer, p.x, p.y, lobby.selectedRole == Role.THIEF, carrying());
            }
            @Override public boolean touchDragged(int sx, int sy, int pointer) {
                if (page() != Page.MATCH) return false;
                Vector2 p = unproject(sx, sy);
                return touch.drag(pointer, p.x, p.y);
            }
            @Override public boolean touchUp(int sx, int sy, int pointer, int button) {
                boolean wasAction = touch.holdPointer() == pointer;
                long heldMs = System.nanoTime() / 1_000_000L - actionDownMs;
                touch.up(pointer);
                // A quick tap while carrying puts the material down where the thief stands.
                boolean atTarget = view != null && view.thief != null
                        && view.thief.interaction.candidate != null;
                if (wasAction && carrying() && heldMs < 300L && !atTarget) touch.fireDrop();
                return true;
            }
            @Override public boolean touchCancelled(int sx, int sy, int pointer, int button) {
                touch.cancel(pointer);
                return true;
            }
            @Override public boolean keyDown(int key) {
                if (editingField >= 0) {
                    if (key == Input.Keys.BACKSPACE && editText.length() > 0) {
                        editText.setLength(editText.length() - 1);
                    } else if (key == Input.Keys.ENTER) {
                        applyEditor();
                    } else if (key == Input.Keys.ESCAPE) {
                        closeEditor();
                    }
                    return true;
                }
                if (key == Input.Keys.ESCAPE) { touch.clear(); gateway.returnToLobby(); return true; }
                return false;
            }
            @Override public boolean keyTyped(char c) {
                if (editingField < 0) return false;
                if (c >= 32 && c < 127 && editText.length() < 40) editText.append(c);
                return true;
            }
        });
    }

    @Override public void resize(int width, int height) {
        world.update(width, height);
        ui.update(width, height, true);
        touch.setWorldWidth(ui.getWorldWidth());
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
        lobby.selectedRole = null;
        lobby.suppressRoomRole = true;
        lobby.readyReveal = 0f;
        lobby.readyPress = 0f;
        lobby.readyQueued = false;
        lobby.readyReturning = false;
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
                // A countdown means a match is beginning: whatever tick the previous one ended on
                // must not shadow the new one's counter.
                if (s.phase == RoomPhase.COUNTDOWN && (room == null || room.phase != RoomPhase.COUNTDOWN)) {
                    lastViewMatchId = null;
                    lastViewTick = Long.MIN_VALUE;
                    interpolator.clear();
                    resetActionButton();
                }
                room = s;
                if (s.phase == RoomPhase.LOBBY || s.phase == RoomPhase.COUNTDOWN) {
                    dropFrame();
                    touch.clear();
                }
                for (RoomPlayer p : s.players) {
                    if (!lobby.suppressRoomRole && session != null
                            && session.playerId.equals(p.playerId) && p.role != null) {
                        lobby.selectedRole = p.role;
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
        // A rejected role belongs next to that role, not in the footer with the ready counter.
        if ("SELECT_ROLE".equals(s.action) && s.state == ControlStatus.State.FAILED) {
            lobby.roleNoticeText = readableError(s.errorCode);
            lobby.roleNoticeRole = rolePickPending;
            lobby.roleNoticeT = 2.6f;
            pendingControls.remove(s.action);
            return;
        }
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

    /** Puts the action button back to its freshly-spawned state. */
    private void resetActionButton() {
        touch.clear();
        actionButton.reset();
    }

    /** One context per frame; pages never hold state of their own. */
    private PageContext pageContext() {
        if (pageContext == null || pageContext.worldWidth != ui.getWorldWidth()) {
            pageContext = new PageContext(batch, fonts, uiRound, widgets, ui.getWorldWidth());
        }
        return pageContext;
    }

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
        resetActionButton();
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

    private boolean tapEditor(Vector2 p) {
        float px = (ui.getWorldWidth() - EDITOR_W) / 2f;
        float fx = px + 22f;
        float fw = EDITOR_W - 44f;
        float by = EDITOR_TOP + EDITOR_BTN_TOP;
        float y = UiTheme.H - (by + EDITOR_BTN_H);
        if (inside(p, fx, y, EDITOR_BTN_W, EDITOR_BTN_H)) { startWash(8); return true; }
        if (inside(p, fx + fw - EDITOR_BTN_W, y, EDITOR_BTN_W, EDITOR_BTN_H)) {
            startWash(7);
            return true;
        }
        float fieldY = UiTheme.H - (editorFieldTop() + 66f);
        if (inside(p, fx, fieldY, fw, 66f)) {
            Gdx.input.setOnscreenKeyboardVisible(true);
        }
        return true;                        // everything else is behind the modal
    }

    private boolean tap(Vector2 p, int pointer) {
        if (fadePhase != 0) return true;   // the screen is covered
        if (editingField >= 0) return tapEditor(p);
        Page page = shownPage == null ? page() : shownPage;

        if (page == Page.CONNECT) {
            float cw3 = connectColumn(ui.getWorldWidth());
            float chipY = UiTheme.yFromTop(CONNECT_CHIP_TOP, BOX_H);
            if (inside(p, UiTheme.MARGIN, chipY, cw3, BOX_H)) { startWash(4); return true; }
            if (inside(p, UiTheme.MARGIN + cw3 + GRID_GAP, chipY, cw3, BOX_H)) {
                startWash(5); return true;
            }
            if (inside(p, UiTheme.MARGIN + 2f * (cw3 + GRID_GAP), chipY, cw3, BOX_H)) {
                startWash(6); return true;
            }
            float actY = UiTheme.yFromTop(CONNECT_ACTION_TOP, CTA_H);
            if (inside(p, UiTheme.MARGIN, actY, CTA_W, CTA_H)) { startWash(0); return true; }
            if (scenarioFade > 0.5f
                    && inside(p, scenarioX(ui.getWorldWidth()), actY, SCENARIO_W, CTA_H)) {
                startWash(2);
                return true;
            }
            if (inside(p, modeX(ui.getWorldWidth()), actY, MODE_W, CTA_H)) {
                startWash(3);
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
                lobby.suppressRoomRole = false;
                rolePickPending = Role.POLICE;
                if (!pending("SELECT_ROLE")) gateway.selectRole(Role.POLICE);
                return true;
            }
            if (inside(p, ROLE_CX - 200f, UiTheme.yFromTop(ROLE2_TOP - 8f, 108f), 420f, 108f)) {
                recordPress("role-thief");
                lobby.suppressRoomRole = false;
                rolePickPending = Role.THIEF;
                if (!pending("SELECT_ROLE")) gateway.selectRole(Role.THIEF);
                return true;
            }
            if (inside(p, UiTheme.MARGIN, UiTheme.yFromTop(CTA_TOP, CTA_H), CTA_W, CTA_H)) {
                recordPress("ready");
                // The click starts the animation; the request goes out when it lands, so the
                // button reacts before the network does.
                if (lobby.selectedRole == null || lobby.readyReveal < 0.5f) return true;
                if (!pending("SET_READY") && !lobby.readyQueued) {
                    // Two words: the one under the finger now, and the one that replaces it at the
                    // peak of the wash. Both are decided here, so the swap needs no server reply.
                    boolean wasReady = isSelfReady();
                    lobby.readyLabelAtPress = wasReady ? "CANCEL" : "READY";
                    lobby.readyLabelAfter = wasReady ? "READY" : "CANCEL";
                    lobby.readyQueued = true;
                    lobby.readyPress = 0f;
                }
                return true;
            }
            if (inside(p, leaveCentre(ui.getWorldWidth()) - UiDraw.width(fonts.mono, "LEAVE ROOM", 1.5f) / 2f - 24f, UiTheme.yFromTop(CTA_TOP, CTA_H), UiDraw.width(fonts.mono, "LEAVE ROOM", 1.5f) + 48f, CTA_H)) {
                recordPress("leave");
                // The animation runs first; the request goes out once the rule is at full size,
                // otherwise the screen changes before anything can be seen.
                if (!pending("LEAVE_ROOM") && !lobby.leaveQueued) {
                    lobby.leaveQueued = true;
                    lobby.leaveT = 0f;
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
            if (inside(p, quitX(ui.getWorldWidth()), quitY(), QUIT_W, QUIT_H)) {
                startWash(9);
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

    /** Opens the inline editor on one of the three connection fields. */
    private void openEditor(int field) {
        if (editingField >= 0 || field < 0 || field > 2) return;
        editingField = field;
        editText.setLength(0);
        editText.append(field == 0 ? host : field == 1 ? port : roomId);
        // A field tap means "I want to type", so the keyboard comes up with the panel. It is only
        // ever raised from here - MODE and SCENARIO never open the editor.
        Gdx.input.setOnscreenKeyboardVisible(true);
    }

    private void applyEditor() {
        String v = editText.toString().trim();
        if (!v.isEmpty()) {
            if (editingField == 0) host = v;
            else if (editingField == 1) port = v;
            else if (editingField == 2) roomId = v;
            editedChip = editingField;
            editedAtMs = System.nanoTime() / 1_000_000L;
        }
        closeEditor();
    }

    private float editorFieldTop() { return EDITOR_TOP + 30f; }

    private void closeEditor() {
        editingField = -1;
        editText.setLength(0);
        Gdx.input.setOnscreenKeyboardVisible(false);
        if (hideKeyboardHook != null) hideKeyboardHook.run();
    }

    private String editorTitle() {
        return editingField == 0 ? "SERVER HOST" : editingField == 1 ? "PORT" : "ROOM";
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
        // ShapeRenderer does not lobby.turn blending on for itself; without this every translucent
        // beam renders as an opaque slab.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        switch (page) {
            case CONNECT: {
                float w = ui.getWorldWidth();
                pageRule(shapes, w, CONNECT_RULE1);
                pageRule(shapes, w, CONNECT_RULE2 + bottomShift());
                break;
            }
            case LOBBY: {
                float w = ui.getWorldWidth();
                float bs = bottomShift();
                // Rounded, pure white, eight units: a hairline disappears on a phone.
                lobbyCapsule(shapes, UiTheme.MARGIN, RULE1_TOP, w - 2 * UiTheme.MARGIN, RULE_H, Color.WHITE);
                lobbyCapsule(shapes, UiTheme.MARGIN, RULE2_TOP + bs, w - 2 * UiTheme.MARGIN, RULE_H, Color.WHITE);
                lobbyRoleMarker(shapes, ROLE1_TOP, "POLICE", lobby.selectPolice);
                lobbyRoleMarker(shapes, ROLE2_TOP, "THIEF", lobby.selectThief);
                lobbyArt.police(shapes, ROLE_CX + ICON_DX, UiTheme.H - (ROLE1_TOP + 38f), lobby.turn,
                lobby.lobbyClock, lobby.beamOn);
                lobbyArt.thief(shapes, ROLE_CX + ICON_DX, UiTheme.H - (ROLE2_TOP + 38f), lobby.selectThief,
                lobby.lobbyClock);
                // Five slots at rest, the full label when pressed; 1.5 units under the text.
                float leaveW = UiDraw.width(fonts.mono, "LEAVE ROOM", 1.5f);
                float unit = UiDraw.width(fonts.mono, "M", 1.5f);
                float hot = UiAnim.smooth(lobby.leaveHot);
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
                // A white page: everything else is removed, so the count is the only thing to read.
                shapes.setColor(new Color(0.949f, 0.949f, 0.941f, 1f));
                shapes.rect(0f, 0f, w, UiTheme.H);

                float dotY = UiTheme.H - 300f;
                float gap = 34f;
                float dotR = 9f;
                float firstX = w / 2f - gap;
                for (int i = 0; i < 3; i++) {
                    // Brightness, not a filled slot: each dot eases between dim and lit.
                    float lit = countDot[i];
                    shapes.setColor(0.08f, 0.08f, 0.09f, 0.20f + 0.80f * lit);
                    shapes.circle(firstX + i * gap, dotY, dotR, 28);
                }
                break;
            }
            case MATCH: {
                float w = ui.getWorldWidth();
                // Stick: a hollow ring so the map shows through, plus a dot for the thumb.
                ShapeKit.ring(shapes, TouchController.JOY_X, TouchController.JOY_Y,
                        TouchController.JOY_R, 7.5f, new Color(1f, 1f, 1f, 0.55f));
                shapes.setColor(Color.WHITE);
                shapes.circle(TouchController.JOY_X + (float) touch.moveX() * 55f,
                        TouchController.JOY_Y + (float) touch.moveY() * 55f, 26f, 32);
                if (lobby.selectedRole == Role.THIEF) {
                    actionButton.drawShapes(shapes, touch.actionX(), touch.actionY(),
                            TouchController.HOLD_R);
                }
                // Same rounded navy slab as the menu's primary action, washed on press.
                scratchColor.set(READY_BLUE);
                scratchColor.lerp(Color.WHITE, washOf(9));
                lobbyCapsule(shapes, quitX(w), QUIT_TOP, QUIT_W, QUIT_H, scratchColor);
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

    /** True when the thief is holding a material, which turns the action button into DROP. */
    private boolean carrying() {
        return view != null && view.thief != null && view.thief.carriedMaterialId != null;
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
            case CONNECT: ConnectView.draw(pageContext(), connectFrame()); break;
            case LOBBY:
                if (room != null) lobbyView.draw(pageContext(), lobbyFrame(ui.getWorldWidth()));
                break;
            case COUNTDOWN: MatchViews.drawCountdown(pageContext(), countFade); break;
            case MATCH:
                MatchViews.drawHud(pageContext(), view, lobby.selectedRole, actionButton, touch,
                        marksFade, deliveredFade, exitsFade, quit, (float) ticksPerSecond(),
                        quitX(ui.getWorldWidth()), QUIT_TOP, QUIT_W, QUIT_H);
                break;
            case RESULT: ResultView.draw(pageContext(), resultFrame()); break;
            default: break;
        }

        // Both message lines share the left margin of every other row on every page.
        float msgMargin = UiTheme.MARGIN;
        float msgWidth = ui.getWorldWidth() - 2f * msgMargin;
        // One slot for both lines: an error outranks a notice while it is visible.
        TextFade message = errorFade.visible() && errorFade.alpha() >= noticeFade.alpha()
                ? errorFade : (noticeFade.visible() ? noticeFade : null);
        if (message != null) {
            boolean isError = message == errorFade;
            scratchColor.set(isError ? UiTheme.ACCENT : UiTheme.TEXT_SOFT);
            scratchColor.a = message.alpha();
            String line = isError ? "ERROR  " + message.shown() : message.shown();
            UiDraw.text(batch, fonts.mono, UiDraw.fit(fonts.mono, line, msgWidth, 1.5f),
                    msgMargin, 720f - 60f - UiDraw.capHeight(fonts.mono), scratchColor, 1.5f);
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
        if (editorFade > 0.01f) {
            FieldEditor.draw(pageContext(), editorTitle(), editText.toString(), washOf(7),
                    washOf(8), editorFade);
        }
        batch.end();
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


    /** The global error line and the softer notice line that shares its slot. */
    private final TextFade errorFade = new TextFade();
    private final TextFade noticeFade = new TextFade();
    private final TextFade statusFade = new TextFade();
    /** The lobby's footer line: the ready count hands over to STARTING by cross-fading. */
    private final TextFade lobbyStatusFade = new TextFade();
    /** The countdown digit cross-fades: 3 out, 2 in, and so on. */
    private final TextFade countFade = new TextFade();
    /** Per-dot brightness: the current digit lights its own dot, eased. */
    private final float[] countDot = new float[3];
    private final TextFade marksFade = new TextFade();
    private final TextFade deliveredFade = new TextFade();
    private final TextFade exitsFade = new TextFade();
    private final TextFade detailFade = new TextFade();
    private final TextFade permFade = new TextFade();


    private void pulse(int id) {
        pulseId = id;
        pulseT = 0f;
    }

    /** Top of a label that is vertically centred inside the primary button's band. */
    private float actionTextTop() {
        return CONNECT_ACTION_TOP + (CTA_H - UiDraw.capHeight(fonts.mono)) / 2f;
    }

    /** A ring, used for the hold/drop controls. */
    /**
     * HOLD: a green disc with a white rim, a clamp closed around the kept material. A pictogram
     * rather than a literal hand - at 70 px the silhouette of a hand turns into a blob.
     */
    private void drawHoldDisc(ShapeRenderer shapes, float cx, float cy, float r, float alpha) {
        Color bed = new Color(0.145f, 0.475f, 0.302f, 1f);
        Color ink = new Color(0.043f, 0.086f, 0.063f, 0.95f);
        Color gem = new Color(1f, 0.94f, 0.72f, 1f);

        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.circle(cx, cy, r, 48);
        shapes.setColor(bed);
        shapes.circle(cx, cy, r - 3f, 48);

        float u = r / 70f;
        // a U-shaped clamp: two tall jaws joined underneath, open at the top
        ShapeKit.capsule(shapes, cx - 28f * u, cy - 30f * u, 18f * u, 58f * u, ink);
        ShapeKit.capsule(shapes, cx + 10f * u, cy - 30f * u, 18f * u, 58f * u, ink);
        ShapeKit.capsule(shapes, cx - 26f * u, cy - 32f * u, 52f * u, 16f * u, ink);
        // the material squeezed between them
        shapes.setColor(gem);
        shapes.triangle(cx, cy + 34f * u, cx - 15f * u, cy + 10f * u, cx + 15f * u, cy + 10f * u);
        shapes.triangle(cx - 15f * u, cy + 10f * u, cx + 15f * u, cy + 10f * u, cx, cy - 14f * u);
        shapes.setColor(1f, 1f, 1f, 0.9f);
        shapes.triangle(cx, cy + 34f * u, cx - 5f * u, cy + 14f * u, cx + 6f * u, cy + 14f * u);
    }

    /** DROP: a yellow disc with a white rim, the material leaving with a downward arrow. */
    private void drawDropDisc(ShapeRenderer shapes, float cx, float cy, float r) {
        Color bed = new Color(0.639f, 0.478f, 0.086f, 1f);
        Color ink = new Color(0.114f, 0.086f, 0.020f, 0.95f);
        Color gem = new Color(1f, 0.96f, 0.78f, 1f);

        shapes.setColor(1f, 1f, 1f, 0.85f);
        shapes.circle(cx, cy, r, 48);
        shapes.setColor(bed);
        shapes.circle(cx, cy, r - 3f, 48);

        float u = r / 50f;
        // the material, still in hand at the top
        shapes.setColor(gem);
        shapes.triangle(cx, cy + 34f * u, cx - 12f * u, cy + 16f * u, cx + 12f * u, cy + 16f * u);
        shapes.triangle(cx - 12f * u, cy + 16f * u, cx + 12f * u, cy + 16f * u, cx, cy - 2f * u);
        shapes.setColor(1f, 1f, 1f, 0.9f);
        shapes.triangle(cx, cy + 34f * u, cx - 4f * u, cy + 20f * u, cx + 5f * u, cy + 20f * u);
        // the arrow it travels along
        shapes.setColor(ink);
        shapes.rect(cx - 3f * u, cy - 14f * u, 6f * u, 14f * u);
        shapes.triangle(cx - 12f * u, cy - 14f * u, cx + 12f * u, cy - 14f * u, cx, cy - 32f * u);
    }

    /** A true ring: segments laid along the circle, so the inside stays see-through. */

    /** Fills the disc from the bottom up to {@code level}, clipping each stripe to the circle. */

    /** The success mark: white outline, blue core, drawn one stroke at a time. */


    /** Rounded rectangle in y-up shape coordinates, with an explicit corner radius. */

    /** Capsule in y-up shape coordinates (lobbyCapsule takes a top-based y). */

    private void ring(ShapeRenderer shapes, float cx, float cy, float r, float alpha) {
        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.circle(cx, cy, r, 48);
        shapes.setColor(0.043f, 0.063f, 0.090f, 1f);   // the floor's own colour
        shapes.circle(cx, cy, r - 3f, 48);
    }

    /** White rounded chip with a label over a value, both centred. */

    /**
     * A white chip. {@code lit} warms it while it is being pressed: white cannot wash to white, so
     * a press tints it towards the accent instead.
     */

    /** Deep navy slab with a white label, the same primary as the lobby. */

    /** Deep navy slab that washes to white while it is being pressed, like the lobby's READY. */

    /**
     * Draws a label centred in a box, shrinking it when it would not fit. Labels are the one place
     * where a longer word must not spill outside its slab, and the boxes are fixed sizes.
     */

    /** As fitText but with a forced scale, so swapping the word never shifts the glyphs. */

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

    /**
     * The inline editor. A light panel, a white field, and two navy buttons - the same shapes the
     * rest of the app uses, instead of the platform's own dialog.
     */

    /** Navy rounded button that washes white while it is pressed. */

    /**
     * A control drawn as an accent frame around the background: rounded outside, rounded inside,
     * so it reads as a nested border rather than a filled button.
     */


    /** The lobby footer's current wording, before it is faded onto the screen. */
    private String lobbyStatusText() {
        if (room == null) return "";
        int ready = 0;
        int total = 0;
        for (RoomPlayer p : room.players) {
            total++;
            if (p.ready) ready++;
        }
        if (total <= 1) return "WAITING FOR A SECOND PLAYER";
        if (ready >= total) return "STARTING...";
        if (ready == 0) return "0 / " + total + " READY \u00b7 BOTH MUST READY";
        return ready + " / " + total + " READY \u00b7 WAITING FOR OPPONENT";
    }

    private String scenarioLabel() { return "SCENARIO  " + scenario; }

    private String modeLabel() { return "MODE  " + mode.name(); }

    private boolean scenarioActive() {
        return scenario != FakeGateway.Scenario.NORMAL;
    }

    private float connectColumn(float w) {
        return (w - 2 * UiTheme.MARGIN - 2 * GRID_GAP) / 3f;
    }

    private static final float MODE_W = 210f;
    /** Wide enough for the longest scenario name, so switching never changes the box. */
    private static final float SCENARIO_W = 320f;

    /** MODE sits against the right margin; SCENARIO is parked immediately to its left. */
    private float modeX(float w) {
        return w - UiTheme.MARGIN - MODE_W;
    }

    private float scenarioX(float w) {
        return modeX(w) - SCENARIO_W - 24f;
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

    /**
     * Draws a centred string character by character, mixing in the lit colour for the glyphs the
     * beam has already passed. {@code lit} is 0..1 of how far across the string the light reached.
     */


    private final Color scratchLit = new Color();

    /**
     * White chip with black text. When the value changes, the old string fades out while the new
     * one fades in, so a role swap reads as a change rather than a flicker.
     */



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
        ShapeKit.capsule(shapes, x, UiTheme.yFromTop(top, h), w, h, color);
    }

    // ---- characters -------------------------------------------------------------------------

    /**
     * Police cap from above with a flashlight in front.
     *
     * <p>Idle: the light sweeps slowly across the room. Chosen: it cuts out, the cap swings round,
     * and the beam grows back out towards the words. Brightness falls off with distance, so the
     * beam is strongest at the lens.
     */

    /** Triangle with a different alpha at each vertex: the whole gradient without stair steps. */

    /**
     * Ninja from above, drawn with the same recipe as the match badge: scarf tails, white rim,
     * dark hood, headband. Selection only grows the halo.
     */

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
        boolean countdown = page() == Page.COUNTDOWN || shownPage == Page.COUNTDOWN;
        phaseIn = countdown ? 0.16f : 0.26f;
        phaseHold = countdown ? 0.08f : 0.26f;
        phaseOut = countdown ? 0.40f : 1.14f;
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
            lobby.selectedRole = null;
            lobby.suppressRoomRole = true;
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
        if (lobby.readyQueued || lobby.readyReturning) return true;
        for (int i = 0; i < washing.length; i++) {
            if (washing[i] || returning[i]) return true;
        }
        return false;
    }

    private void updateTransition(float dt) {
        switch (fadePhase) {
            case 1:
                fadeT += dt / phaseIn;
                if (fadeT >= 1f) {
                    // Opaque now, so the swap is invisible - and the destination starts rendering
                    // behind the curtain while it holds, which is what lets its entrance settle.
                    adoptPage();
                    fadePhase = 2;
                    fadeT = 0f;
                }
                break;
            case 2:
                fadeT += dt / phaseHold;
                if (fadeT >= 1f) {
                    fadePhase = 3;
                    fadeT = 0f;
                }
                break;
            case 3:
                fadeT += dt / phaseOut;
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
        editorFade = UiAnim.approach(editorFade, editingField >= 0 ? 1f : 0f, dt, 7f);
        errorFade.step(dt, errorText);
        // The two share one slot on screen: while an error is present the notice waits its lobby.turn.
        noticeFade.step(dt, errorFade.shown().isEmpty() ? notice : "");
        statusFade.step(dt, connectionState);
        lobbyStatusFade.step(dt, lobbyStatusText());
        if (view != null) {
            marksFade.step(dt, Integer.toString(view.marks));
            deliveredFade.step(dt, Integer.toString(view.deliveredCount));
            exitsFade.step(dt, view.exitsOpen ? "OPEN" : "CLOSED");
        }
        detailFade.step(dt, connectionDetail);
        permFade.step(dt, mode == Mode.LAN
                ? "ACCESS_LOCAL_NETWORK  " + (permission == null ? "N/A" : permission.describe())
                : "");
        lobby.readyReveal = UiAnim.approach(lobby.readyReveal, lobby.selectedRole == null ? 0f : 1f, dt, 4.5f);
        if (shownPage == Page.COUNTDOWN) {
            int secs = Math.max(1, Math.min(3, room == null ? 3
                    : (int) Math.ceil(room.countdownRemainingMs / 1000.0)));
            countFade.step(dt, Integer.toString(secs));
            // They fill in order and stay lit: 3 lights one, 2 lights two, 1 lights all three.
            for (int i = 0; i < countDot.length; i++) {
                countDot[i] = UiAnim.approach(countDot[i], (3 - i) >= secs ? 1f : 0f, dt, 6f);
            }
        }
        for (int i = 0; i < wash.length; i++) {
            if (washing[i]) {
                wash[i] = Math.min(1f, wash[i] + dt / WASH_UP);
                if (wash[i] >= 1f) {
                    // Reaching white sends the request. The button then falls back to navy on its
                    // own; that return trip is the wash played backwards, and the room may answer
                    // and cover the screen while it runs.
                    washing[i] = false;
                    returning[i] = true;
                    switch (i) {
                        case 0: connect(); break;
                        case 1: touch.clear(); gateway.returnToLobby(); break;
                        case 2: {
                            FakeGateway.Scenario[] all = FakeGateway.Scenario.values();
                            scenario = all[(scenario.ordinal() + 1) % all.length];
                            // A failure belongs to the scenario that produced it: switching away
                            // must not leave a stale red line behind.
                            errorText = "";
                            notice = "";
                            break;
                        }
                        case 3:
                            mode = mode == Mode.OFFLINE ? Mode.LAN : Mode.OFFLINE;
                            errorText = "";
                            notice = "";
                            break;
                        case 7: applyEditor(); break;
                        case 8: closeEditor(); break;
                        case 9: requestQuit(); break;
                        default: pendingEditor = i - 4; break;
                    }
                }
            } else if (returning[i]) {
                wash[i] = Math.max(0f, wash[i] - dt / WASH_DOWN);
                if (wash[i] <= 0f) {
                    wash[i] = 0f;
                    returning[i] = false;
                    // The editor opens only after the whole press has played out, so the player
                    // sees their tap acknowledged before a panel covers the page.
                    if (pendingEditor >= 0 && pendingEditor == i - 4) {
                        int field = pendingEditor;
                        pendingEditor = -1;
                        openEditor(field);
                    }
                }
            }
        }
        if (pulseId >= 0) {
            pulseT += dt;
            if (pulseT > 0.50f) pulseId = -1;
        }
        lobby.lobbyClock += dt;
        // Touch only: there is no pointer to hover with on a phone, so the state machine is
        // driven entirely by what is selected.
        int hover = 0;
        lobby.selectPolice = UiAnim.approach(lobby.selectPolice, lobby.selectedRole == Role.POLICE ? 1f : 0f, dt, 5.5f);
        lobby.selectThief = UiAnim.approach(lobby.selectThief, lobby.selectedRole == Role.THIEF ? 1f : 0f, dt, 6f);

        // Three beats, in both directions: lobby.turn the head first, then switch the lamp on. Lighting
        // before the head has moved would shine the torch at whatever the cap happened to face.
        if (lobby.selectedRole == Role.POLICE) {
            if (toPoliceT < 0f) toPoliceT = 0f;
            toPoliceT += dt;
            fromPoliceT = -1f;
            lobby.turn = UiAnim.clamp01((toPoliceT - 0.08f) / 0.30f);
            lobby.beamOn = toPoliceT > 0.40f
                    ? UiAnim.approach(lobby.beamOn, 1f, dt, 3.4f)
                    : UiAnim.approach(lobby.beamOn, 0f, dt, 18f);
        } else {
            toPoliceT = -1f;
            if (fromPoliceT < 0f) fromPoliceT = 0f;
            fromPoliceT += dt;
            lobby.beamOn = UiAnim.approach(lobby.beamOn, 0f, dt, 16f);
            lobby.turn = 1f - UiAnim.clamp01((fromPoliceT - 0.04f) / 0.28f);
        }


        boolean actionHeld = touch.holdPointer() != -1;
        actionButton.update(dt, view, actionHeld, data.rules.interactTicks,
                data.rules.tickRate);

        scenarioFade = UiAnim.approach(scenarioFade, mode == Mode.LAN ? 0f : 1f, dt, 6f);
        countdownReveal = UiAnim.approach(countdownReveal,
                shownPage == Page.COUNTDOWN ? 1f : 0f, dt, 3.2f);
        if (lobby.roleNoticeT > 0f) lobby.roleNoticeT = Math.max(0f, lobby.roleNoticeT - dt);

        // LEAVE ROOM's rule: 0.14s up, 0.10s hold, 0.26s back down. One shot, no loop.
        if (lobby.leaveT >= 0f) {
            lobby.leaveT += dt;
            if (lobby.leaveT < 0.14f) {
                lobby.leaveHot = lobby.leaveT / 0.14f;
            } else if (lobby.leaveT < 0.30f) {
                lobby.leaveHot = 1f;
            } else if (lobby.leaveT < 0.62f) {
                lobby.leaveHot = 1f - (lobby.leaveT - 0.30f) / 0.32f;
            } else {
                lobby.leaveHot = 0f;
                lobby.leaveT = -1f;
            }
            if (lobby.leaveQueued && lobby.leaveT >= 0.34f) {
                lobby.leaveQueued = false;
                touch.clear();
                gateway.leaveRoom();
            }
        } else {
            lobby.leaveHot = 0f;
        }

        lobbyView.step(dt);

        if (lobby.readyQueued) {
            lobby.readyPress = Math.min(1f, lobby.readyPress + dt / WASH_UP);
            if (lobby.readyPress >= 1f) {
                lobby.readyQueued = false;
                lobby.readyReturning = true;
                if (!pending("SET_READY")) gateway.setReady(!isSelfReady());
            }
        } else if (lobby.readyReturning) {
            lobby.readyPress = Math.max(0f, lobby.readyPress - dt / WASH_DOWN);
            if (lobby.readyPress <= 0f) {
                lobby.readyPress = 0f;
                lobby.readyReturning = false;
            }
        } else {
            lobby.readyPress = UiAnim.approach(lobby.readyPress, 0f, dt, 6f);
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



    /** One scale for every CTA word, measured against the longest one, so text never shifts. */

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** One faded caption under the action button. */

    /** One compact row under the minimap: grey label, white value, same baseline. */

    /** White rounded plate: a grey caption over a dark value, both centred. */

    /** One grey label followed by a white value on the same line; the gap follows the label. */


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
