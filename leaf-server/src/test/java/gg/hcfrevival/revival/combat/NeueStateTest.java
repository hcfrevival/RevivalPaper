package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.CombatRules;
import org.junit.jupiter.api.Test;

/**
 * The parry, riposte and stagger state machines (docs/revival/neue-combat.md §8), from Combo's {@code Combat.Parried},
 * {@code PlayerSim.Parries} and {@code PlayerSim.Stagger}, with RevivalPaper's two clocks: client ticks under authority,
 * server ticks outside it (§11).
 */
@RevivalTest
class NeueStateTest {

    private static final CombatRules NEUE = CombatRules.NEUE;
    private static final int JITTER = 2;

    // A fully rested first block up at client tick 100, server tick 1000
    private static NeueState blocking() {
        final NeueState state = new NeueState();
        state.blockStarted(100L, 1000L, true, NEUE);
        return state;
    }

    @Test
    void hitsInTheFirstThreeTicksOfAFreshBlockAreParried() {
        // PlayerSim.Parries: BlockTicks < ParryTicks, BlockTicks 0 on the block's first tick
        for (int age = 0; age <= 4; age++) {
            assertEquals(age < 3, blocking().parries(100L + age, 1000L + age, JITTER), "block age " + age);
        }
    }

    @Test
    void oneParryPerBlockAndAFreshBlockRearms() {
        final NeueState state = blocking();
        assertTrue(state.parries(101L, 1001L, JITTER));
        state.parried(1001L, NEUE.riposteTicks());
        assertTrue(state.parryUsed());
        assertFalse(state.mayParry());
        assertFalse(state.parries(101L, 1001L, JITTER)); // the next hit on this block is a plain 1.7 block
        state.blockEnded(103L, true);
        state.blockStarted(123L, 1023L, true, NEUE); // rested 20 ticks
        assertFalse(state.parryUsed());
        assertTrue(state.parries(123L, 1023L, JITTER));
    }

    @Test
    void theServerFloorStopsAStalledBlocker() {
        // T_now frozen at the block-start window while server time runs: max(0, S_now − S_B − jitter) catches up
        final NeueState state = blocking();
        assertTrue(state.parries(100L, 1004L, JITTER)); // 4 − 2 = 2 < 3
        assertFalse(state.parries(100L, 1005L, JITTER)); // 5 − 2 = 3
    }

    @Test
    void outsideAuthorityTheServerClockAlone() {
        final NeueState state = new NeueState();
        state.blockStarted(1000L, 1000L, false, NEUE);
        assertTrue(state.parries(1002L, 1002L, 0));
        assertFalse(state.parries(1003L, 1003L, 0));
    }

    @Test
    void riposteIsACritForTwelveTicks() {
        // Combat.RiposteSeconds 0.6: a crit at +11, gone at +12
        final NeueState state = blocking();
        state.parried(1000L, NEUE.riposteTicks());
        assertTrue(state.riposteReady(1011L));
        assertFalse(state.riposteReady(1012L));
        // A click the window turned away doesn't use it (nothing calls consume); a hit that lands, fresh or overflow, does
        assertTrue(state.riposteReady(1005L));
        assertTrue(state.consumeRiposte(1005L));
        assertFalse(state.riposteReady(1006L));
        assertFalse(state.consumeRiposte(1006L));
        assertEquals(1, state.ripostes());
        assertFalse(new NeueState().riposteReady(Long.MAX_VALUE - 1)); // never parried
    }

    @Test
    void lockoutRunsFromTheRecoilsAckForTheStagger() {
        final NeueState state = new NeueState();
        final Object recoil = new Object();
        state.stagger(1000L, recoil, NEUE.staggerTicks(), 3, true);
        assertTrue(state.staggered());
        // Before T_s is known every attack is refused: clicks made before the client saw the parry are the recovery
        assertTrue(state.lockedOut(true, 40L, 1001L));
        assertFalse(state.motionApplied(new Object(), 52L)); // another packet
        assertTrue(state.motionApplied(recoil, 52L));
        assertEquals(52L, state.staggerAck());
        for (long tick = 52L; tick <= 61L; tick++) {
            assertTrue(state.lockedOut(true, tick, 1003L), "window " + tick); // T_s ... T_s + 9
        }
        assertFalse(state.lockedOut(true, 62L, 1004L)); // T_s + 10 is delivered
        assertFalse(state.staggered());
    }

    @Test
    void outsideAuthorityTheRoundTripStandsInForTheAck() {
        final NeueState state = new NeueState();
        state.stagger(1000L, new Object(), 10, 3, false);
        assertTrue(state.lockedOut(false, 0L, 1012L)); // S_parry + ceil(rtt / 50) + 10, exclusive
        assertFalse(state.lockedOut(false, 0L, 1013L));
        // A recoil a plugin cancelled has no ack to wait for
        final NeueState cancelled = new NeueState();
        cancelled.stagger(1000L, null, 10, 3, false);
        assertTrue(cancelled.lockedOut(true, 99L, 1012L));
        assertFalse(cancelled.lockedOut(true, 99L, 1013L));
    }

    @Test
    void theRoundTripIsTakenOnceAtTheParry() {
        // A keep-alive landing mid-stagger changes the connection's round trip; the stagger already running keeps its end
        final NeueState state = new NeueState();
        state.stagger(1000L, new Object(), 10, 1, false);
        assertTrue(state.lockedOut(false, 0L, 1010L));
        assertFalse(state.lockedOut(false, 0L, 1011L)); // S_parry + 1 + 10, whatever the round trip reads now
        final NeueState next = new NeueState();
        next.stagger(2000L, new Object(), 10, 0, false);
        assertFalse(next.lockedOut(false, 0L, 2010L)); // a zero round trip: S_parry + 10
    }

    @Test
    void aLostAckStopsLockingEventually() {
        final NeueState state = new NeueState();
        state.stagger(1000L, new Object(), 10, 3, true);
        assertTrue(state.lockedOut(true, 5L, 1110L));
        assertFalse(state.lockedOut(true, 5L, 1111L));
        assertTrue(state.slowEnds(true, 5L, 1111L, 2));
    }

    @Test
    void theSlowComesOffAHalfTripEarlySoItLandsOnTime() {
        final NeueState state = new NeueState();
        final Object recoil = new Object();
        state.stagger(1000L, recoil, 10, 2, true);
        assertTrue(state.slowed());
        state.motionApplied(recoil, 52L);
        assertFalse(state.slowEnds(true, 59L, 1006L, 2));
        assertTrue(state.slowEnds(true, 60L, 1007L, 2)); // T_s + 10 − round(rtt / 50)
        assertFalse(state.slowed());
        assertFalse(state.slowEnds(true, 61L, 1008L, 2)); // once
        final NeueState outside = new NeueState();
        outside.stagger(1000L, null, 10, 0, true);
        assertFalse(outside.slowEnds(false, 0L, 1009L, 0));
        assertTrue(outside.slowEnds(false, 0L, 1010L, 0));
    }

    @Test
    void aStaggerDropsTheHeldClick() {
        final NeueState state = new NeueState();
        state.hold(7, new Object(), 1000L, 3, 0, new Object());
        state.stagger(1001L, new Object(), 10, 0, false);
        assertFalse(state.pending());
        assertEquals(1, state.staggers());
    }

    @Test
    void noStaggerLengthNoLockout() {
        final NeueState state = new NeueState();
        state.stagger(1000L, new Object(), 0, 0, true);
        assertFalse(state.staggered());
        assertFalse(state.slowed());
        assertFalse(state.lockedOut(true, 1L, 1000L));
    }
}
