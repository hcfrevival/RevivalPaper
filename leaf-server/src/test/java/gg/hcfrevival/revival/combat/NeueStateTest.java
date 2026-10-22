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
    /** Server time long past any lockout's real-time floor, for the checks about windows alone. */
    private static final long LATE = Long.MAX_VALUE / 2;

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
        state.stagger(1000L, recoil, NEUE.staggerTicks(), 1, 3, true, NeueState.NEVER, 0L);
        assertTrue(state.staggered());
        // Before T_s is known every attack is refused: clicks made before the client saw the parry are the recovery
        assertTrue(state.lockedOut(true, 40L, 1001L, LATE));
        assertFalse(state.motionApplied(new Object(), 52L, 0L, TICK)); // another packet
        assertTrue(state.motionApplied(recoil, 52L, 0L, TICK));
        assertEquals(52L, state.staggerAck());
        for (long tick = 52L; tick <= 61L; tick++) {
            assertTrue(state.lockedOut(true, tick, 1003L, LATE), "window " + tick); // T_s ... T_s + 9
        }
        assertFalse(state.lockedOut(true, 62L, 1004L, LATE)); // T_s + 10 is delivered
        assertFalse(state.staggered());
    }

    /**
     * Cheat suite 2, L3: under authority the lockout also lasts {@code stagger − jitter} ticks of real time from the
     * earliest an honest ack could have come, so a client that runs its windows 3x fast once it sees the recoil gains at
     * most the jitter (1 tick). Times are {@code System.nanoTime}, the clock TickClock budgets windows with.
     */
    @Test
    void aClientRunningItsWindowsFastCantShortenTheLockout() {
        final NeueState state = staggered(SENT);
        assertTrue(state.motionApplied(RECOIL, 52L, SENT + RTT, TICK)); // acked in window 52, one round trip after it went out
        final long ack = SENT + RTT;
        // Windows 52..62 at 3x speed: 62 runs 167 ms after the ack, its windows say the lockout is over
        assertTrue(state.lockedOut(true, 62L, 1003L, ack + ms(167)));
        assertTrue(state.lockedOut(true, 63L, 1008L, ack + ms(449))); // the floor: 9 ticks of 50 ms after the ack
        assertFalse(state.lockedOut(true, 64L, 1009L, ack + ms(450)));
        // An honest client's window 62 runs 500 ms after the ack: its windows end it, the floor never does
        final NeueState honest = staggered(SENT);
        honest.motionApplied(RECOIL, 52L, ack, TICK);
        assertTrue(honest.lockedOut(true, 61L, 1009L, ack + ms(450))); // its windows still hold it
        assertFalse(honest.lockedOut(true, 62L, 1010L, ack + ms(500)));
        // The floor follows the client's tick period (a server ticking at 10 TPS gives 100 ms windows)
        final NeueState slowRate = staggered(SENT);
        slowRate.motionApplied(RECOIL, 52L, ack, 2 * TICK);
        assertTrue(slowRate.lockedOut(true, 62L, 1010L, ack + ms(899)));
        assertFalse(slowRate.lockedOut(true, 62L, 1010L, ack + ms(900)));
    }

    /**
     * Review 2: the floor runs on real time, so a slow server (16 TPS, or a hitch) never lengthens an honest lockout. The
     * client's windows keep 50 ms of real time while the server's ticks fall behind: window T_s + 10 still ends it.
     */
    @Test
    void aSlowServerDoesntLengthenAnHonestLockout() {
        final NeueState state = staggered(SENT);
        final long ack = SENT + RTT;
        state.motionApplied(RECOIL, 52L, ack, TICK);
        // 500 ms of real time after the ack, but only 8 server ticks (62.5 ms each), or a 1 s hitch with 2
        assertTrue(state.lockedOut(true, 61L, 1008L, ack + ms(450)));
        assertFalse(state.lockedOut(true, 62L, 1008L, ack + ms(500)));
        final NeueState hitch = staggered(SENT);
        hitch.motionApplied(RECOIL, 52L, ack, TICK);
        assertFalse(hitch.lockedOut(true, 62L, 1002L, ack + ms(1100)));
    }

    /**
     * Review 3: the floor is anchored at the earliest an honest ack could have come ({@code sent + ackRtt}), not at when
     * the ack's window happened to be processed, so an ack window 60 or 100 ms late costs the honest T_s + 10 click
     * nothing. A client can't move the send time; delaying its pongs only delays T_s.
     */
    @Test
    void aLateAckWindowCostsNoHonestClick() {
        for (final long late : new long[] {60, 80, 100}) {
            final NeueState state = staggered(SENT);
            final long expected = SENT + RTT; // when the ack's window should have come
            state.motionApplied(RECOIL, 52L, expected + ms(late), TICK);
            // The honest client's T_s + 10 runs 500 ms after T_s was sent, on time for the rest of the stream
            assertFalse(state.lockedOut(true, 62L, 1010L, expected + ms(500)), late + " ms late");
        }
        // An ack earlier than the baseline allows (a fast round trip) anchors at the ack itself
        final NeueState fast = staggered(SENT);
        fast.motionApplied(RECOIL, 52L, SENT + ms(20), TICK);
        assertTrue(fast.lockedOut(true, 62L, 1003L, SENT + ms(20) + ms(449)));
        assertFalse(fast.lockedOut(true, 62L, 1003L, SENT + ms(20) + ms(450)));
    }

    /**
     * Cheat suite 2, L3 at 200 ms: the ping rose 1.3 s before the parry, faster than the baseline (a p10) follows, so the
     * round trip the floor expects is the last ack's, read when the recoil went out. The 3x client's ack comes one 200 ms
     * round trip after the send, and the floor holds it 9 ticks past that, as for a client that was always at 200 ms; the
     * baseline alone (20 ms) would have let it attack 180 ms early.
     */
    @Test
    void aPingThatJustRoseBuysNoTicks() {
        assertEquals(ms(200), NeueState.ackRtt(ms(20), ms(200)));
        assertEquals(ms(20), NeueState.ackRtt(ms(20), ms(15))); // never under the baseline
        final NeueState state = new NeueState();
        state.stagger(1000L, RECOIL, 10, 1, 3, false, SENT, NeueState.ackRtt(ms(20), ms(200)));
        final long ack = SENT + ms(200);
        state.motionApplied(RECOIL, 52L, ack, TICK);
        assertTrue(state.lockedOut(true, 62L, 1007L, ack + ms(167))); // its windows say it's over
        assertTrue(state.lockedOut(true, 63L, 1013L, ack + ms(449)));
        assertFalse(state.lockedOut(true, 64L, 1013L, ack + ms(450)));
        // The late-ack allowance still holds at the new ping: an ack window 100 ms late costs the honest click nothing
        final NeueState late = new NeueState();
        late.stagger(1000L, RECOIL, 10, 1, 3, false, SENT, NeueState.ackRtt(ms(20), ms(200)));
        late.motionApplied(RECOIL, 52L, ack + ms(100), TICK);
        assertFalse(late.lockedOut(true, 62L, 1014L, ack + ms(500)));
    }

    /**
     * Real-client run N2: the slow is its own packet, which can be acked windows after the recoil; its removal is timed
     * from its own ack (window 55 here, three after T_s) so the client is slowed for the whole stagger, not 7.
     */
    @Test
    void theSlowCountsFromItsOwnAck() {
        final NeueState state = new NeueState();
        state.stagger(1000L, RECOIL, 10, 1, 3, true, SENT, RTT);
        final Object slow = new Object();
        state.slowSent(slow);
        state.motionApplied(RECOIL, 52L, SENT + RTT, TICK);
        assertFalse(state.slowEnds(true, 62L, 1012L, 2)); // T_s + 10 − rtt: the recoil's clock would end it here
        assertFalse(state.slowApplied(new Object(), 55L));
        assertTrue(state.slowApplied(slow, 55L));
        assertFalse(state.slowEnds(true, 62L, 1012L, 2));
        assertTrue(state.slowEnds(true, 63L, 1013L, 2)); // 55 + 10 − 2
    }

    private static final Object RECOIL = new Object();
    private static final long SENT = 1_000_000_000_000L;
    private static final long TICK = 50_000_000L;
    private static final long RTT = 120_000_000L;

    private static long ms(final long millis) {
        return millis * 1_000_000L;
    }

    private static NeueState staggered(final long sentNanos) {
        final NeueState state = new NeueState();
        state.stagger(1000L, RECOIL, 10, 1, 3, false, sentNanos, RTT);
        return state;
    }

    @Test
    void outsideAuthorityTheRoundTripStandsInForTheAck() {
        final NeueState state = new NeueState();
        state.stagger(1000L, new Object(), 10, 1, 3, false, NeueState.NEVER, 0L);
        assertTrue(state.lockedOut(false, 0L, 1012L, LATE)); // S_parry + ceil(rtt / 50) + 10, exclusive
        assertFalse(state.lockedOut(false, 0L, 1013L, LATE));
        // A recoil a plugin cancelled has no ack to wait for
        final NeueState cancelled = new NeueState();
        cancelled.stagger(1000L, null, 10, 1, 3, false, NeueState.NEVER, 0L);
        assertTrue(cancelled.lockedOut(true, 99L, 1012L, LATE));
        assertFalse(cancelled.lockedOut(true, 99L, 1013L, LATE));
    }

    @Test
    void theRoundTripIsTakenOnceAtTheParry() {
        // A keep-alive landing mid-stagger changes the connection's round trip; the stagger already running keeps its end
        final NeueState state = new NeueState();
        state.stagger(1000L, new Object(), 10, 1, 1, false, NeueState.NEVER, 0L);
        assertTrue(state.lockedOut(false, 0L, 1010L, LATE));
        assertFalse(state.lockedOut(false, 0L, 1011L, LATE)); // S_parry + 1 + 10, whatever the round trip reads now
        final NeueState next = new NeueState();
        next.stagger(2000L, new Object(), 10, 1, 0, false, NeueState.NEVER, 0L);
        assertFalse(next.lockedOut(false, 0L, 2010L, LATE)); // a zero round trip: S_parry + 10
    }

    @Test
    void aLostAckStopsLockingEventually() {
        final NeueState state = new NeueState();
        state.stagger(1000L, new Object(), 10, 1, 3, true, NeueState.NEVER, 0L);
        assertTrue(state.lockedOut(true, 5L, 1110L, LATE));
        assertFalse(state.lockedOut(true, 5L, 1111L, LATE));
        assertTrue(state.slowEnds(true, 5L, 1111L, 2));
    }

    @Test
    void theSlowComesOffAHalfTripEarlySoItLandsOnTime() {
        final NeueState state = new NeueState();
        final Object recoil = new Object();
        state.stagger(1000L, recoil, 10, 1, 2, true, NeueState.NEVER, 0L);
        assertTrue(state.slowed());
        state.motionApplied(recoil, 52L, 0L, TICK);
        assertFalse(state.slowEnds(true, 59L, 1006L, 2));
        assertTrue(state.slowEnds(true, 60L, 1007L, 2)); // T_s + 10 − round(rtt / 50)
        assertFalse(state.slowed());
        assertFalse(state.slowEnds(true, 61L, 1008L, 2)); // once
        final NeueState outside = new NeueState();
        outside.stagger(1000L, null, 10, 1, 0, true, NeueState.NEVER, 0L);
        assertFalse(outside.slowEnds(false, 0L, 1009L, 0));
        assertTrue(outside.slowEnds(false, 0L, 1010L, 0));
    }

    @Test
    void aStaggerDropsTheHeldClick() {
        final NeueState state = new NeueState();
        state.hold(7, new Object(), 1000L, 3, 0, new Object());
        state.stagger(1001L, new Object(), 10, 1, 0, false, NeueState.NEVER, 0L);
        assertFalse(state.pending());
        assertEquals(1, state.staggers());
    }

    @Test
    void noStaggerLengthNoLockout() {
        final NeueState state = new NeueState();
        state.stagger(1000L, new Object(), 0, 1, 0, true, NeueState.NEVER, 0L);
        assertFalse(state.staggered());
        assertFalse(state.slowed());
        assertFalse(state.lockedOut(true, 1L, 1000L, LATE));
    }
}
