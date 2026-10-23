package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** Torture F4: TickEnd arrivals off the wire, taken by the main thread in the same order. */
@RevivalTest
class ArrivalStampsTest {

    @Test
    void stampsAreTakenInOrderAndNeverLaterThanNow() {
        final ArrivalStamps stamps = new ArrivalStamps();
        assertEquals(500L, stamps.take(500L), "nothing stamped: the main thread's own time");
        stamps.stamp(100L);
        stamps.stamp(150L);
        stamps.stamp(200L);
        assertEquals(100L, stamps.take(1000L));
        assertEquals(150L, stamps.take(1000L));
        assertEquals(180L, stamps.take(180L), "never after the main thread's now");
        assertEquals(1000L, stamps.take(1000L));
    }

    @Test
    void aFullRingDropsTheOldest() {
        final ArrivalStamps stamps = new ArrivalStamps();
        for (int i = 0; i < ArrivalStamps.RING + 3; i++) {
            stamps.stamp(i);
        }
        assertEquals(3L, stamps.take(Long.MAX_VALUE), "a later arrival than its own: less credit, never more");
    }

    @Test
    void windowsWaitingForTheMainThreadWidenTheCredit() {
        // Torture r80j10: a 725 ms server hitch left 14 TickEnds queued; the hitched tick's flush, stamped 10 past the
        // last window handled, was due as soon as they ran. A stall (nothing arriving) keeps X2-02's bound
        final ArrivalStamps stamps = new ArrivalStamps();
        assertEquals(10, stamps.creditBound(10), "nothing waiting: max-banked-ticks");
        for (int i = 0; i < 14; i++) {
            stamps.stamp(i);
        }
        assertEquals(24, stamps.creditBound(10), "fourteen sent and waiting");
        stamps.take(Long.MAX_VALUE);
        assertEquals(23, stamps.creditBound(10), "one handled");
    }

    @Test
    void aBurstWaitingBehindTheWindowRunningIsCounted() {
        // NW-1: a 2 s freeze's ten catch-up ticks arrive in one read; while the first runs, nine are only off the wire
        final ArrivalStamps stamps = new ArrivalStamps();
        assertEquals(0, stamps.pending());
        for (int i = 0; i < 10; i++) {
            stamps.stamp(i);
        }
        stamps.take(Long.MAX_VALUE);
        assertEquals(9, stamps.pending(), "the burst's first window is handled");
        for (int i = 0; i < 9; i++) {
            stamps.take(Long.MAX_VALUE);
        }
        assertEquals(0, stamps.pending());
    }

    @Test
    void installsAgainAfterAnUninstall() {
        // Review PT2-01 / R2C-01: authority off then on again re-adds the same handler; a non-sharable one threw
        final io.netty.channel.embedded.EmbeddedChannel channel = new io.netty.channel.embedded.EmbeddedChannel();
        channel.pipeline().addLast(net.minecraft.network.HandlerNames.PACKET_HANDLER, new io.netty.channel.ChannelInboundHandlerAdapter());
        final ArrivalStamps stamps = new ArrivalStamps();
        for (int round = 0; round < 3; round++) {
            org.junit.jupiter.api.Assertions.assertTrue(stamps.install(channel), "install " + round);
            channel.writeInbound(net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.INSTANCE);
            org.junit.jupiter.api.Assertions.assertTrue(stamps.take(Long.MAX_VALUE) < Long.MAX_VALUE, "stamped through the handler, round " + round);
            stamps.uninstall();
            stamps.uninstall(); // twice: a no-op
            org.junit.jupiter.api.Assertions.assertNull(channel.pipeline().get(ArrivalStamps.HANDLER));
        }
        channel.finishAndReleaseAll();
    }
}
