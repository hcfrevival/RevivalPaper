package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** NAS glitch 2026-10-22: where a correction sent now lands, from the pings' own landings. */
@RevivalTest
class LandingLagTest {

    private static final int SECOND = 20; // windows

    @Test
    void theMedianOfRecentLandings() {
        final LandingLag lag = new LandingLag();
        assertEquals(-1, lag.estimate(LandingLag.SCOPE, 0, SECOND), "nothing measured yet");
        lag.sample(LandingLag.SCOPE, 2, 10, 10L);
        lag.sample(LandingLag.SCOPE, 1, 11, 20L);
        lag.sample(LandingLag.SCOPE, 2, 12, 30L);
        assertEquals(2, lag.estimate(LandingLag.SCOPE, 13, SECOND));
        lag.sample(LandingLag.SCOPE, 1, 13, 50L);
        assertEquals(1, lag.estimate(LandingLag.SCOPE, 14, SECOND), "the lower median of 1 1 2 2");
        assertEquals(-1, lag.estimate(LandingLag.TICK, 14, SECOND), "each flush kind on its own");
    }

    @Test
    void oldLandingsAgeOut() {
        final LandingLag lag = new LandingLag();
        lag.sample(LandingLag.TICK, 3, 0, 0L);
        lag.sample(LandingLag.TICK, 3, 1, 1L);
        lag.sample(LandingLag.TICK, 2, 2 * SECOND, 2_000_000_000L);
        assertEquals(2, lag.estimate(LandingLag.TICK, 2 * SECOND + 1, SECOND), "only the last second's windows");
        assertEquals(-1, lag.estimate(LandingLag.TICK, 4 * SECOND, SECOND));
        assertEquals(2_000_000_000L, lag.lastSampleNanos(LandingLag.TICK));
    }

    @Test
    void aClientFreezeAgesNothing() {
        // NW-1: aged by wall time, a 2 s freeze left no landing, and the correction after it fell back to an RTT the
        // freeze's own pongs had inflated. The client's windows stand still while it does
        final LandingLag lag = new LandingLag();
        lag.sample(LandingLag.SCOPE, 5, 100, 1_000_000_000L);
        lag.sample(LandingLag.SCOPE, 5, 101, 1_050_000_000L);
        assertEquals(5, lag.estimate(LandingLag.SCOPE, 102, SECOND), "the next window after the freeze, 2 s later by the clock");
    }

    @Test
    void noLandingBeforeItsWindowAndNoStalls() {
        final LandingLag lag = new LandingLag();
        lag.sample(LandingLag.SCOPE, -1, 1, 1L);
        lag.sample(LandingLag.SCOPE, -3, 2, 2L);
        lag.sample(LandingLag.SCOPE, LandingLag.MAX_LAG + 1, 3, 3L);
        assertEquals(-1, lag.estimate(LandingLag.SCOPE, 4, SECOND));
        assertEquals(0L, lag.lastSampleNanos(LandingLag.SCOPE));
    }

    @Test
    void keepsTheLastSixteen() {
        final LandingLag lag = new LandingLag();
        for (int i = 0; i < LandingLag.SAMPLES; i++) {
            lag.sample(LandingLag.SCOPE, 5, i, i);
        }
        for (int i = 0; i < LandingLag.SAMPLES / 2 + 1; i++) {
            lag.sample(LandingLag.SCOPE, 1, LandingLag.SAMPLES + i, 100 + i);
        }
        assertEquals(1, lag.estimate(LandingLag.SCOPE, 2 * LandingLag.SAMPLES, 4 * LandingLag.SAMPLES), "more than half of the kept ones are newer");
    }
}
