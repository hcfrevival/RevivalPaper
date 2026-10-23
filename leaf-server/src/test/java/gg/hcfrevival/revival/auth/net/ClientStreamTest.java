package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Design §3.1 and §15.4, report 02 §5: windows and the input grammar of a vanilla client tick. */
@RevivalTest
class ClientStreamTest {

    private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

    @Test
    void aVanillaTickIsOneLegalWindow() {
        final ClientStream stream = new ClientStream();
        assertTrue(stream.recordAck(0, 0, 0, 0));
        assertTrue(stream.record(new ServerboundSetCarriedItemPacket(2)));
        assertTrue(stream.record(new ServerboundAttackPacket(9)));
        assertTrue(stream.record(ServerboundPunchPacket.INSTANCE));
        assertTrue(stream.record(new ServerboundPlayerInputPacket(FORWARD)));
        assertTrue(stream.record(new ServerboundMovePlayerPacket.Pos(new Vec3(1.0, 2.0, 3.0), true, false)));
        final ClientStream.Window window = stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);
        assertEquals(1, window.tick());
        assertEquals(0, window.violations());
        assertEquals(7, window.items(), "ack, four packets, move, tick end");
        assertEquals(ClientStream.ACK, window.kind(0));
        assertEquals(ClientStream.TICK_END, window.a(window.items() - 1));
        assertSame(window, stream.poll());
        assertEquals(2, stream.currentTick());
    }

    @Test
    void sendChangesPacketsComeInOrderAndOnce() {
        final ClientStream stream = new ClientStream();
        assertTrue(stream.record(new ServerboundMovePlayerPacket.StatusOnly(true, false)));
        assertFalse(stream.record(new ServerboundPlayerInputPacket(FORWARD)), "input after the move claim");
        assertFalse(stream.record(new ServerboundMovePlayerPacket.StatusOnly(false, false)), "a second move claim");
        assertFalse(stream.record(new ServerboundAttackPacket(1)), "a keybind after the move claim");
        assertEquals(3, stream.violations());
    }

    @Test
    void pongsAndAcceptsBelongToTheDrain() {
        final ClientStream stream = new ClientStream();
        stream.record(new ServerboundAttackPacket(1));
        assertFalse(stream.recordAck(0, 0, 0, 0), "a pong after a keybind packet");
        assertFalse(stream.recordAccept(new ServerboundAcceptTeleportationPacket(3, 0.0, 0.0, 0.0, 0.0F, 0.0F)));
        assertEquals(2, stream.violations());
    }

    @Test
    void useAfterReleaseInOneWindowIsImpossible() {
        final ClientStream stream = new ClientStream();
        stream.record(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        assertFalse(stream.record(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 0.0F, 0.0F)));
    }

    @Test
    void theRotationReplyIsTheMoveRightAfterItsPong() {
        final ClientStream stream = new ClientStream();
        stream.recordAck(0, 1, 1, 0);
        assertTrue(stream.record(new ServerboundMovePlayerPacket.Rot(90.0F, 0.0F, false, false)));
        assertTrue(stream.record(new ServerboundMovePlayerPacket.PosRot(new Vec3(1.0, 2.0, 3.0), 90.0F, 0.0F, true, false)),
            "the tick's own move is still allowed after the reply");
        final ClientStream.Window window = stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);
        assertEquals(ClientStream.ROTATION_REPLY, window.kind(1));
        assertEquals(ClientStream.PACKET, window.kind(2));
        assertEquals(0, window.violations());
    }

    @Test
    void aRotationPacketNotRightAfterThePongIsAClaim() {
        final ClientStream stream = new ClientStream();
        stream.recordAck(0, 1, 1, 0);
        stream.recordAck(1, 1, 0, 0);
        stream.record(new ServerboundMovePlayerPacket.Rot(90.0F, 0.0F, false, false));
        final ClientStream.Window window = stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);
        assertEquals(ClientStream.PACKET, window.kind(2));
    }

    @Test
    void aCorrectionsAcceptMustFollowItsPong() {
        final ClientStream stream = new ClientStream();
        stream.recordAck(0, 1, 0, -1_500_000_000);
        assertTrue(stream.recordAccept(new ServerboundAcceptTeleportationPacket(-1_500_000_000, 0.0, 0.0, 0.0, 0.0F, 0.0F)));
        stream.recordAck(1, 2, 0, -1_500_000_001);
        stream.recordAck(2, 2, 0, 0);
        assertTrue(stream.recordAccept(new ServerboundAcceptTeleportationPacket(-1_500_000_001, 0.0, 0.0, 0.0, 0.0F, 0.0F)),
            "late but still in the drain");
        stream.recordAck(2, 3, 0, -1_500_000_002);
        assertFalse(stream.recordAccept(new ServerboundAcceptTeleportationPacket(-7, 0.0, 0.0, 0.0, 0.0F, 0.0F)), "the wrong accept next");
    }

    @Test
    void inputIsStickyAndThePreviousTickIsKept() {
        final ClientStream stream = new ClientStream();
        assertEquals(FORWARD, stream.advanceInput(FORWARD));
        assertEquals(FORWARD, stream.advanceInput(null), "no PlayerInput: the keys did not change");
        assertEquals(FORWARD, stream.previousInput());
        assertEquals(Input.EMPTY, stream.advanceInput(Input.EMPTY));
        assertEquals(FORWARD, stream.previousInput());
    }

    @Test
    void thePositionReminderIsDueEveryTwentyTicks() {
        final ClientStream stream = new ClientStream();
        stream.resetClient(false);
        assertTrue(stream.lastSentKnown());
        assertEquals(0.0, stream.lastSentX(), "a new LocalPlayer starts at xLast = 0");
        stream.advancePosition(true, 5.0, 64.0, 5.0);
        for (int tick = 1; tick < 19; tick++) {
            stream.advancePosition(false, 0.0, 0.0, 0.0);
            assertFalse(stream.reminderDue(), "tick " + tick);
        }
        stream.advancePosition(false, 0.0, 0.0, 0.0);
        assertTrue(stream.reminderDue());
    }

    @Test
    void repliesAfterALongStallDontCountTowardTheTicksPacketCap() {
        // Review 3 #4: a client back from a 30 s stall answers its 600 queued pings in one frame, then runs its tick
        final ClientStream stream = new ClientStream();
        for (int i = 0; i < 600; i++) {
            assertTrue(stream.recordAck(0, 0, 0, 0));
        }
        assertEquals(ClientStream.RECORDED, stream.recordChecked(new ServerboundMovePlayerPacket.Pos(new Vec3(1.0, 2.0, 3.0), true, false)));
        assertFalse(stream.overflowing(), "replies have their own cap");
    }

    @Test
    void aSecondMoveClaimAndAnOverflowAreProtocolViolations() {
        final ClientStream stream = new ClientStream();
        assertEquals(ClientStream.RECORDED, stream.recordChecked(new ServerboundMovePlayerPacket.StatusOnly(true, false)));
        assertEquals(ClientStream.SECOND_CLAIM, stream.recordChecked(new ServerboundMovePlayerPacket.StatusOnly(true, false)));
        stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);
        // A client that never ends its tick: the window stops growing at the cap
        int overflow = 0;
        for (int i = 0; i < ClientStream.MAX_ITEMS + 10; i++) {
            if (stream.recordChecked(new ServerboundSetCarriedItemPacket(i % 9)) == ClientStream.OVERFLOW) {
                overflow++;
            }
        }
        assertEquals(10, overflow);
        assertTrue(stream.openWindowAgeNanos(System.nanoTime() + 1_000_000L) > 0L, "the open window has an age");
    }

    @Test
    void everyRotationOfABundleIsAnsweredAfterItsAccepts() {
        // Review rotation-reply-adjacency-kick: [ping, position, rotation] -> Pong, Accept, Rot; two rotations -> two Rots
        final ClientStream stream = new ClientStream();
        stream.recordAck(0, 3, 2, 0);
        assertTrue(stream.recordAccept(new ServerboundAcceptTeleportationPacket(5, 0.0, 0.0, 0.0, 0.0F, 0.0F)));
        assertTrue(stream.record(new ServerboundMovePlayerPacket.Rot(90.0F, 0.0F, false, false)));
        assertTrue(stream.record(new ServerboundMovePlayerPacket.Rot(45.0F, 0.0F, false, false)));
        assertTrue(stream.record(new ServerboundMovePlayerPacket.PosRot(new Vec3(1.0, 2.0, 3.0), 45.0F, 0.0F, true, false)),
            "the tick's own move is not a second claim");
        final ClientStream.Window window = stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);
        assertEquals(ClientStream.ROTATION_REPLY, window.kind(2));
        assertEquals(ClientStream.ROTATION_REPLY, window.kind(3));
        assertEquals(ClientStream.PACKET, window.kind(4));
        assertEquals(0, window.violations());
    }

    @Test
    void theAbortAfterAnAcceptIsADrainReply() {
        // Review abort-destroy-after-accept-kick: handleMovePlayer accepts, then stopDestroyBlock sends ABORT
        final ClientStream stream = new ClientStream();
        stream.recordAck(0, 1, 0, 0);
        stream.recordAccept(new ServerboundAcceptTeleportationPacket(5, 0.0, 0.0, 0.0, 0.0F, 0.0F));
        assertTrue(stream.record(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, BlockPos.ZERO, Direction.DOWN)));
        assertTrue(stream.recordAck(1, 1, 0, 0), "another pong of the same drain stays legal");
        assertTrue(stream.record(new ServerboundAttackPacket(7)));
        final ClientStream.Window window = stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);
        assertEquals(ClientStream.DRAIN_ABORT, window.kind(2));
        assertEquals(0, window.violations());
        // Not after an accept: a keybind like any other
        stream.recordAck(1, 1, 0, 0);
        stream.record(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, BlockPos.ZERO, Direction.DOWN));
        assertFalse(stream.recordAck(1, 1, 0, 0));
    }

    private static final long MS = 1_000_000L;

    @Test
    void catchUpTicksAfterAStallKeepTheirCredit() {
        // Review timer-backlog-permanent: the drain's pongs come right before the frame's catch-up ticks, which the
        // stall is owed; with the old floor (newest ack) only about (rtt + drift) / 50 of them ran and the rest queued
        final ClientStream stream = new ClientStream();
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        final long rtt = 30 * MS;
        clock.start(start, 50 * MS, 10);
        long now = start;
        for (int tick = 0; tick < 40; tick++) {
            now += 50 * MS;
            stream.notePong(now - 2 * MS, now - rtt);
            stream.close(ServerboundClientTickEndPacket.INSTANCE, now);
            assertTrue(clock.tryRun(now, stream.poll().timerAckNanos(), 50 * MS, 120 * MS, 10), "steady tick " + tick);
        }
        // 400 ms frame stall: no pongs, no ticks. Then the drain answers the 8 queued pings and 8 catch-up ticks follow
        now += 400 * MS;
        for (int ping = 7; ping >= 0; ping--) {
            stream.notePong(now, now - rtt / 2 - ping * 50 * MS);
        }
        int ran = 0;
        for (int tick = 0; tick < 8; tick++) {
            now += 3 * MS; // each catch-up tick takes the client 3 ms
            stream.close(ServerboundClientTickEndPacket.INSTANCE, now);
            if (clock.tryRun(now, stream.poll().timerAckNanos(), 50 * MS, 120 * MS, 10)) {
                ran++;
            }
        }
        assertEquals(8, ran, "every catch-up tick runs at once: no queue is left behind");
    }

    @Test
    void answeringWhileSendingNoTicksStillForfeitsTheTime() {
        final ClientStream stream = new ClientStream();
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        clock.start(start, 50 * MS, 10);
        long now = start;
        for (int tick = 0; tick < 20; tick++) {
            now += 50 * MS;
            stream.notePong(now - 2 * MS, now - 30 * MS);
            stream.close(ServerboundClientTickEndPacket.INSTANCE, now);
            assertTrue(clock.tryRun(now, stream.poll().timerAckNanos(), 50 * MS, 120 * MS, 10));
        }
        // 500 ms of pongs and no TickEnd (banking while visibly alive), then a burst of 10 ticks
        for (int i = 0; i < 10; i++) {
            now += 50 * MS;
            stream.notePong(now, now - 30 * MS);
        }
        int ran = 0;
        for (int tick = 0; tick < 10; tick++) {
            now += MS;
            stream.close(ServerboundClientTickEndPacket.INSTANCE, now);
            if (clock.tryRun(now, stream.peek().timerAckNanos(), 50 * MS, 120 * MS, 10)) {
                stream.poll();
                ran++;
            }
        }
        assertTrue(ran <= 6, "pongs from well before the burst bind it: about (settle + rtt + drift) / 50 ticks of credit, ran " + ran);
    }

    @Test
    void aContainerCloseFromAiStepIsInOrder() {
        // client-compat N8: standing in a portal with a screen open, LocalPlayer.aiStep closes it after the keybinds
        final ClientStream stream = new ClientStream();
        assertTrue(stream.record(new ServerboundAttackPacket(7)));
        assertTrue(stream.record(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0)), "the aiStep close");
        assertTrue(stream.record(new ServerboundPlayerInputPacket(Input.EMPTY)));
        assertTrue(stream.record(new ServerboundMovePlayerPacket.PosRot(new Vec3(1.0, 2.0, 3.0), 0.0F, 0.0F, true, false)));
        final ClientStream.Window window = stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);
        assertEquals(0, window.violations());
        assertEquals(ClientStream.AISTEP, window.a(1));
        // After the input it is out of place again
        stream.record(new ServerboundPlayerInputPacket(Input.EMPTY));
        assertFalse(stream.record(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0)));
    }

    @Test
    void aSecondInputIsCountedNotASecondClaim() {
        // Design §15.8: a frame-time key press on a modded client; only a second move claim stays a protocol violation
        final ClientStream stream = new ClientStream();
        assertEquals(ClientStream.RECORDED, stream.recordChecked(new ServerboundPlayerInputPacket(Input.EMPTY)));
        assertEquals(ClientStream.EXTRA_INPUT, stream.recordChecked(new ServerboundPlayerInputPacket(FORWARD)));
    }
    @Test
    void aCatchUpBurstIsNotPaced() {
        // Torture F3: windows of a burst after a stall come together with no pong; ack deadlines don't count them
        final long ms = 1_000_000L;
        final ClientStream stream = new ClientStream();
        stream.setPacing(15 * ms, 10);
        long t = 1_000_000 * ms;
        for (int i = 0; i < 5; i++) {
            t += 50 * ms;
            assertTrue(stream.close(ServerboundClientTickEndPacket.INSTANCE, t, t).paced(), "on time");
        }
        assertEquals(5, stream.pacedTicks());
        t += 1000 * ms; // a 1 s stall: 20 windows arrive within a few ms
        int paced = 0;
        for (int i = 0; i < 20; i++) {
            if (stream.close(ServerboundClientTickEndPacket.INSTANCE, t + i * ms / 2, t + i * ms / 2).paced()) {
                paced++;
            }
        }
        assertEquals(2, paced, "the burst's first window, and one more after the vanilla frame's 10 catch-up ticks");
        // A window whose drain answered a ping is the client's own pace, burst or not
        stream.notePong(t, t - 80 * ms);
        assertTrue(stream.close(ServerboundClientTickEndPacket.INSTANCE, t + 11 * ms, t + 11 * ms).paced());
        final ClientStream.Window next = stream.close(ServerboundClientTickEndPacket.INSTANCE, t + 60 * ms, t + 60 * ms);
        assertTrue(next.paced());
        assertEquals(stream.pacedTicks(), next.pacedTick(), "a paced window carries the count it made");
    }
}
