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
        assertTrue(stream.recordAck(0, 0, false, 0));
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
        assertFalse(stream.recordAck(0, 0, false, 0), "a pong after a keybind packet");
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
        stream.recordAck(0, 1, true, 0);
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
        stream.recordAck(0, 1, true, 0);
        stream.recordAck(1, 1, false, 0);
        stream.record(new ServerboundMovePlayerPacket.Rot(90.0F, 0.0F, false, false));
        final ClientStream.Window window = stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);
        assertEquals(ClientStream.PACKET, window.kind(2));
    }

    @Test
    void aCorrectionsAcceptMustFollowItsPong() {
        final ClientStream stream = new ClientStream();
        stream.recordAck(0, 1, false, -1_500_000_000);
        assertTrue(stream.recordAccept(new ServerboundAcceptTeleportationPacket(-1_500_000_000, 0.0, 0.0, 0.0, 0.0F, 0.0F)));
        stream.recordAck(1, 2, false, -1_500_000_001);
        stream.recordAck(2, 2, false, 0);
        assertTrue(stream.recordAccept(new ServerboundAcceptTeleportationPacket(-1_500_000_001, 0.0, 0.0, 0.0, 0.0F, 0.0F)),
            "late but still in the drain");
        stream.recordAck(2, 3, false, -1_500_000_002);
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
}
