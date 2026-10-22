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
    void aStalledClientBurstsAtMostTheBank() {
        final TickClock clock = new TickClock();
        final long start = 1_000_000 * MS;
        clock.start(start, PERIOD, 10);
        final long now = start + 2000 * MS; // 2 s without a tick, no acks either (network stall)
        int ran = 0;
        while (clock.tryRun(now, start, PERIOD, DRIFT, 10)) {
            ran++;
        }
        assertEquals(10, ran);
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
