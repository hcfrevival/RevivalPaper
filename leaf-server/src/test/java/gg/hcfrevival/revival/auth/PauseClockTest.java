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

    // SV-2: a melee window follows the clicks' clock only while a stall parts the ticks from real time, two periods or
    // more, measured on the tick starts of its own span. Each test has a tick period of its own: a new period clears the
    // starts kept before
    private static final long TICK = 50 * MS;

    // Ticks from..to (inclusive) starting on schedule from `first`, each 0-2 ms off it as Paper's loop wakes
    private static long onSchedule(final int from, final int to, final long first, final long period) {
        for (int i = from; i <= to; i++) {
            Authority.noteTickStart(i, first + (i - from) * period + (i % 3) * MS, period);
        }
        return first + (to - from) * period; // the last one's slot
    }

    @Test
    void aSkippedStallLeavesNoLagBehind() {
        // A 3 s stall in tick 100, then the skip vanilla and Paper make past their catch-up: the tick counter moves one
        // while 3 s pass, and the ticks after run on schedule. The lag clock above keeps those 3 s for the session
        final long period = TICK + 1;
        final long stalled = onSchedule(0, 100, 11_000_000 * MS, period);
        assertTrue(Authority.behindNow(stalled + 3_000 * MS), "in the stall");
        long at = onSchedule(101, 101, stalled + 3_005 * MS, period);
        assertTrue(Authority.behindSince(95, at + 10 * MS), "a window across it: its counter lost the 3 s");
        at = onSchedule(102, 102, at + period, period);
        assertFalse(Authority.behindNow(at + 10 * MS), "from the next tick on schedule");
        assertFalse(Authority.behindSince(101, at + 10 * MS), "a window from the tick after the skip");
        at = onSchedule(103, 140, at + period, period);
        assertFalse(Authority.behindNow(at + 10 * MS));
        assertFalse(Authority.behindSince(120, at + 10 * MS), "a window from later");
        assertTrue(Authority.behindSince(95, at + 10 * MS), "the window across it, still");
        at = onSchedule(141, 200, at + period, period);
        assertFalse(Authority.behindSince(95, at + 10 * MS), "64 ticks on, out of the starts kept: as on schedule");
        assertFalse(Authority.behindSince(180, at + 10 * MS));
    }

    @Test
    void aStallReadsBehindThroughItsCatchUpAndInTheWindowsItCompressed() {
        // A 200 ms stall in tick 100 (a plugin's hitch, as in cheat suite 2's server-lag row), then Paper's catch-up:
        // ticks 101-104 back to back, on schedule from 105
        final long period = TICK + 2;
        final long stalled = onSchedule(0, 100, 13_000_000 * MS, period);
        assertFalse(Authority.behindNow(stalled + 140 * MS), "a long tick so far");
        assertTrue(Authority.behindNow(stalled + 160 * MS), "a stall");
        // The clicks queued behind it run before tick 101 starts: a window from before the stall reads behind
        assertTrue(Authority.behindSince(97, stalled + 205 * MS), "the burst, a window from before");
        Authority.noteTickStart(101, stalled + 206 * MS, period);
        assertTrue(Authority.behindSince(97, stalled + 207 * MS), "catching up");
        Authority.noteTickStart(102, stalled + 208 * MS, period);
        Authority.noteTickStart(103, stalled + 210 * MS, period);
        Authority.noteTickStart(104, stalled + 212 * MS, period);
        assertFalse(Authority.behindNow(stalled + 213 * MS), "the stall is over");
        // Back on schedule. A window across the stall stays on the clicks' clock for good (its counter took the clock's
        // value in the stall; the catch-up ticks would count the stall's time again), the one dealt in the burst was
        // counted down early by the catch-up, as was one from the catch-up's second tick; one from after it is on schedule
        for (int i = 105; i <= 120; i++) {
            final long at = onSchedule(i, i, stalled + (i - 100) * period, period);
            assertTrue(Authority.behindSince(97, at + 10 * MS), "a window across the stall, tick " + i);
            assertTrue(Authority.behindSince(101, at + 10 * MS), "the burst's window, tick " + i);
            assertTrue(Authority.behindSince(102, at + 10 * MS), "the catch-up's window, tick " + i);
            assertFalse(Authority.behindSince(105, at + 10 * MS), "a window from after it, tick " + i);
        }
    }

    @Test
    void aLongTickIsNoStall() {
        // A tick that runs 50 ms over (a reload, a chunk burst): the counter is a tick off at most, and the next ticks
        // catch it up. Neither the clicks' clock nor a held click's wait starts for it
        final long period = TICK + 3;
        final long longTick = onSchedule(0, 100, 15_000_000 * MS, period);
        assertFalse(Authority.behindNow(longTick + 100 * MS), "100 ms into the tick");
        Authority.noteTickStart(101, longTick + 100 * MS, period);
        assertFalse(Authority.behindSince(98, longTick + 110 * MS), "the next tick, 50 ms late");
        long at = onSchedule(102, 110, longTick + 2 * period, period);
        assertFalse(Authority.behindSince(101, at + 10 * MS), "a window from the late tick");
        // A stall is two periods past the tick's own
        assertFalse(Authority.behindNow(at + 145 * MS));
        assertTrue(Authority.behindNow(at + 155 * MS), "150 ms into a tick: a stall");
    }
}
