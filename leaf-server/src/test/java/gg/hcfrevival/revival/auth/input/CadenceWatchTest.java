package gg.hcfrevival.revival.auth.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** neue-combat §12.2 detectors A ({@code click-streak}) and B ({@code click-lock}), with §12.3's cases. */
@RevivalTest
class CadenceWatchTest {

    @Test
    void aRunOfOneClickTicksIsCountedAndResetByAnythingElse() {
        final CadenceWatch watch = new CadenceWatch();
        for (int i = 0; i < 99; i++) {
            watch.onWindow(true, 1);
        }
        assertEquals(99, watch.streak());
        assertEquals(100, watch.onWindow(true, 1));
        assertEquals(0, watch.onWindow(true, 2), "two clicks in a tick");
        watch.onWindow(true, 1);
        assertEquals(0, watch.onWindow(true, 0), "a tick without a click");
        watch.onWindow(true, 1);
        assertEquals(0, watch.onWindow(false, 1), "an ineligible window (mining, a screen, using an item)");
        assertEquals(100, watch.longest());
    }

    @Test
    void aTwelveCpsLockFiresAtTheHundredFiftiethInterval() {
        // 2, 2, 1 ticks between clicks: 12 CPS on the 20 Hz tick grid, a balanced sequence never a tick off its line
        final CadenceWatch watch = new CadenceWatch();
        watch.onWindow(true, 1);
        for (int k = 1; k <= 150; k++) {
            click(watch, k % 3 == 0 ? 1 : 2);
            assertEquals(k < 150 ? 0 : 150, watch.locked(), "after " + k + " intervals");
        }
        click(watch, 2);
        assertEquals(151, watch.locked(), "one click more, still locked");
    }

    @Test
    void tenAndTwentyCpsLock() {
        final CadenceWatch ten = new CadenceWatch();
        ten.onWindow(true, 1);
        for (int k = 0; k < 150; k++) {
            click(ten, 2);
        }
        assertEquals(150, ten.locked());
        final CadenceWatch twenty = new CadenceWatch();
        for (int k = 0; k < 151; k++) {
            twenty.onWindow(true, 1);
        }
        assertEquals(150, twenty.locked());
    }

    @Test
    void twentyFiveCpsLocksWithTwoClicksInSomeTicks() {
        // 1, 1, 1, 2 clicks a tick: intervals of 1 and 0
        final CadenceWatch watch = new CadenceWatch();
        for (int t = 0; t < 140; t++) {
            watch.onWindow(true, t % 4 == 3 ? 2 : 1);
        }
        assertTrue(watch.locked() >= 150, "locked " + watch.locked());
    }

    @Test
    void aLateClickBreaksTheLockAtItsClick() {
        final CadenceWatch watch = new CadenceWatch();
        watch.onWindow(true, 1);
        for (int k = 0; k < 150; k++) {
            click(watch, 2);
        }
        assertEquals(150, watch.locked());
        // A 3 at the end of the ring is about a tick off the line through the 2s
        click(watch, 3);
        assertEquals(0, watch.locked());
    }

    @Test
    void intervalsTwoApartOrTooSlowNeverLock() {
        final CadenceWatch spread = new CadenceWatch();
        spread.onWindow(true, 1);
        for (int k = 0; k < 400; k++) {
            click(spread, k % 2 == 0 ? 1 : 3); // 10 CPS on average, but 1s and 3s
        }
        assertEquals(0, spread.longestLock());
        final CadenceWatch slow = new CadenceWatch();
        slow.onWindow(true, 1);
        for (int k = 0; k < 400; k++) {
            click(slow, 3); // 6.7 CPS, under click-lock-min-cps
        }
        assertEquals(0, slow.longestLock());
    }

    @Test
    void anIneligibleWindowOrANewRingSizeStartsTheChainOver() {
        final CadenceWatch watch = new CadenceWatch();
        watch.onWindow(true, 1);
        for (int k = 0; k < 149; k++) {
            click(watch, 2);
        }
        watch.onWindow(false, 0); // a screen opened
        for (int k = 0; k < 140; k++) {
            click(watch, 2);
        }
        assertEquals(0, watch.longestLock(), "289 clicks at a fixed rate, but no 150 intervals in one chain");
        for (int k = 0; k < 11; k++) {
            click(watch, 2);
        }
        assertEquals(150, watch.locked(), "151 clicks since the break: 150 intervals");
        watch.onWindow(true, 0, 100, CadenceWatch.LOCK_RESIDUAL, CadenceWatch.LOCK_MIN_CPS);
        watch.onWindow(true, 1, 100, CadenceWatch.LOCK_RESIDUAL, CadenceWatch.LOCK_MIN_CPS);
        assertEquals(0, watch.locked(), "click-lock-clicks changed: a new ring");
    }

    @Test
    void aSteadyHumanNeverLocks() {
        // §12.3: 12 CPS, intervals with a CV of 0.10 (steady, the pessimistic side of the study), 10,000 clicks on the tick
        // grid: the timing drifts off any line within a few dozen clicks
        final java.util.Random random = new java.util.Random(12);
        final CadenceWatch watch = new CadenceWatch();
        double ms = 0.0;
        int tick = 0;
        int inTick = 0;
        for (int k = 0; k < 10_000; k++) {
            ms += Math.max(10.0, 1000.0 / 12.0 * (1.0 + 0.10 * random.nextGaussian()));
            final int clickTick = (int) (ms / 50.0);
            while (tick < clickTick) {
                watch.onWindow(true, inTick);
                inTick = 0;
                tick++;
            }
            inTick++;
        }
        assertEquals(0, watch.longestLock());
    }

    // Windows without a click, then a window with one: an interval of `gap` ticks since the last click
    private static void click(final CadenceWatch watch, final int gap) {
        for (int i = 1; i < gap; i++) {
            watch.onWindow(true, 0);
        }
        watch.onWindow(true, 1);
    }
}
