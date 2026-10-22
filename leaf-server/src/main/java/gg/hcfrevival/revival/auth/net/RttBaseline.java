package gg.hcfrevival.revival.auth.net;

import java.util.Arrays;

/**
 * A connection's base round trip, from its ack round trips (§15.1): a low percentile, never the latest pong.
 * <p>
 * Two windows over the same samples. The slow one is the last {@value #SLOW} acks, about 13 s at one ack per server
 * tick: its p10 and p90 are the base and the high. Alone, its p10 trails a ping that goes up: it moves only once nine in
 * ten of its samples are new, so a player whose route went from 45 to 300 ms kept 45 ms ack deadlines for about 12 s,
 * had every logged entry force-applied, and its mirrors went inexact (its hits UNKNOWN). The fast one is the last
 * {@value #FAST} acks: when its p10 is above the slow p10, it is the base. So the base rises once 29 of the last 32 acks
 * are above it (about 1.5 s of acks), and falls through the slow window, once a tenth of the last 256 are below it (and
 * 4 of the last 32). A lone spike either way moves neither p10; a stall's drain (its queued pings answered at once,
 * oldest first) lifts the fast p10 for the 4 acks after it.
 * <p>
 * Each window is a ring plus the same values kept sorted: a sample is one binary search and one array shift out and in,
 * so the percentiles are exact on every ack and nothing is allocated. Main thread (the owner's ack lock).
 */
public final class RttBaseline {

    static final int SLOW = 256;
    static final int FAST = 32;

    private final Window slow = new Window(SLOW);
    private final Window fast = new Window(FAST);
    private long base;
    private long high;

    /** @param initialNanos the base and high before the first sample */
    public RttBaseline(final long initialNanos) {
        this.base = initialNanos;
        this.high = initialNanos;
    }

    /** One ack's round trip, in nanoseconds. */
    public void sample(final long rttNanos) {
        this.slow.add(rttNanos);
        this.fast.add(rttNanos);
        this.base = Math.max(this.slow.low(), this.fast.low());
        this.high = Math.max(this.slow.high(), this.base);
    }

    /** The base round trip: the slow window's p10, or the fast window's when that is higher. */
    public long base() {
        return this.base;
    }

    /** The p90 of the slow window (at least {@link #base}): with the base, the connection's own jitter. */
    public long high() {
        return this.high;
    }

    /** The last {@code capacity} samples, as a ring and sorted. */
    private static final class Window {

        private final long[] ring;
        private final long[] sorted;
        private int size;
        private int next;

        Window(final int capacity) {
            this.ring = new long[capacity];
            this.sorted = new long[capacity];
        }

        void add(final long value) {
            if (this.size == this.ring.length) {
                // The evicted sample is in the sorted copy: any equal value is the same one to drop
                final int at = Arrays.binarySearch(this.sorted, 0, this.size, this.ring[this.next]);
                System.arraycopy(this.sorted, at + 1, this.sorted, at, this.size - at - 1);
                this.size--;
            }
            this.ring[this.next] = value;
            this.next = (this.next + 1) % this.ring.length;
            final int found = Arrays.binarySearch(this.sorted, 0, this.size, value);
            final int at = found < 0 ? -found - 1 : found;
            System.arraycopy(this.sorted, at, this.sorted, at + 1, this.size - at);
            this.sorted[at] = value;
            this.size++;
        }

        // The percentile indices rttBase has always used: p10 = sorted[n / 10], p90 = sorted[min(n - 1, 9n / 10)]
        long low() {
            return this.sorted[this.size / 10];
        }

        long high() {
            return this.sorted[Math.min(this.size - 1, this.size * 9 / 10)];
        }
    }
}
