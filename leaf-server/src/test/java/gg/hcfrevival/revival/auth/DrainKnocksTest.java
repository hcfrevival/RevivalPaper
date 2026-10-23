package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.net.ClientStream;
import gg.hcfrevival.revival.auth.net.OutboundClassifier;
import gg.hcfrevival.revival.auth.net.Transactions;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * pvp-issues NW-1: a correction's forecast takes the knocks of the drain it is decided in that the truth hasn't applied
 * yet: all of them for a deferred correction (decided before the drain runs), those after the accept for the backstop.
 */
@RevivalTest
class DrainKnocksTest {

    private static ClientboundSetEntityMotionPacket knock(final double y) {
        return new ClientboundSetEntityMotionPacket(7, new Vec3(0.1, y, 0.0));
    }

    @Test
    void theDrainsKnocksInOrderWithTheLandedOnesForced() {
        final ClientStream stream = new ClientStream();
        final Transactions.Acked acked = stream.currentAcked();
        final ClientboundSetEntityMotionPacket before = knock(0.36);
        final ClientboundSetEntityMotionPacket explode = knock(0.5); // kinds decide, not packet classes
        final ClientboundSetEntityMotionPacket landed = knock(0.4);
        acked.add(before, OutboundClassifier.SELF_MOTION, false, 1L);
        acked.add(knock(0.0), OutboundClassifier.REMOTE_PLAYER, false, 1L);
        assertTrue(stream.recordAck(0, 2, 0, 0));
        acked.add(knock(0.0), OutboundClassifier.SELF_POSITION, false, 2L); // the correction
        assertTrue(stream.recordAck(2, 3, 0, -1_500_000_000));
        assertTrue(stream.recordAccept(new ServerboundAcceptTeleportationPacket(-1_500_000_000, 0.0, 0.0, 0.0, 0.0F, 0.0F)));
        acked.add(explode, OutboundClassifier.EXPLODE, false, 3L);
        acked.add(landed, OutboundClassifier.SELF_MOTION | OutboundClassifier.LANDED, false, 4L); // forced, landing now
        assertTrue(stream.recordAck(3, 5, 0, 0));
        final ClientStream.Window window = stream.close(ServerboundClientTickEndPacket.INSTANCE, 0L);

        final Transactions.Acked all = new Transactions.Acked();
        AuthPlayer.drainKnocks(window, 0, all);
        assertEquals(3, all.size(), "a deferred correction: every knock of the drain, not the other entries");
        assertSame(before, all.packet(0));
        assertSame(explode, all.packet(1));
        assertEquals(OutboundClassifier.EXPLODE, all.kind(1));
        assertSame(landed, all.packet(2));
        assertEquals(OutboundClassifier.SELF_MOTION, all.kind(2));
        assertFalse(all.upperBound(0), "the truth takes it in this drain too");
        assertTrue(all.upperBound(2), "forced: the truth has it already");

        int accept = -1;
        for (int i = 0; i < window.items(); i++) {
            if (window.kind(i) == ClientStream.ACCEPT) {
                accept = i;
            }
        }
        final Transactions.Acked after = new Transactions.Acked();
        AuthPlayer.drainKnocks(window, accept + 1, after);
        assertEquals(2, after.size(), "the backstop: the knock before the accept is in truth and belief already");
        assertSame(explode, after.packet(0));
        assertSame(landed, after.packet(1));
    }
}
