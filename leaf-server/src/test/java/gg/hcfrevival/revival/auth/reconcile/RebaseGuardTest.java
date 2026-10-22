package gg.hcfrevival.revival.auth.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** §15.5: adopted drift per axis is capped per window of ticks. */
@RevivalTest
class RebaseGuardTest {

    private static final double CAP = 1.0E-9;
    private static final int WINDOW = 20;

    @Test
    void aSteadyCreepIsStoppedAtTheCap() {
        final RebaseGuard guard = new RebaseGuard();
        int admitted = 0;
        for (int tick = 0; tick < WINDOW; tick++) {
            if (guard.admit(tick, 0.0, -1.0E-10, 0.0, CAP, WINDOW)) {
                admitted++;
            }
        }
        assertEquals(10, admitted, "ten 1e-10 steps down fill a 1e-9 cap");
        assertEquals(10, guard.refused());
    }

    @Test
    void noiseThatGoesBothWaysIsAlwaysAdopted() {
        final RebaseGuard guard = new RebaseGuard();
        for (int tick = 0; tick < 200; tick++) {
            assertTrue(guard.admit(tick, (tick & 1) == 0 ? 1.0E-10 : -1.0E-10, 0.0, 0.0, CAP, WINDOW), "tick " + tick);
        }
        assertEquals(0, guard.refused());
    }

    @Test
    void aNewWindowStartsFromZero() {
        final RebaseGuard guard = new RebaseGuard();
        assertTrue(guard.admit(0, 0.0, 0.0, CAP, CAP, WINDOW));
        assertFalse(guard.admit(19, 0.0, 0.0, 1.0E-12, CAP, WINDOW));
        assertTrue(guard.admit(20, 0.0, 0.0, CAP, CAP, WINDOW));
    }

    @Test
    void resetForgetsTheWindow() {
        final RebaseGuard guard = new RebaseGuard();
        assertTrue(guard.admit(5, CAP, 0.0, 0.0, CAP, WINDOW));
        guard.reset();
        assertTrue(guard.admit(6, CAP, 0.0, 0.0, CAP, WINDOW));
    }
}
