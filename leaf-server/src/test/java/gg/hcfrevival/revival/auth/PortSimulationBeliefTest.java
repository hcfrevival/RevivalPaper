package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.net.OutboundClassifier;
import gg.hcfrevival.revival.auth.net.Transactions;
import gg.hcfrevival.revival.auth.net.Wire;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * pvp-issues NW-1: the belief (the client's own state) takes the knocks the client applies, and the gap's velocity says
 * whether a knock on its way still moves the gap when it lands.
 */
@RevivalTest
@RevivalRegistries
class PortSimulationBeliefTest {

    private static PortSimulation tracking() {
        final PortSimulation sim = new PortSimulation(null); // the owner is read only for item use
        sim.trackBelief(true);
        sim.belief(true, 0.0, 0.0, 0.0); // re-based on the truth
        return sim;
    }

    @Test
    void theBeliefTakesTheKnocksTheClientApplies() {
        final PortSimulation sim = tracking();
        sim.setVelocity(0.4, 0.36, 0.0); // the truth takes a knock in the drain
        assertEquals(0.4 * 0.4 + 0.36 * 0.36, sim.velocityGap(null) * sim.velocityGap(null), 1.0E-12, "a belief without it");
        sim.beliefVelocity(0.4, 0.36, 0.0, false); // and the client, in the same drain
        assertEquals(0.0, sim.velocityGap(null));
        sim.addVelocity(0.1, 0.0, 0.0);
        sim.beliefVelocity(0.1, 0.0, 0.0, true); // an explosion adds to both
        assertEquals(0.0, sim.velocityGap(null));
    }

    @Test
    void aKnockOnItsWaySettlesTheGapUnlessItIsForced() {
        final PortSimulation sim = tracking();
        sim.setVelocity(0.002, 0.0, 0.0); // the truth: a gap still drifting 2 mm a tick
        final ClientboundSetEntityMotionPacket knock = new ClientboundSetEntityMotionPacket(1, new Vec3(0.3, 0.36, -0.2));
        final Transactions.Acked knocks = new Transactions.Acked();
        knocks.add(knock, OutboundClassifier.SELF_MOTION, false, 0L);
        assertEquals(0.0, sim.velocityGap(knocks), "it replaces both velocities where it lands: the gap is a position only");

        final Transactions.Acked forced = new Transactions.Acked();
        forced.add(knock, OutboundClassifier.SELF_MOTION, true, 0L); // the truth took it at its deadline already
        final double[] decoded = new double[3];
        Wire.lpVec3(0.3, 0.36, -0.2, decoded);
        final double dx = 0.002 - decoded[0];
        assertEquals(Math.sqrt(dx * dx + decoded[1] * decoded[1] + decoded[2] * decoded[2]), sim.velocityGap(forced), 1.0E-12,
            "only the client still takes it");
    }

    @Test
    void noBeliefNoGap() {
        final PortSimulation sim = new PortSimulation(null);
        assertTrue(Double.isNaN(sim.velocityGap(null)));
        sim.beliefVelocity(1.0, 0.0, 0.0, false); // nothing to write to
        sim.trackBelief(true);
        assertTrue(Double.isNaN(sim.velocityGap(null)), "tracked, but no claim has set it yet");
    }
}
