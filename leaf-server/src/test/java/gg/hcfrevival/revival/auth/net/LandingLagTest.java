package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** NAS glitch 2026-10-22: where a correction sent now lands, from the pings' own landings. */
@RevivalTest
class LandingLagTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void theMedianOfRecentLandings() {
        final LandingLag lag = new LandingLag();
        assertEquals(-1, lag.estimate(LandingLag.SCOPE, 0L, SECOND), "nothing measured yet");
        lag.sample(LandingLag.SCOPE, 2, 10L);
        lag.sample(LandingLag.SCOPE, 1, 20L);
        lag.sample(LandingLag.SCOPE, 2, 30L);
        assertEquals(2, lag.estimate(LandingLag.SCOPE, 40L, SECOND));
        lag.sample(LandingLag.SCOPE, 1, 50L);
        assertEquals(1, lag.estimate(LandingLag.SCOPE, 60L, SECOND), "the lower median of 1 1 2 2");
        assertEquals(-1, lag.estimate(LandingLag.TICK, 60L, SECOND), "each flush kind on its own");
    }

    @Test
    void oldLandingsAgeOut() {
        final LandingLag lag = new LandingLag();
        lag.sample(LandingLag.TICK, 3, 0L);
        lag.sample(LandingLag.TICK, 3, 1L);
        lag.sample(LandingLag.TICK, 2, 2 * SECOND);
        assertEquals(2, lag.estimate(LandingLag.TICK, 2 * SECOND + 1L, SECOND), "only the last second's");
        assertEquals(-1, lag.estimate(LandingLag.TICK, 4 * SECOND, SECOND));
        assertEquals(2 * SECOND, lag.lastSampleNanos(LandingLag.TICK));
    }

    @Test
    void noLandingBeforeItsWindowAndNoStalls() {
        final LandingLag lag = new LandingLag();
        lag.sample(LandingLag.SCOPE, -1, 1L);
        lag.sample(LandingLag.SCOPE, -3, 2L);
        lag.sample(LandingLag.SCOPE, LandingLag.MAX_LAG + 1, 3L);
        assertEquals(-1, lag.estimate(LandingLag.SCOPE, 4L, SECOND));
        assertEquals(0L, lag.lastSampleNanos(LandingLag.SCOPE));
    }

    @Test
    void keepsTheLastSixteen() {
        final LandingLag lag = new LandingLag();
        for (int i = 0; i < LandingLag.SAMPLES; i++) {
            lag.sample(LandingLag.SCOPE, 5, i);
        }
        for (int i = 0; i < LandingLag.SAMPLES / 2 + 1; i++) {
            lag.sample(LandingLag.SCOPE, 1, 100 + i);
        }
        assertEquals(1, lag.estimate(LandingLag.SCOPE, 200L, SECOND), "more than half of the kept ones are newer");
    }
}
