package gg.hcfrevival.revival.auth.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** pvp-issues SP-4: tick-locked w-tap and block-hit macros. */
@RevivalTest
class TapWatchTest {

    // A hit, then the key off for `length` ticks starting `lead` ticks after it, then `gap` quiet ticks; returns the next tick
    private static int tap(final TapWatch watch, int tick, final int lead, final int length, final int gap) {
        for (int k = 0; k < lead + length + gap; k++, tick++) {
            watch.onTick(tick, k == 0, k < lead || k >= lead + length);
        }
        return tick;
    }

    @Test
    void aTickLockedMacroRunsOneSampleOverAndOver() {
        final TapWatch watch = new TapWatch();
        int tick = 0;
        for (int i = 1; i <= 120; i++) {
            tick = tap(watch, tick, 1, 2, 7);
            assertEquals(i, watch.run(), "tap " + i);
        }
        assertEquals(120, watch.longest());
        assertEquals(1, watch.lastLead());
        assertEquals(2, watch.lastLength());
    }

    @Test
    void aTapInTheAttacksOwnTickLeadsByZero() {
        final TapWatch watch = new TapWatch();
        tap(watch, 5, 0, 3, 4);
        assertEquals(1, watch.samples());
        assertEquals(0, watch.lastLead());
        assertEquals(3, watch.lastLength());
    }

    @Test
    void oneTickOffBreaksTheRun() {
        final TapWatch watch = new TapWatch();
        int tick = 0;
        for (int i = 0; i < 50; i++) {
            tick = tap(watch, tick, 1, 2, 7);
        }
        tick = tap(watch, tick, 1, 3, 6);
        assertEquals(1, watch.run(), "a longer tap starts a new run");
        tick = tap(watch, tick, 2, 2, 6);
        assertEquals(1, watch.run(), "a later tap starts a new run");
        tap(watch, tick, 2, 2, 6);
        assertEquals(2, watch.run());
        assertEquals(50, watch.longest());
    }

    @Test
    void aKeyChangeWithNoRecentAttackIsNoTap() {
        final TapWatch watch = new TapWatch();
        int tick = 0;
        watch.onTick(tick++, true, true);
        for (int k = 0; k < TapWatch.MAX_GAP; k++) {
            watch.onTick(tick++, false, true);
        }
        // Eleven ticks after the hit: walking off, not a tap
        watch.onTick(tick++, false, false);
        watch.onTick(tick++, false, true);
        assertEquals(0, watch.samples());
        // Nor a tap with no hit at all, or one held off past MAX_GAP
        for (int k = 0; k < 3; k++) {
            watch.onTick(tick++, false, false);
            watch.onTick(tick++, false, true);
        }
        watch.onTick(tick++, true, false);
        for (int k = 0; k < TapWatch.MAX_GAP + 1; k++) {
            watch.onTick(tick++, false, false);
        }
        watch.onTick(tick, false, true);
        assertEquals(0, watch.samples());
    }

    @Test
    void anAttackTakesOnlyItsFirstTap() {
        final TapWatch watch = new TapWatch();
        int tick = tap(watch, 0, 1, 1, 1);
        // a second tap with no attack of its own
        watch.onTick(tick++, false, false);
        watch.onTick(tick, false, true);
        assertEquals(1, watch.samples());
    }

    @Test
    void humanTimingNeverHoldsAHundred() {
        // The tightest human the SP-4 study models: both gaps within a 20 ms (0.4 tick) spread around a whole tick
        final Random random = new Random(26_10_23L);
        final TapWatch watch = new TapWatch();
        int tick = 0;
        for (int i = 0; i < 20_000; i++) {
            final int lead = Math.max(0, (int) Math.round(1.0 + 0.4 * random.nextGaussian()));
            final int length = Math.max(1, (int) Math.round(2.0 + 0.4 * random.nextGaussian()));
            tick = tap(watch, tick, lead, length, 6);
        }
        assertEquals(20_000, watch.samples());
        assertTrue(watch.longest() < 25, "longest run " + watch.longest());
    }
}
