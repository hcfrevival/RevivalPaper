package gg.hcfrevival.revival.auth;

/**
 * Counts over the last {@link #SECONDS} seconds in one-second buckets (the playtest HUD's "last 10 s" figures).
 * Allocation-free; main thread.
 */
public final class RollingStats {

    public static final int SECONDS = 10;

    private final long[] second = new long[SECONDS];
    private final int[] compared = new int[SECONDS];
    private final int[] exact = new int[SECONDS];
    private final int[] corrections = new int[SECONDS];

    private int bucket(final long nowMillis) {
        final long now = nowMillis / 1000L;
        final int index = (int) Math.floorMod(now, (long) SECONDS);
        if (this.second[index] != now) {
            this.second[index] = now;
            this.compared[index] = 0;
            this.exact[index] = 0;
            this.corrections[index] = 0;
        }
        return index;
    }

    public void compared(final boolean wasExact, final long nowMillis) {
        final int index = this.bucket(nowMillis);
        this.compared[index]++;
        if (wasExact) {
            this.exact[index]++;
        }
    }

    public void correction(final long nowMillis) {
        this.corrections[this.bucket(nowMillis)]++;
    }

    private boolean live(final int index, final long nowMillis) {
        return nowMillis / 1000L - this.second[index] < SECONDS;
    }

    /** Claims compared in the window. */
    public int compared(final long nowMillis) {
        int sum = 0;
        for (int i = 0; i < SECONDS; i++) {
            if (this.live(i, nowMillis)) {
                sum += this.compared[i];
            }
        }
        return sum;
    }

    /** Share of compared claims that were exact in the window, or NaN when none were compared. */
    public double exactShare(final long nowMillis) {
        int all = 0;
        int ok = 0;
        for (int i = 0; i < SECONDS; i++) {
            if (this.live(i, nowMillis)) {
                all += this.compared[i];
                ok += this.exact[i];
            }
        }
        return all == 0 ? Double.NaN : (double) ok / all;
    }

    public int corrections(final long nowMillis) {
        int sum = 0;
        for (int i = 0; i < SECONDS; i++) {
            if (this.live(i, nowMillis)) {
                sum += this.corrections[i];
            }
        }
        return sum;
    }
}
