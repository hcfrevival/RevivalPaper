package gg.hcfrevival.revival.auth.input;

import java.util.Arrays;

/**
 * Rewind that the attacker's own connection doesn't explain (pvp-issues HR-3): evidence for staff
 * ({@code AuthInputAnomalyEvent} {@code rewind-stretch}), never authority.
 * <p>
 * Every valid melee hit is judged on the target box the attacker's client drew, {@code rewind} milliseconds old. An
 * honest client's rewind is its round trip plus the display delay its mirror measured, plus jitter and tick waits; the
 * excess is what the base round trip ({@code rttBase}, p10) and the display don't explain. A backtrack cheat that holds
 * its pongs around its swings keeps its base low and its acked view old when it hits, so it spends the rewind cap's
 * slack ({@code rewind-slack-ms}, 150) on every hit; one that stalls on purpose widens the cap's jitter term for the 13 s
 * its 256-ack window remembers the stall, and spends that. The alert reads the median excess of the last N valid hits
 * ({@code rewind-stretch-hits}, 30) against {@code rewind-stretch-ms} (100). Real clients through the torture proxy
 * (runs {@code grid1} and {@code audit4-marathon}): median excess 5-61 ms on steady links up to 30 ms of jitter (p99
 * under 135), 26-76 ms through 300 ms stalls every 4-5 s and 1 s stalls every 15 s.
 * <p>
 * O(1) per hit but the median's sort of N values, and hits are rare. One per attacker, main thread.
 */
public final class RewindWatch {

    /** {@code stats.rewind-stretch-hits} and {@code rewind-stretch-ms} defaults. */
    public static final int HITS = 30;
    public static final long STRETCH_MS = 100L;

    private long[] ring = new long[HITS];
    private long[] scratch = new long[HITS];
    private int size;
    private int next;
    private long median;
    private long highest = Long.MIN_VALUE;
    private long hits;

    /** One valid hit's excess rewind in milliseconds; {@code window}: the hits the median spans. */
    public void onHit(final long excessMillis, final int window) {
        if (this.ring.length != window) {
            // A new rewind-stretch-hits: start over at the new size
            this.ring = new long[window];
            this.scratch = new long[window];
            this.size = 0;
            this.next = 0;
        }
        this.ring[this.next] = excessMillis;
        this.next = (this.next + 1) % window;
        this.size = Math.min(window, this.size + 1);
        this.hits++;
        System.arraycopy(this.ring, 0, this.scratch, 0, this.size);
        Arrays.sort(this.scratch, 0, this.size);
        this.median = this.scratch[this.size / 2];
        if (this.size == window) {
            this.highest = Math.max(this.highest, this.median);
        }
    }

    /** The median excess of the last hits (up to the window), in milliseconds; 0 before the first. */
    public long median() {
        return this.median;
    }

    /** Whether the window is full, so the median is judged. */
    public boolean full() {
        return this.size == this.ring.length;
    }

    /** The highest median over a full window this session, or {@code Long.MIN_VALUE}. */
    public long highest() {
        return this.highest;
    }

    /** Valid hits measured this session. */
    public long hits() {
        return this.hits;
    }
}
