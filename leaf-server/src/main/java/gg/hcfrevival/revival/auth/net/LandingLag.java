package gg.hcfrevival.revival.auth.net;

/**
 * Where a packet sent now lands on the client, in client windows: {@code pong window - the window the client was in when
 * the ping went out} (by real time, {@code Transactions.Context.windowTick}), measured per flush kind (NAS glitch
 * 2026-10-22, torture r60j0). A correction made while the server runs window {@code w} lands in
 * {@code (window the client is in - w) + this}: the windows already queued, then the trip (§15.5).
 * <p>
 * Why measured: the client applies a packet before the first tick that starts after it arrives, so the trip is
 * {@code ceil((client tick to TickEnd + round trip + server wait to the flush) / 50) - 1} windows past the current one,
 * which {@code round(rtt / 50)} misses: at a round trip of 60 ms (1.2 ticks) every correction landed one tick later
 * than forecast, and the accept backstop chased the client every other tick for as long as it moved (a slow server's
 * wait does the same at 30 ms). The two flush kinds wait differently: windows run between server ticks flush as soon as
 * they finish, the server tick's bundle at the tick's end. Measured from the client's window, not the one the server
 * runs, so windows queued behind a stall never count as trip.
 * <p>
 * Main thread only (samples come from {@code Transactions.ack}, under its lock, on the main thread).
 */
public final class LandingLag {

    /** Sent while windows run between server ticks (or outside any scope): flushed as the scope ends. */
    public static final int SCOPE = 0;
    /** Sent inside the server tick: flushed with the tick's bundle at its end. */
    public static final int TICK = 1;
    static final int KINDS = 2;
    /** Samples kept per kind; the estimate is their median. */
    static final int SAMPLES = 16;
    /** A lag past this is a stall, not a landing time: never sampled. */
    static final int MAX_LAG = 64;

    private final int[][] lags = new int[KINDS][SAMPLES];
    private final long[][] at = new long[KINDS][SAMPLES];
    private final int[] next = new int[KINDS];
    private final int[] count = new int[KINDS];
    private final long[] last = new long[KINDS];
    private final int[] scratch = new int[SAMPLES];

    /** A ping flushed by {@code kind} came back {@code lag} windows after the one the client was in at its flush. */
    public void sample(final int kind, final int lag, final long nowNanos) {
        if (kind < 0 || kind >= KINDS || lag < 0 || lag > MAX_LAG) {
            return; // not before the window it was sent in; one past MAX_LAG waited out a stall
        }
        final int slot = this.next[kind];
        this.lags[kind][slot] = lag;
        this.at[kind][slot] = nowNanos;
        this.next[kind] = (slot + 1) % SAMPLES;
        this.count[kind] = Math.min(SAMPLES, this.count[kind] + 1);
        this.last[kind] = nowNanos;
    }

    /** The (lower) median of {@code kind}'s samples taken within {@code maxAgeNanos}, or -1 when there are none. */
    public int estimate(final int kind, final long nowNanos, final long maxAgeNanos) {
        if (kind < 0 || kind >= KINDS) {
            return -1;
        }
        final int[] values = this.scratch;
        int n = 0;
        for (int i = 0; i < this.count[kind]; i++) {
            if (nowNanos - this.at[kind][i] > maxAgeNanos) {
                continue;
            }
            // Insertion sort into scratch: at most 16 values, no allocation on the correction path
            final int v = this.lags[kind][i];
            int j = n++;
            while (j > 0 && values[j - 1] > v) {
                values[j] = values[j - 1];
                j--;
            }
            values[j] = v;
        }
        return n == 0 ? -1 : values[(n - 1) / 2];
    }

    /** When {@code kind} was last sampled ({@code System.nanoTime}), or 0. */
    public long lastSampleNanos(final int kind) {
        return kind < 0 || kind >= KINDS ? 0L : this.last[kind];
    }

    public void clear() {
        for (int k = 0; k < KINDS; k++) {
            this.count[k] = 0;
            this.next[k] = 0;
            this.last[k] = 0L;
        }
    }
}
