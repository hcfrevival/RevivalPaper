package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.dreeam.leaf.config.modules.opt.ReduceUselessPackets;
import org.junit.jupiter.api.Test;

/**
 * pvp-issues SV-3: Leaf's packet reductions drop the velocity and move packets authority's knocks and paths count on, so
 * authority stays off with either one on (as with the async tracker and parallel world ticking).
 */
@RevivalTest
class LeafRefusalTest {

    @Test
    void packetReductionsKeepAuthorityOff() {
        final boolean motion = ReduceUselessPackets.filterClientboundSetEntityMotionPacket;
        final boolean move = ReduceUselessPackets.reduceUselessEntityMovePackets;
        try {
            ReduceUselessPackets.filterClientboundSetEntityMotionPacket = false;
            ReduceUselessPackets.reduceUselessEntityMovePackets = false;
            assertNull(Authority.refusal(), "Leaf's defaults are fine");

            ReduceUselessPackets.filterClientboundSetEntityMotionPacket = true;
            final String motionRefusal = Authority.refusal();
            assertTrue(motionRefusal != null && motionRefusal.contains("reduce-entity-motion-packets"), String.valueOf(motionRefusal));

            ReduceUselessPackets.filterClientboundSetEntityMotionPacket = false;
            ReduceUselessPackets.reduceUselessEntityMovePackets = true;
            final String moveRefusal = Authority.refusal();
            assertTrue(moveRefusal != null && moveRefusal.contains("reduce-entity-move-packets"), String.valueOf(moveRefusal));
        } finally {
            ReduceUselessPackets.filterClientboundSetEntityMotionPacket = motion;
            ReduceUselessPackets.reduceUselessEntityMovePackets = move;
        }
    }
}
