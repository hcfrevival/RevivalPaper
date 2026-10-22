package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.junit.jupiter.api.Test;

/** Design §9 and §15.5 (Grim's balance model, report 06 §9.1). */
@RevivalTest
class TickClockTest {

    private static final long MS = 1_000_000L;
    private static final long PERIOD = 50 * MS;
    private static final long DRIFT = 120 * MS;

    @Test
    void anHonestClientAlwaysRuns() {
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        clock.start(start, PERIOD, 10);
        for (int i = 1; i <= 2000; i++) {
            final long now = start + i * PERIOD + (i % 7) * MS; // jitter
            assertTrue(clock.tryRun(now, now - 40 * MS, PERIOD, DRIFT, 10), "tick " + i);
        }
        assertEquals(0, clock.refused());
    }

    @Test
    void aFastClientRunsOutOfBudget() {
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        clock.start(start, PERIOD, 10);
        int waited = 0;
        for (int i = 1; i <= 400; i++) {
            final long now = start + i * 45 * MS; // 22 ticks a second
            if (!clock.tryRun(now, now - 40 * MS, PERIOD, DRIFT, 10)) {
                waited++;
            }
        }
        assertTrue(waited >= 30, "a 22 TPS client is held back about 2 ticks a second (" + waited + ")");
    }

    @Test
    void aStalledClientBurstsAtMostTheBankTwice() {
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        clock.start(start, PERIOD, 10);
        final long now = start + 2000 * MS; // 2 s without a tick, no acks either (network stall)
        int ran = 0;
        while (clock.tryRun(now, start, PERIOD, DRIFT, 10)) {
            ran++;
        }
        // The bank, then the bank's worth of the time it forfeited, owed to the catch-up ticks right behind (torture F4)
        assertEquals(20, ran);
    }

    @Test
    void aOneSecondStallDrainsInsteadOfStayingBehind() {
        // Torture F4 (marathon r80stall1000w): 20 windows ticked during a 1 s stall arrive at once, then one a period
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        clock.start(start, PERIOD, 10);
        long now = start;
        for (int i = 1; i <= 100; i++) {
            now = start + i * PERIOD;
            assertTrue(clock.tryRun(now, now, now - 40 * MS, PERIOD, DRIFT, 10));
        }
        final long ack = now - 40 * MS;
        now += 1000 * MS;
        int queued = 20;
        while (queued > 0 && clock.tryRun(now, now, ack, PERIOD, DRIFT, 10)) {
            queued--;
        }
        assertEquals(0, queued, "the burst runs at once");
        for (int i = 1; i <= 40; i++) {
            final long t = now + i * PERIOD;
            assertTrue(clock.tryRun(t, t, t - 40 * MS, PERIOD, DRIFT, 10), "and the client is on time again, tick " + i);
        }
        assertTrue(clock.repaidNanos() > 0L);
    }

    @Test
    void aServerHitchIsCreditedFromEachWindowsArrival() {
        // Torture F4 (t_hitch_server): windows came on time during a 1.2 s server hitch and waited in the server's queue
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        clock.start(start, PERIOD, 10);
        long now = start;
        for (int i = 1; i <= 100; i++) {
            now = start + i * PERIOD;
            assertTrue(clock.tryRun(now, now, now - 40 * MS, PERIOD, DRIFT, 10));
        }
        final long hitchStart = now;
        now += 1200 * MS;
        for (int i = 1; i <= 24; i++) {
            final long arrival = hitchStart + i * PERIOD;
            assertTrue(clock.tryRun(now, arrival, hitchStart - 40 * MS, PERIOD, DRIFT, 10), "window " + i + " of the hitch");
        }
        assertEquals(0L, clock.repaidNanos(), "nothing forfeited, so nothing owed");
    }

    @Test
    void theAckClockForfeitsTimeTheClientWasVisiblyAliveFor() {
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        clock.start(start, PERIOD, 10);
        final long now = start + 2000 * MS;
        final long ack = now - 30 * MS; // it answered a ping sent 30 ms ago, so its clock is past it
        int ran = 0;
        while (clock.tryRun(now, ack, PERIOD, DRIFT, 10)) {
            ran++;
        }
        assertEquals(3, ran, "only (drift + 30 ms) / 50 ms of credit is left");
        assertFalse(clock.tryRun(now, ack, PERIOD, DRIFT, 10));
    }
}
