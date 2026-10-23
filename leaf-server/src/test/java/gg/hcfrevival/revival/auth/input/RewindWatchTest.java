package gg.hcfrevival.revival.auth.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** pvp-issues HR-3: the rewind-stretch median. */
@RevivalTest
class RewindWatchTest {

    @Test
    void theMedianOfTheLastHitsIsJudgedOnceTheWindowIsFull() {
        final RewindWatch watch = new RewindWatch();
        for (int i = 0; i < 29; i++) {
            watch.onHit(150L, 30);
        }
        assertFalse(watch.full());
        assertEquals(Long.MIN_VALUE, watch.highest(), "no full window yet");
        watch.onHit(150L, 30);
        assertTrue(watch.full());
        assertEquals(150L, watch.median());
        // Fifteen honest hits replace fifteen stretched ones: the median sits on the boundary, then drops
        for (int i = 0; i < 15; i++) {
            watch.onHit(20L, 30);
        }
        assertEquals(150L, watch.median(), "15 of 30 still stretched: the upper median");
        watch.onHit(20L, 30);
        assertEquals(20L, watch.median());
        assertEquals(150L, watch.highest());
        assertEquals(46L, watch.hits());
    }

    @Test
    void aNewWindowStartsOver() {
        final RewindWatch watch = new RewindWatch();
        for (int i = 0; i < 30; i++) {
            watch.onHit(200L, 30);
        }
        watch.onHit(10L, 10);
        assertFalse(watch.full());
        assertEquals(10L, watch.median());
    }

    @Test
    void aJitteryHonestClientStaysUnder() {
        // grid1 r150j30 as a model: excess around 60 ms with a 30 ms spread, a heavy tail of stalls 5% of the time
        final Random random = new Random(150_30L);
        final RewindWatch watch = new RewindWatch();
        for (int i = 0; i < 10_000; i++) {
            final long excess = random.nextDouble() < 0.05 ? 300L : Math.round(60.0 + 30.0 * random.nextGaussian());
            watch.onHit(excess, RewindWatch.HITS);
        }
        assertTrue(watch.highest() < RewindWatch.STRETCH_MS, "highest median " + watch.highest());
    }
}
