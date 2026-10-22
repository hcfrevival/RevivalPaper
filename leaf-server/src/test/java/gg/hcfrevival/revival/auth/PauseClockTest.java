package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/**
 * Torture F6: rewind ages are on server time, wall time less how far the server is behind its tick schedule. A hitch is
 * off the clock while it runs and until the catch-up ticks run; ordinary per-tick jitter never accumulates.
 */
@RevivalTest
class PauseClockTest {

    private static final long MS = 1_000_000L;

    @Test
    void aHitchIsOffTheClockUntilTheServerCatchesUp() {
        final long period = 60 * MS; // a tick rate of its own: the schedule starts afresh here
        final int tick = 1_000_000_000;
        long t = 7_000_000 * MS;
        Authority.noteServerTick(tick, t, period);
        // 2000 ticks with ±5 ms of jitter: nothing accumulates
        for (int i = 1; i <= 2000; i++) {
            Authority.noteServerTick(tick + i, t + i * period + (i % 2 == 0 ? 5 : -5) * MS, period);
        }
        t += 2000 * period;
        final long steady = Authority.lagMillisAt(t + 20 * MS);
        assertTrue(steady <= 11L, "jitter never accumulates: at most its own spread (" + steady + " ms)");
        // A 1.2 s tick: late already while it runs (an attack handled right after it is judged on that)
        final long during = Authority.lagMillisAt(t + 1000 * MS);
        assertTrue(during >= 930L && during <= 950L, "the running hitch is off the clock (" + during + " ms)");
        Authority.noteServerTick(tick + 2001, t + 1260 * MS, period);
        final long after = Authority.lagMillisAt(t + 1261 * MS);
        assertTrue(after >= 1200L && after <= 1212L, "the hitch is off the clock (" + after + " ms)");
        // Catch-up: 23 ticks back to back, 2 ms apart
        for (int i = 2; i <= 24; i++) {
            Authority.noteServerTick(tick + 2000 + i, t + 1260 * MS + (i - 1) * 2 * MS, period);
        }
        final long caughtUp = Authority.lagMillisAt(t + 1310 * MS);
        assertTrue(caughtUp <= 2L, "the catch-up put the clock back on wall time (" + caughtUp + " ms)");
    }
}
