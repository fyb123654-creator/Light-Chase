package com.lightchase.network;

import com.lightchase.contracts.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Tests frame-by-frame sampling through the real socket, with a manually advanced gateway clock. */
public class GatewaySuspectInputTest {
    private final AtomicLong now = new AtomicLong(10_000);
    private final WireCodec codec = new WireCodec();
    private final BlockingQueue<RoomState> rooms = new LinkedBlockingQueue<>();
    private final List<GatewayError> errors = new CopyOnWriteArrayList<>();
    private ClientGatewayHealthTest.MockPeerServer peer;
    private WebSocketClientGateway gateway;
    private long seq = 20, revision = 1;

    @Before public void connect() throws Exception {
        peer = new ClientGatewayHealthTest.MockPeerServer(); peer.start();
        assertTrue(peer.started.await(3, TimeUnit.SECONDS));
        gateway = new WebSocketClientGateway(now::get, 1000, 3000, 5000, 250, 0);
        gateway.connect(new ConnectionOptions("127.0.0.1", peer.getPort(), "TEST", "test", "map-hash-v1", "config-hash-v1"),
                new ClientGatewayHealthTest.TestListener() {
                    @Override public void onRoom(RoomState room) { rooms.add(room); }
                    @Override public void onError(GatewayError error) { errors.add(error); }
                });
        WireCodec.Message hello = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull(hello);
        peer.sendWelcome(hello.requestId, "map-hash-v1", "config-hash-v1");
        WireCodec.Message join = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull(join);
        peer.conn.send(codec.encode("ACK", ++seq, join.requestId, null, ProtocolContractTest.fields("action", "JOIN_ROOM")));
        room(RoomPhase.RUNNING, "m-1");
    }
    @After public void stop() throws Exception {
        if (gateway != null) gateway.close();
        if (peer != null) peer.stop(1000);
    }

    @Test public void everyFrameDuringSuspectCannotReplayMovementHeldOrDrop() throws Exception {
        enter();
        frames(new InputFrame(1, -1, true, true), 30);
        assertTrue(inputs().isEmpty());
        recover(); frames(new InputFrame(1, -1, true, true), 4);
        assertZero(inputs()); assertTrue(errors.isEmpty());
    }
    @Test public void controlsHeldBeforeOutageNeedReleaseEvenWithoutSuspectSubmissions() throws Exception {
        gateway.submitInput(new InputFrame(1, 0, true, false)); gateway.pulse(); inputs();
        enter(); recover();
        frames(new InputFrame(1, 0, true, false), 4);
        assertZero(inputs());
    }
    @Test public void directionChangesWithoutNeutralDoNotCountAsFreshMovement() throws Exception {
        gateway.submitInput(new InputFrame(1, 0, false, false)); gateway.pulse(); inputs();
        enter(); recover(); frames(new InputFrame(-1, 1, false, false), 3);
        assertZero(inputs());
        gateway.submitInput(InputFrame.ZERO);
        gateway.submitInput(new InputFrame(-1, 0.5, false, false)); gateway.pulse();
        WireCodec.Message fresh = last(inputs());
        assertEquals(-1, fresh.body.get("moveX").getAsDouble(), 0);
        assertEquals(0.5, fresh.body.get("moveY").getAsDouble(), 0);
    }
    @Test public void releaseAndNewPressAfterRecoveryResumeNormalInputs() throws Exception {
        enter(); frames(new InputFrame(1, 0, true, true), 3); inputs(); recover();
        gateway.submitInput(InputFrame.ZERO);
        gateway.submitInput(new InputFrame(0.5, -0.5, true, true));
        WireCodec.Message fresh = last(inputs());
        assertTrue(WireCodec.bool(fresh.body, "interactHeld"));
        assertEquals(0.5, fresh.body.get("moveX").getAsDouble(), 0);
        assertEquals(1, WireCodec.integer(fresh.body, "dropId"));
    }
    @Test public void releaseDuringSuspectAllowsANewPressAfterRecovery() throws Exception {
        gateway.submitInput(new InputFrame(1, 0, true, true)); inputs();
        enter(); gateway.submitInput(InputFrame.ZERO); recover();
        gateway.submitInput(new InputFrame(-0.5, 0, true, true));
        WireCodec.Message fresh = last(inputs());
        assertTrue(WireCodec.bool(fresh.body, "interactHeld"));
        assertEquals(-0.5, fresh.body.get("moveX").getAsDouble(), 0);
        assertEquals(2, WireCodec.integer(fresh.body, "dropId"));
    }
    @Test public void rePressDuringSuspectCannotBecomeAFreshPressOnRecovery() throws Exception {
        enter(); gateway.submitInput(InputFrame.ZERO);
        gateway.submitInput(new InputFrame(1, 0, true, true)); recover();
        frames(new InputFrame(1, 0, true, true), 3); assertZero(inputs());
    }
    @Test public void movementAndInteractionAreRearmedIndependently() throws Exception {
        gateway.submitInput(new InputFrame(1, 0, true, false)); inputs(); enter(); recover();
        gateway.submitInput(new InputFrame(1, 0, false, false));
        gateway.submitInput(new InputFrame(1, 0, true, false));
        WireCodec.Message interaction = last(inputs());
        assertTrue(WireCodec.bool(interaction.body, "interactHeld"));
        assertEquals(0, interaction.body.get("moveX").getAsDouble(), 0);
        gateway.submitInput(new InputFrame(0, 0, true, false));
        gateway.submitInput(new InputFrame(-1, 0, true, false)); gateway.pulse();
        WireCodec.Message movement = last(inputs());
        assertEquals(-1, movement.body.get("moveX").getAsDouble(), 0);
        assertTrue(WireCodec.bool(movement.body, "interactHeld"));
    }
    @Test public void suspectDropsAreDiscardedWithoutSpendingOrReplayingIds() throws Exception {
        gateway.submitInput(new InputFrame(0, 0, false, true));
        assertEquals(1, WireCodec.integer(last(inputs()).body, "dropId"));
        enter(); frames(new InputFrame(0, 0, false, true), 3); assertTrue(inputs().isEmpty());
        recover(); frames(new InputFrame(0, 0, false, true), 3); assertZero(inputs());
        gateway.submitInput(InputFrame.ZERO);
        gateway.submitInput(new InputFrame(0, 0, false, true));
        assertEquals(2, WireCodec.integer(last(inputs()).body, "dropId"));
        now.addAndGet(50); gateway.pulse();
        for (WireCodec.Message input : inputs()) assertTrue(input.body.get("dropId").isJsonNull());
    }
    @Test public void releasedSuspectDropIsNeverDelayedButNewDropWorks() throws Exception {
        enter(); gateway.submitInput(new InputFrame(0, 0, false, true));
        gateway.submitInput(InputFrame.ZERO); recover(); gateway.pulse(); assertZero(inputs());
        gateway.submitInput(new InputFrame(0, 0, false, true));
        assertEquals(1, WireCodec.integer(last(inputs()).body, "dropId"));
    }
    @Test public void suspectSamplingDoesNotRenewTheInputLease() throws Exception {
        gateway.submitInput(new InputFrame(1, 0, true, false)); inputs();
        // Expire the lease before SUSPECT; recovery must still clear any later outage samples.
        now.set(12_999); gateway.pulse(); assertZero(inputs());
        enter(); frames(new InputFrame(1, 0, true, false), 3); inputs(); recover();
        gateway.pulse(); List<WireCodec.Message> first = inputs(); assertEquals(1, first.size()); assertZero(first);
        now.addAndGet(50); gateway.pulse();
        assertTrue("No repeated input lease extension from suspect samples", inputs().isEmpty());
    }
    @Test public void freshInputAfterRecoveryStillExpiresAt250ms() throws Exception {
        enter(); gateway.submitInput(InputFrame.ZERO); recover();
        gateway.submitInput(new InputFrame(1, 0, true, false));
        assertTrue(WireCodec.bool(last(inputs()).body, "interactHeld"));
        now.addAndGet(249); gateway.pulse();
        assertTrue(WireCodec.bool(last(inputs()).body, "interactHeld"));
        now.addAndGet(1); gateway.pulse();
        List<WireCodec.Message> zero = inputs(); assertEquals(1, zero.size()); assertZero(zero);
        now.addAndGet(50); gateway.pulse(); assertTrue(inputs().isEmpty());
    }
    @Test public void neutralOutageDoesNotBlockNewControlsOnRecovery() throws Exception {
        enter(); frames(InputFrame.ZERO, 3); recover();
        gateway.submitInput(new InputFrame(1, 0, true, true));
        WireCodec.Message fresh = last(inputs());
        assertEquals(1, fresh.body.get("moveX").getAsDouble(), 0);
        assertTrue(WireCodec.bool(fresh.body, "interactHeld"));
        assertEquals(1, WireCodec.integer(fresh.body, "dropId"));
    }
    @Test public void newMatchClearsPreviousRecoveryGatesAndInputIds() throws Exception {
        enter(); gateway.submitInput(new InputFrame(1, 0, true, true)); recover();
        room(RoomPhase.FINISHED, "m-1"); room(RoomPhase.LOBBY, null);
        room(RoomPhase.COUNTDOWN, null); room(RoomPhase.RUNNING, "m-2");
        gateway.submitInput(new InputFrame(1, 0, true, true));
        WireCodec.Message fresh = last(inputs());
        assertEquals("m-2", fresh.matchId); assertEquals(1, WireCodec.integer(fresh.body, "inputSeq"));
        assertEquals(1, WireCodec.integer(fresh.body, "dropId"));
    }

    private void enter() throws Exception {
        now.set(13_000); gateway.pulse(); assertEquals("SUSPECT", gateway.getConnectionState());
    }
    private void recover() throws Exception {
        now.incrementAndGet(); room(RoomPhase.RUNNING, "m-1");
        assertEquals("CONNECTED", gateway.getConnectionState());
    }
    private void frames(InputFrame frame, int count) {
        for (int i = 0; i < count; i++) {
            now.addAndGet(16); gateway.submitInput(frame); gateway.pulse();
        }
    }
    /** A control request sent after INPUTs is an outbound socket barrier; no reply can recover SUSPECT. */
    private List<WireCodec.Message> inputs() throws Exception {
        gateway.setReady(false);
        List<WireCodec.Message> inputs = new ArrayList<>();
        for (;;) {
            WireCodec.Message message = peer.pollMessage(3, TimeUnit.SECONDS); assertNotNull("Outbound barrier", message);
            if ("SET_READY".equals(message.type)) return inputs;
            if ("INPUT".equals(message.type)) inputs.add(message);
        }
    }
    private static WireCodec.Message last(List<WireCodec.Message> inputs) {
        assertFalse("Expected an INPUT", inputs.isEmpty()); return inputs.get(inputs.size() - 1);
    }
    private static void assertZero(List<WireCodec.Message> inputs) {
        for (WireCodec.Message input : inputs) {
            assertEquals(0, input.body.get("moveX").getAsDouble(), 0);
            assertEquals(0, input.body.get("moveY").getAsDouble(), 0);
            assertFalse(WireCodec.bool(input.body, "interactHeld"));
            assertTrue("No delayed drop", input.body.get("dropId").isJsonNull());
        }
    }
    private void room(RoomPhase phase, String match) throws Exception {
        boolean ready = phase != RoomPhase.LOBBY;
        RoomState state = new RoomState("TEST", revision++, phase, "test-room", "map-hash-v1", "config-hash-v1", match,
                phase == RoomPhase.COUNTDOWN ? 3000 : 0, Arrays.asList(
                new RoomPlayer("p-1", Role.THIEF, ready, "CONNECTED", false),
                new RoomPlayer("p-2", Role.POLICE, ready, "CONNECTED", false)));
        peer.conn.send(codec.encode("ROOM_STATE", ++seq, null, null, state));
        assertNotNull("Room receive barrier", rooms.poll(3, TimeUnit.SECONDS));
    }
}
