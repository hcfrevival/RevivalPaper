package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.CombatRules;
import org.junit.jupiter.api.Test;

/**
 * Neue's click buffer as a pure state machine (docs/revival/neue-combat.md §5.2), from Combo's {@code GameServer.Claim}
 * and {@code GameServer.Buffered} with {@code Rules.ClickBufferSeconds} 0.15 (3 ticks): a click the shut damage window
 * turned away lands on the first tick the window opens, if the attacker is still free and the target still in reach.
 * Combo's tests: {@code NeuesBufferLandsAClickOnTheFirstTickTheWindowOpens}, {@code ClassicLosesAClickIntoTheWindow}.
 */
@RevivalTest
class ClickBufferTest {

    private static final int BUFFER = CombatRules.NEUE.clickBufferTicks();
    private static final int VICTIM = 42;
    private static final Object SWORD = new Object();
    private static final int SLOT = 0;

    // An attack on VICTIM at server tick `tick` that the window turned away, settled
    private static Object heldClick(final NeueState state, final long tick) {
        final Object packet = new Object();
        state.begin(VICTIM, packet, 2.0, false);
        assertTrue(state.enter());
        assertTrue(state.on(VICTIM));
        state.mark(NeueState.HELD);
        assertEquals(NeueState.HELD, state.settle(tick, BUFFER, SLOT, SWORD));
        return packet;
    }

    // The landing pass from `from` until the click is gone: the tick it was due, or -1
    private static long runUntilGone(final NeueState state, final long from, final long open) {
        for (long tick = from; tick < from + 20; tick++) {
            final int step = state.landingStep(tick, true, true, tick >= open);
            if (step == NeueState.DUE) {
                return tick;
            }
            if (step != NeueState.WAIT) {
                return -1L;
            }
        }
        return -1L;
    }

    @Test
    void aClickThreeTicksBeforeTheWindowOpensLandsOnTheOpeningTick() {
        final NeueState state = new NeueState();
        final Object packet = heldClick(state, 97L);
        assertTrue(state.pending());
        assertEquals(VICTIM, state.pendingTarget());
        assertSame(packet, state.pendingPacket());
        assertSame(SWORD, state.pendingWeapon());
        assertEquals(100L, runUntilGone(state, 97L, 100L));
        assertFalse(state.pending()); // one attempt only
        assertSame(packet, state.pendingPacket()); // readable for the landing's begin
    }

    @Test
    void aClickFourTicksBeforeExpires() {
        final NeueState state = new NeueState();
        heldClick(state, 96L);
        assertEquals(-1L, runUntilGone(state, 96L, 100L));
        assertEquals(1, state.bufferExpired());
        assertFalse(state.pending());
    }

    @Test
    void classicHoldsNothing() {
        final NeueState state = new NeueState();
        state.begin(VICTIM, new Object(), 2.0, false);
        state.mark(NeueState.HELD);
        state.settle(97L, CombatRules.CLASSIC.clickBufferTicks(), SLOT, SWORD);
        assertFalse(state.pending());
    }

    @Test
    void outOfReachAtTheOpeningIsDroppedForGood() {
        final NeueState state = new NeueState();
        heldClick(state, 98L);
        assertEquals(NeueState.DUE, state.landingStep(100L, true, true, true));
        state.outOfReach(); // the reach check failed
        assertEquals(NeueState.IDLE, state.landingStep(101L, true, true, true)); // back in reach: still nothing
        assertEquals(1, state.bufferOutOfReach());
        assertNull(state.pendingPacket());
    }

    @Test
    void anAttackerWhoIsntFreeLosesTheClick() {
        // A block raised, the slot changed, a container opened, staggered: any of them before the window opens
        final NeueState state = new NeueState();
        heldClick(state, 98L);
        assertEquals(NeueState.WAIT, state.landingStep(99L, true, true, false));
        assertEquals(NeueState.NOT_FREE, state.landingStep(100L, true, false, true));
        assertFalse(state.pending());
        assertEquals(1, state.bufferDropped());
    }

    @Test
    void aTargetThatIsGoneDropsTheClick() {
        final NeueState state = new NeueState();
        heldClick(state, 98L);
        assertEquals(NeueState.GONE, state.landingStep(99L, false, true, false));
        assertFalse(state.pending());
    }

    @Test
    void aNewerHeldClickReplacesTheOld() {
        final NeueState state = new NeueState();
        heldClick(state, 98L);
        final Object newer = new Object();
        state.begin(VICTIM + 1, newer, 2.0, false);
        state.mark(NeueState.HELD);
        state.settle(99L, BUFFER, SLOT, SWORD);
        assertEquals(VICTIM + 1, state.pendingTarget());
        assertSame(newer, state.pendingPacket());
        assertEquals(102L, state.pendingUntil());
    }

    @Test
    void clicksThatWerentDeliveredLeaveItAndOnesThatLandedClearIt() {
        final NeueState state = new NeueState();
        final Object held = heldClick(state, 98L);
        // A missed click is never begun: nothing changes. One over the cap, or locked out, is refused
        state.begin(VICTIM, new Object(), 2.0, false);
        state.mark(NeueState.REFUSED);
        assertEquals(NeueState.REFUSED, state.settle(98L, BUFFER, SLOT, SWORD));
        assertTrue(state.pending());
        assertSame(held, state.pendingPacket());
        // Delivered and turned away for another reason (an event cancelled it): cleared
        state.begin(VICTIM, new Object(), 2.0, false);
        assertEquals(NeueState.TURNED_AWAY, state.settle(99L, BUFFER, SLOT, SWORD));
        assertFalse(state.pending());
        // Landed: cleared
        heldClick(state, 99L);
        state.begin(VICTIM + 1, new Object(), 2.0, false);
        state.mark(NeueState.LANDED);
        state.settle(99L, BUFFER, SLOT, SWORD);
        assertFalse(state.pending());
    }

    @Test
    void aParryAtTheOpeningIsAParryNotAHit() {
        final NeueState state = new NeueState();
        final Object packet = heldClick(state, 98L);
        assertEquals(NeueState.DUE, state.landingStep(100L, true, true, true));
        state.begin(VICTIM, packet, 2.4, true);
        assertTrue(state.buffered());
        assertEquals(2.4, state.distance());
        state.mark(NeueState.PARRIED);
        assertEquals(NeueState.PARRIED, state.settle(100L, BUFFER, SLOT, SWORD));
        assertFalse(state.pending());
        assertEquals(0, state.bufferedLanded());
    }

    @Test
    void twoHeldClicksOnOneVictimLandOneFreshHit() {
        // Connection order: the first lands fresh and shuts the window; the second is turned away and isn't held again
        final NeueState first = new NeueState();
        final NeueState second = new NeueState();
        final Object a = heldClick(first, 98L);
        final Object b = heldClick(second, 99L);
        assertEquals(NeueState.DUE, first.landingStep(100L, true, true, true));
        first.begin(VICTIM, a, 2.0, true);
        first.mark(NeueState.LANDED);
        first.settle(100L, BUFFER, SLOT, SWORD);
        assertEquals(NeueState.DUE, second.landingStep(100L, true, true, true));
        second.begin(VICTIM, b, 2.0, true);
        second.mark(NeueState.HELD);
        assertEquals(NeueState.HELD, second.settle(100L, BUFFER, SLOT, SWORD));
        assertFalse(second.pending());
        assertEquals(1, first.bufferedLanded());
        assertEquals(0, second.bufferedLanded());
    }

    @Test
    void anAttackOutsideTheNetcodeAdoptsItsTarget() {
        // Outside authority the hit-registration hook opens the context without the target; the first hook names it
        final NeueState state = new NeueState();
        assertFalse(state.enter()); // nothing begun
        state.begin(NeueState.UNKNOWN_TARGET, null, Double.NaN, false);
        assertTrue(state.enter());
        assertFalse(state.enter()); // entered once
        assertTrue(state.on(VICTIM));
        assertFalse(state.on(VICTIM + 1));
        assertEquals(VICTIM, state.target());
        state.mark(NeueState.HELD);
        state.settle(98L, BUFFER, SLOT, SWORD);
        assertTrue(state.pending());
        assertNull(state.pendingPacket()); // landed by the server-side pass
        assertEquals(-1, state.settle(99L, BUFFER, SLOT, SWORD)); // nothing running
    }

    /** The click cap outside authority: Combo's PlayerSim.Mouse and SpendClick at 20 Hz, Neue's 12 a second, burst 2. */
    @Test
    void clickCapLetsEvenClickersThroughAndHoldsTwentyToTwelve() {
        final CombatRules neue = CombatRules.NEUE;
        for (final double cps : new double[] {9.0, 12.0}) {
            final NeueState state = new NeueState();
            int lost = 0;
            long last = -1L;
            for (int i = 0; ; i++) {
                final long tick = Math.round(i * 20.0 / cps);
                if (tick >= 2000L) {
                    break;
                }
                if (tick == last) {
                    continue;
                }
                last = tick;
                if (!state.takeClick(tick, neue.clickRefill(), neue.clickBurst())) {
                    lost++;
                }
            }
            assertEquals(0, lost, cps + " CPS");
        }
        final NeueState spammer = new NeueState();
        int admitted = 0;
        for (long tick = 0L; tick < 2000L; tick++) {
            if (spammer.takeClick(tick, neue.clickRefill(), neue.clickBurst())) {
                admitted++;
            }
        }
        assertEquals(12.0, admitted / 100.0, 0.2); // AnEvenClickerUpToTheCapSwingsEveryClick, at 20 a second
    }

    @Test
    void afterARestThreeTicksInARowThenTheFourthIsRefused() {
        final CombatRules neue = CombatRules.NEUE;
        final NeueState state = new NeueState();
        assertTrue(state.takeClick(0L, neue.clickRefill(), neue.clickBurst()));
        // two seconds idle: full again
        assertTrue(state.takeClick(40L, neue.clickRefill(), neue.clickBurst()));
        assertTrue(state.takeClick(41L, neue.clickRefill(), neue.clickBurst()));
        assertTrue(state.takeClick(42L, neue.clickRefill(), neue.clickBurst()));
        assertFalse(state.takeClick(43L, neue.clickRefill(), neue.clickBurst()));
        // two in one tick spend two: the second refill never comes
        final NeueState burst = new NeueState();
        assertTrue(burst.takeClick(0L, neue.clickRefill(), neue.clickBurst()));
        assertTrue(burst.takeClick(0L, neue.clickRefill(), neue.clickBurst()));
        assertFalse(burst.takeClick(0L, neue.clickRefill(), neue.clickBurst()));
    }
}
