package gg.hcfrevival.revival.auth.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** The reconciler's decisions and the in-flight correction (design §6.2, §15.3, §15.5). */
@RevivalTest
class ReconcilerTest {

    private static int judge(final Reconciler reconciler, final double distance) {
        return reconciler.judge(distance, 0.0, 1.0E-3, 0.01, 0.95, 5, 0L);
    }

    @Test
    void exactWithinAndCorrect() {
        final Reconciler reconciler = new Reconciler();
        assertEquals(Reconciler.EXACT, judge(reconciler, 0.0));
        assertEquals(Reconciler.WITHIN, judge(reconciler, 5.0E-4), "under the threshold goes into the budget");
        assertEquals(Reconciler.CORRECT, judge(reconciler, 0.5));
    }

    @Test
    void anInFlightCorrectionIsNotJudgedAndCarriesItsDelta() {
        final Reconciler reconciler = new Reconciler();
        final int id = reconciler.send(-0.5, 0.25, 0.0);
        assertTrue(Reconciler.ours(id));
        assertTrue(reconciler.inFlight());
        assertEquals(Reconciler.IN_FLIGHT, judge(reconciler, 0.5));
        assertEquals(0.5, reconciler.inFlightDelta(0));
        assertEquals(0.25, reconciler.inFlightDelta(1));
        assertEquals(0.0, reconciler.inFlightDelta(2));
        assertTrue(reconciler.accepted(id));
        assertFalse(reconciler.inFlight());
        assertEquals(0.0, reconciler.inFlightDelta(0), "nothing in flight, no Δ");
    }

    @Test
    void theAttackersEyeStaysWithinTheDelta() {
        // The client is 0.5 behind on x while a 0.5 correction is in flight: its own eye counts
        assertEquals(-0.5, Reconciler.bounded(-0.5, 0.5));
        // A claim farther off than the correction moves it is clamped to the Δ (no reach from a spoofed claim)
        assertEquals(0.5, Reconciler.bounded(3.0, 0.5));
        assertEquals(-0.5, Reconciler.bounded(-3.0, 0.5));
        // An axis the correction doesn't move stays on the truth
        assertEquals(0.0, Reconciler.bounded(0.2, 0.0));
    }

    @Test
    void anAppliedCorrectionWithoutItsAcceptIsAViolation() {
        final Reconciler reconciler = new Reconciler();
        final int id = reconciler.send(1.0, 0.0, 0.0);
        reconciler.applied(id);
        reconciler.afterWindow();
        assertEquals(1, reconciler.violations());
        assertFalse(reconciler.inFlight());
    }

    @Test
    void anAcceptForACorrectionNeverSentIsForged() {
        final Reconciler reconciler = new Reconciler();
        final int id = reconciler.send(0.5, 0.0, 0.0);
        reconciler.reset(); // a server teleport dropped it while it was on its way
        assertFalse(reconciler.accepted(id));
        assertTrue(reconciler.sentLately(id), "its accept is stale, not forged");
        assertFalse(reconciler.sentLately(Reconciler.ID_MAX - (1 << 25)), "an id in our band we never sent is forged");
    }
}
