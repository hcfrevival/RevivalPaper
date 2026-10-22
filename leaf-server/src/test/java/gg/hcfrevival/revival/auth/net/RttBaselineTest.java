package gg.hcfrevival.revival.auth.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * rttBase follows a ping that went up within about 1.5 s (one ack per server tick), ignores spikes and a stall's drain,
 * and falls through the slow window. Synthetic ack round trips, in milliseconds; one sample is one 50 ms tick.
 */
@RevivalTest
class RttBaselineTest {

    private static final long MS = 1_000_000L;

    private final RttBaseline rtt = new RttBaseline(100 * MS);

    private void feed(final long millis, final int acks) {
        for (int i = 0; i < acks; i++) {
            this.rtt.sample(millis * MS);
        }
    }

    private long base() {
        return this.rtt.base() / MS;
    }

    // The ack deadline in client ticks at the live default (AuthPlayer: ceil(min(rttBase, rtt-ceiling-ms) / 50) + 4)
    private int deadline() {
        return (int) Math.ceil(Math.min(this.rtt.base(), 250 * MS) / (50.0 * MS)) + 4;
    }

    @Test
    void theFirstAckReplacesTheStartingGuess() {
        assertEquals(100, this.base());
        this.feed(40, 1);
        assertEquals(40, this.base());
        assertEquals(40, this.rtt.high() / MS);
    }

    @Test
    void aStepUpFrom45To300MsSettlesWithinTwoSeconds() {
        this.feed(45, 300);
        assertEquals(45, this.base());
        int settledAt = -1;
        for (int ack = 1; ack <= 300; ack++) {
            this.feed(300, 1);
            if (ack < 29) {
                assertEquals(45, this.base(), "28 high acks of the last 32 are not yet consistent (ack " + ack + ")");
            }
            if (settledAt < 0 && this.base() == 300) {
                settledAt = ack;
            }
            if (settledAt > 0) {
                assertEquals(300, this.base(), "it stays up (ack " + ack + ")");
            }
        }
        assertEquals(29, settledAt, "29 of the last 32 acks above it: 1.45 s at one ack per tick");
        // 300 ms acks come back in about 7 client ticks; the deadline is now 9 (the ceiling's 5 + 4), so none is forced
        assertEquals(9, this.deadline());
    }

    @Test
    void theSlowWindowAloneWouldHaveTakenTwelveSeconds() {
        // What rttBase was before the fast window: the slow p10 rises only once 231 of 256 samples are new
        this.feed(45, 300);
        this.feed(300, 230);
        assertEquals(300, this.base(), "the fast window carries it");
        assertEquals(300, this.rtt.high() / MS);
    }

    @Test
    void aStepDownFallsThroughTheSlowWindow() {
        this.feed(300, 300);
        int fellAt = -1;
        for (int ack = 1; ack <= 100; ack++) {
            this.feed(45, 1);
            if (ack < 26) {
                assertEquals(300, this.base(), "a tenth of the 256 are not lower yet (ack " + ack + ")");
            }
            if (fellAt < 0 && this.base() == 45) {
                fellAt = ack;
            }
        }
        assertEquals(26, fellAt, "a tenth of the last 256: 1.3 s of acks");
        assertEquals(300, this.rtt.high() / MS, "the p90 still remembers the old ping: the jitter allowance stays wide");
    }

    @Test
    void lonePingSpikesMoveNothing() {
        this.feed(45, 300);
        this.feed(900, 1);
        assertEquals(45, this.base(), "one spike up");
        this.feed(45, 40);
        this.feed(5, 1);
        assertEquals(45, this.base(), "one spike down");
        for (int i = 0; i < 400; i++) {
            this.feed(i % 10 == 0 ? 1200 : 45, 1); // every tenth ack lost in a Wi-Fi retry
        }
        assertEquals(45, this.base());
        this.feed(45, 40);
        this.feed(600, 25); // 25 bad acks in a row (1.25 s): still under 29 of 32
        assertEquals(45, this.base());
    }

    @Test
    void jitterKeepsTheBaseAtTheLowEnd() {
        final Random random = new Random(263L);
        long highest = 0;
        long sum = 0;
        for (int i = 0; i < 2000; i++) {
            this.rtt.sample((40 + random.nextInt(41)) * MS); // 40-80 ms, uniform
            if (i >= 256) {
                highest = Math.max(highest, this.base());
                sum += this.base();
                assertTrue(this.base() >= 40, "base " + this.base());
            }
        }
        final double mean = sum / (2000.0 - 256.0);
        assertTrue(mean >= 42.0 && mean <= 48.0, "the base sits at the low end of 40-80 ms (mean " + mean + ")");
        assertTrue(highest < 60, "never up to the median (highest " + highest + ")");
        assertTrue(this.rtt.high() / MS >= 72 && this.rtt.high() / MS <= 80, "p90 " + this.rtt.high() / MS);
    }

    @Test
    void aStallsDrainLiftsTheBaseForFourAcksOnly() {
        this.feed(45, 300);
        // A 4.5 s stall: 90 queued pings answered at once, oldest first (round trips 4.5 s down to 50 ms)
        for (int i = 90; i >= 1; i--) {
            this.feed(i * 50L + 45, 1);
        }
        assertTrue(this.base() <= 250, "base " + this.base());
        this.feed(45, 4);
        assertEquals(45, this.base(), "four fresh acks bring it back");
    }

    @Test
    void matchesASortOfTheSamplesOnEveryAck() {
        final Random random = new Random(42L);
        final long[] history = new long[3000];
        for (int n = 0; n < history.length; n++) {
            // Steps, plateaus and noise, with repeats (the sorted copy drops one of several equal values)
            final long millis = (n / 400 % 2 == 0 ? 50 : 220) + random.nextInt(8) * 10L;
            history[n] = millis * MS;
            this.rtt.sample(history[n]);
            final int count = n + 1;
            final long[] slow = Arrays.copyOfRange(history, Math.max(0, count - 256), count);
            final long[] fast = Arrays.copyOfRange(history, Math.max(0, count - 32), count);
            Arrays.sort(slow);
            Arrays.sort(fast);
            final long expected = Math.max(slow[slow.length / 10], fast[fast.length / 10]);
            assertEquals(expected, this.rtt.base(), "ack " + n);
            assertEquals(Math.max(slow[Math.min(slow.length - 1, slow.length * 9 / 10)], expected), this.rtt.high(), "ack " + n);
        }
    }

    @Test
    void samplingAllocatesNothing() {
        this.feed(45, 600);
        final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long bytes0 = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 2000; i++) {
            this.rtt.sample((45 + (i * 7919) % 300) * MS);
        }
        final long bytes = threads.getCurrentThreadAllocatedBytes() - bytes0;
        assertTrue(bytes < 400, bytes + " bytes in 2000 acks");
    }
}
