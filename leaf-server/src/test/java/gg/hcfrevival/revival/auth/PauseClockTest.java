package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void theServerIsBehindFromAHitchOnNeverOnItsJitter() {
        // SV-2: a damage window follows the clicks' clock once the server fell a period behind since its hit
        final long period = 70 * MS; // a schedule of its own, as above
        final int tick = 2_000_000_000;
        final long t = 9_000_000 * MS;
        Authority.noteServerTick(tick, t, period);
        for (int i = 1; i <= 200; i++) {
            Authority.noteServerTick(tick + i, t + i * period + (i % 2 == 0 ? 30 : -30) * MS, period);
        }
        assertFalse(Authority.behindSince(tick + 100, t + 200 * period + 40 * MS), "per-tick jitter under a period never puts it behind");
        // A 200 ms hitch in tick 201: behind while it runs, then from that tick on
        assertTrue(Authority.behindSince(tick + 300, t + 201 * period + 150 * MS), "behind while the hitch runs");
        Authority.noteServerTick(tick + 201, t + 201 * period + 200 * MS, period);
        Authority.noteServerTick(tick + 202, t + 201 * period + 202 * MS, period);
        Authority.noteServerTick(tick + 203, t + 201 * period + 204 * MS, period);
        assertTrue(Authority.behindSince(tick + 150, t + 201 * period + 205 * MS), "a window from before the hitch");
        assertTrue(Authority.behindSince(tick + 202, t + 201 * period + 205 * MS), "a window from its catch-up ticks");
        // Caught up
        Authority.noteServerTick(tick + 204, t + 204 * period, period);
        Authority.noteServerTick(tick + 205, t + 205 * period, period);
        assertFalse(Authority.behindSince(tick + 204, t + 205 * period + 10 * MS), "a window from after it");
    }
}
