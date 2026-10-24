package com.lightchase.client;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
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
    private final FitViewport ui = new FitViewport(TouchController.UI_WIDTH, TouchController.UI_HEIGHT, uiCamera);
    private final TouchController touch = new TouchController();

    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont font;
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

    private Role selectedRole = Role.THIEF;
    private Page lastPage;
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

        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.getData().setScale(1.45f);
        worldRenderer = new WorldRenderer(shapes, worldCamera, uiCamera, world, ui, data);

        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int sx, int sy, int pointer, int button) {
                Vector2 p = unproject(sx, sy);
                if (p.x < 0 || p.x > TouchController.UI_WIDTH || p.y < 0 || p.y > TouchController.UI_HEIGHT) return false;
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
        connect();
    }

    @Override public void dispose() {
        touch.clear();
        gateway.close();
        if (shapes != null) shapes.dispose();
        if (batch != null) batch.dispose();
        if (font != null) font.dispose();
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
                    if (session != null && session.playerId.equals(p.playerId) && p.role != null) {
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
        notice = s.action + ": " + s.state + (s.errorCode == null ? "" : " (" + s.errorCode + ")");
        if (s.state == ControlStatus.State.FAILED) errorText = s.action + " failed: " + s.errorCode;
        if (s.state == ControlStatus.State.TIMED_OUT) errorText = s.action + " timed out";
        if (s.state == ControlStatus.State.SUCCEEDED) errorText = "";
    }

    private boolean pending(String action) { return pendingControls.contains(action); }

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
        Page page = page();

        if (page == Page.CONNECT) {
            if (inside(p, 60, 500, 520, 52)) { edit("Server host", host, "10.0.2.2", v -> host = v); return true; }
            if (inside(p, 620, 500, 180, 52)) { edit("Port", port, "8080", v -> port = v); return true; }
            if (inside(p, 840, 500, 340, 52)) { edit("Room id", roomId, "DEMO", v -> roomId = v); return true; }
            if (inside(p, 60, 380, 320, 64)) { connect(); return true; }
            if (inside(p, 420, 380, 420, 64)) {
                FakeGateway.Scenario[] all = FakeGateway.Scenario.values();
                scenario = all[(scenario.ordinal() + 1) % all.length];
                return true;
            }
            if (inside(p, 860, 380, 320, 64)) {
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
            if (inside(p, 60, 360, 220, 64)) { if (!pending("SELECT_ROLE")) gateway.selectRole(Role.POLICE); return true; }
            if (inside(p, 300, 360, 220, 64)) { if (!pending("SELECT_ROLE")) gateway.selectRole(Role.THIEF); return true; }
            if (inside(p, 560, 360, 260, 64)) { if (!pending("SET_READY")) gateway.setReady(!isSelfReady()); return true; }
            if (inside(p, 860, 360, 260, 64)) { if (!pending("LEAVE_ROOM")) { touch.clear(); gateway.leaveRoom(); } return true; }
            return true;
        }

        if (page == Page.RESULT) {
            if (inside(p, 60, 260, 320, 64)) {
                if (!pending("RETURN_LOBBY")) { touch.clear(); gateway.returnToLobby(); }
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
        Page page = page();
        if (page != lastPage) {
            String summary = quit.takeSummary();
            notice = summary == null ? "" : summary;
            touch.clear();
            lastPage = page;
        }
        boolean inMatch = page == Page.MATCH;
        gateway.submitInput(inMatch ? touch.frame() : InputFrame.ZERO);
        if (gateway instanceof FakeGateway) ((FakeGateway) gateway).advance(Gdx.graphics.getDeltaTime());

        Gdx.gl.glClearColor(0.035f, 0.05f, 0.08f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        interpolator.sample(System.nanoTime() / 1_000_000L);
        if (inMatch) worldRenderer.renderWorld(view, interpolator);
        drawUi(page);
        if (inMatch) worldRenderer.renderMinimap(view, interpolator);
        drawText(page);
    }

    private void drawUi(Page page) {
        ui.apply();
        shapes.setProjectionMatrix(uiCamera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0.045f, 0.07f, 0.11f, 1f);
        shapes.rect(0, 655, TouchController.UI_WIDTH, 65);

        switch (page) {
            case CONNECT:
                field(60, 500, 520, 52);
                field(620, 500, 180, 52);
                field(840, 500, 340, 52);
                button(60, 380, 320, 64, true);
                button(420, 380, 420, 64, false);
                button(860, 380, 320, 64, mode == Mode.LAN, lanGatewayFactory != null);
                break;
            case LOBBY: {
                slot(60, 470, 520, 64, true);
                slot(620, 470, 520, 64, false);
                button(60, 360, 220, 64, selectedRole == Role.POLICE, !pending("SELECT_ROLE"));
                button(300, 360, 220, 64, selectedRole == Role.THIEF, !pending("SELECT_ROLE"));
                button(560, 360, 260, 64, isSelfReady(), !pending("SET_READY"));
                button(860, 360, 260, 64, false, !pending("LEAVE_ROOM"));
                break;
            }
            case COUNTDOWN: {
                float progress = room == null ? 0f : 1f - Math.min(1f, room.countdownRemainingMs / countdownTotalMs());
                shapes.setColor(0.10f, 0.18f, 0.26f, 1f);
                shapes.rect(340, 300, 600, 24);
                shapes.setColor(0.14f, 0.55f, 0.62f, 1f);
                shapes.rect(340, 300, 600 * progress, 24);
                break;
            }
            case MATCH: {
                shapes.setColor(0.12f, 0.2f, 0.29f, 1f);
                shapes.circle(TouchController.JOY_X, TouchController.JOY_Y, TouchController.JOY_R, 48);
                shapes.setColor(Color.CYAN);
                shapes.circle(TouchController.JOY_X + (float) touch.moveX() * 55f,
                        TouchController.JOY_Y + (float) touch.moveY() * 55f, 28f, 32);
                if (selectedRole == Role.THIEF) {
                    shapes.setColor(touch.interacting() ? Color.TEAL : new Color(0.17f, 0.26f, 0.34f, 1f));
                    shapes.circle(TouchController.HOLD_X, TouchController.HOLD_Y, TouchController.HOLD_R, 48);
                    shapes.setColor(0.17f, 0.26f, 0.34f, 1f);
                    shapes.circle(TouchController.DROP_X, TouchController.DROP_Y, TouchController.DROP_R, 32);
                    float progress = interactionProgress();
                    if (progress > 0) {
                        shapes.setColor(0.10f, 0.18f, 0.26f, 1f);
                        shapes.rect(TouchController.HOLD_X - 90, TouchController.HOLD_Y + 86, 180, 14);
                        shapes.setColor(0.14f, 0.85f, 0.62f, 1f);
                        shapes.rect(TouchController.HOLD_X - 90, TouchController.HOLD_Y + 86, 180 * progress, 14);
                    }
                }
                // Quitting mid-match keeps the connection open until the server sends GAME_OVER.
                button(QUIT_X, QUIT_Y, QUIT_W, QUIT_H, false, !quit.active() && !pending("LEAVE_ROOM"));
                break;
            }
            case RESULT:
                button(60, 260, 320, 64, true, !pending("RETURN_LOBBY"));
                break;
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
        font.setColor(Color.WHITE);
        font.draw(batch, "LIGHT CHASE  /  " + page.name(), 30, 700);
        if (page != Page.MATCH) {
            font.setColor(Color.GOLD);
            font.draw(batch, "OFFLINE FIXTURE - not the real server, no game rules", 30, 672);
        }

        switch (page) {
            case CONNECT: drawConnectText(); break;
            case LOBBY: drawLobbyText(); break;
            case COUNTDOWN: drawCountdownText(); break;
            case MATCH: drawMatchText(); break;
            case RESULT: drawResultText(); break;
            default: break;
        }

        if (!errorText.isEmpty()) {
            font.setColor(1f, 0.45f, 0.45f, 1f);
            font.draw(batch, "ERROR  " + errorText, 30, 60);
        } else if (!notice.isEmpty()) {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, notice, 30, 60);
        }
        batch.end();
    }

    private void drawConnectText() {
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "SERVER HOST", 60, 585);
        font.draw(batch, "PORT", 620, 585);
        font.draw(batch, "ROOM", 840, 585);
        font.setColor(Color.WHITE);
        font.draw(batch, host, 78, 536);
        font.draw(batch, port, 638, 536);
        font.draw(batch, roomId, 858, 536);
        font.draw(batch, "CONNECT", 150, 424);
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "SCENARIO: " + scenario, 440, 424);
        font.setColor(mode == Mode.LAN ? new Color(0.55f, 0.85f, 0.95f, 1f) : Color.LIGHT_GRAY);
        font.draw(batch, "MODE: " + mode, 888, 424);
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Status: " + connectionState + (connectionDetail.isEmpty() ? "" : "  (" + connectionDetail + ")"),
                60, 320);
        if (mode == Mode.LAN) {
            boolean ok = permission == null || permission.granted();
            font.setColor(ok ? new Color(0.55f, 0.95f, 0.45f, 1f) : new Color(1f, 0.65f, 0.35f, 1f));
            font.draw(batch, "ACCESS_LOCAL_NETWORK: " + (permission == null ? "N/A (no platform gate)" : permission.describe()),
                    60, 240);
        }
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Tap a field to edit. SCENARIO replays fixture failures; MODE switches OFFLINE / LAN.",
                60, 280);
    }

    private void drawLobbyText() {
        font.setColor(Color.WHITE);
        font.draw(batch, "ROOM  " + room.roomId + "     PHASE  " + room.phase, 60, 620);
        font.draw(batch, "MAP  " + room.mapId + "     REV  " + room.revision, 60, 578);

        RoomPlayer self = selfPlayer();
        RoomPlayer other = opponentPlayer();
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "YOU      " + (self == null ? "-" : describe(self)), 78, 512);
        font.draw(batch, "OPPONENT " + (other == null ? "waiting for a second player" : describe(other)), 638, 512);

        font.setColor(Color.WHITE);
        font.draw(batch, "POLICE", 128, 404);
        font.draw(batch, "THIEF", 368, 404);
        font.draw(batch, isSelfReady() ? "CANCEL READY" : "READY", 596, 404);
        font.draw(batch, "LEAVE ROOM", 900, 404);

        font.setColor(Color.LIGHT_GRAY);
        if (room.phase == RoomPhase.LOBBY) {
            font.draw(batch, "Both players must be ready before the 3 second countdown starts.", 60, 300);
        } else {
            font.draw(batch, "Ready is disabled outside the lobby phase.", 60, 300);
        }
    }

    private String describe(RoomPlayer player) {
        String role = player.role == null ? "-" : player.role.name();
        String ready = player.ready ? "READY" : "not ready";
        String connection = ConnectionStates.CONNECTED.equals(player.connection) ? "" : "   [" + player.connection + "]";
        return player.playerId + "   " + role + "   " + ready + connection;
    }

    private void drawCountdownText() {
        int seconds = room == null ? 0 : (int) Math.ceil(room.countdownRemainingMs / 1000.0);
        float previous = font.getData().scaleX;
        font.getData().setScale(6f);
        font.setColor(Color.WHITE);
        font.draw(batch, Integer.toString(Math.max(0, seconds)), 610, 500);
        font.getData().setScale(previous);
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Match starting - input is ignored until the countdown ends.", 60, 240);
        font.draw(batch, "Remaining " + (room == null ? 0 : room.countdownRemainingMs) + " ms", 60, 200);
    }

    private void drawMatchText() {
        String time = view == null ? "--" : String.format(Locale.ROOT, "%.1f", view.remainingTicks / ticksPerSecond());
        String marks = view == null ? "-" : Integer.toString(view.marks);
        String delivered = view == null ? "-" : Integer.toString(view.deliveredCount);
        String exits = view == null ? "-" : (view.exitsOpen ? "OPEN" : "CLOSED");
        font.setColor(Color.WHITE);
        font.draw(batch, "TIME " + time + "s     MARKS " + marks + "/" + data.rules.maxMarks
                        + "     DELIVERED " + delivered + "/" + data.map.materials.size() + "     EXITS " + exits,
                30, 672);

        if (!ConnectionStates.CONNECTED.equals(connectionState)) {
            font.setColor(new Color(1f, 0.62f, 0.30f, 1f));
            font.draw(batch, "NET " + connectionState + (connectionDetail.isEmpty() ? "" : " - " + connectionDetail),
                    700, 640);
        }

        font.setColor(quit.active() ? new Color(1f, 0.75f, 0.35f, 1f) : Color.WHITE);
        font.draw(batch, quit.active() ? "LEAVING ..." : "QUIT MATCH", QUIT_X + 52, QUIT_Y + 34);
        font.setColor(Color.WHITE);

        ThiefPrivate thief = view == null ? null : view.thief;
        if (thief != null) {
            font.setColor(Color.LIGHT_GRAY);
            String carried = thief.carriedMaterialId == null ? "none" : thief.carriedMaterialId;
            String protection = thief.protection + (thief.protectionRemainingTicks > 0
                    ? String.format(Locale.ROOT, " %.1fs", thief.protectionRemainingTicks / ticksPerSecond()) : "");
            font.draw(batch, "CARRIED " + carried + "     PROTECTION " + protection, 30, 636);

            InteractionTarget chosen = thief.interaction.active != null
                    ? thief.interaction.active : thief.interaction.candidate;
            String target = chosen == null ? "none" : chosen.type + " " + chosen.targetId;
            font.draw(batch, String.format(Locale.ROOT, "TARGET %s     PROGRESS %d%%     BLOCKED %s",
                    target, Math.round(interactionProgress() * 100f), thief.interaction.blockedReason), 30, 610);

            StringBuilder taken = new StringBuilder();
            for (OriginStatus status : view.originStatus) {
                if (status.everPicked) {
                    if (taken.length() > 0) taken.append(" ");
                    taken.append(status.materialId);
                }
            }
            font.draw(batch, "ORIGINS TAKEN " + (taken.length() == 0 ? "none" : taken), 30, 584);
            font.draw(batch, "MOVE", 105, 32);
            font.draw(batch, "HOLD", 1096, 150);
            font.draw(batch, "DROP", 940, 110);
        } else {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, "Police view: no private thief panel by design.", 30, 610);
            font.draw(batch, "MOVE", 105, 32);
        }
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "MINIMAP", WorldRenderer.MM_X,
                WorldRenderer.MM_Y + data.map.height * WorldRenderer.MM_SCALE + 24);
        if (view != null) {
            font.draw(batch, String.format(Locale.ROOT, "POS %.2f, %.2f", view.self.x, view.self.y), 280, 110);
        }
    }

    private void drawResultText() {
        font.setColor(Color.WHITE);
        font.draw(batch, "MATCH RESULT", 60, 620);
        if (result == null) {
            font.draw(batch, "Waiting for the result message ...", 60, 560);
            return;
        }
        boolean noWinner = result.winner == null;
        boolean iWon = !noWinner && result.winner == selectedRole;
        font.setColor(noWinner ? new Color(0.85f, 0.86f, 0.88f, 1f)
                : iWon ? new Color(0.55f, 0.95f, 0.45f, 1f) : new Color(0.95f, 0.55f, 0.50f, 1f));
        font.draw(batch, "WINNER  " + (noWinner ? "NONE (both players left)" : result.winner.name()), 60, 570);
        font.setColor(Color.WHITE);
        font.draw(batch, "YOUR RESULT  " + (noWinner ? "NO DECISION" : (iWon ? "WIN" : "LOSS"))
                + "   (you are " + selectedRole + ")", 60, 530);
        font.draw(batch, "REASON  " + result.reason, 60, 490);
        font.draw(batch, "MARKS  " + result.marks + "/" + data.rules.maxMarks, 60, 450);
        font.draw(batch, "DELIVERED  " + result.deliveredCount + "/" + data.map.materials.size(), 60, 410);
        font.draw(batch, String.format(Locale.ROOT, "ELAPSED  %.1fs", result.elapsedTicks / ticksPerSecond()), 60, 370);
        font.draw(batch, "BACK TO LOBBY", 150, 304);
        font.setColor(Color.LIGHT_GRAY);
        font.draw(batch, "Result id " + result.resultId + "   match " + result.matchId, 60, 180);
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
