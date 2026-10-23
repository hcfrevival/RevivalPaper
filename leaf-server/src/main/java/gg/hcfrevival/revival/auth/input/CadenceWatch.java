package gg.hcfrevival.revival.auth.input;

/**
 * The locked-cadence detectors (neue-combat §12.2): evidence for staff ({@code AuthInputAnomalyEvent}), never authority.
 * <ul>
 *   <li>A, {@code click-streak}: the run of consecutive eligible windows with exactly one click ({@code Punch}). A 20 CPS
 *       lock or a one-per-tick macro holds it; humans don't for {@code click-streak-ticks} (100, 5 s).</li>
 *   <li>B, {@code click-lock}: any fixed rate. Over the last N intervals between clicks ({@code click-lock-clicks}, 150)
 *       of an unbroken eligible chain, every interval is k or k+1 ticks, the mean is at most 20 / {@code click-lock-min-cps}
 *       ticks (2.5), and no click is more than {@code click-lock-residual} (0.75) ticks off the least-squares line
 *       through them. A fixed rate sampled on the tick grid is a balanced sequence, never about a tick off its line; a
 *       human's timing drifts off it within a few dozen clicks.</li>
 * </ul>
 * An ineligible window (refused or exempt, dead, using an item at its keybinds, a container open, digging) resets both:
 * that can only lower detection. O(1) per window; B's O(N) fit runs only while every interval is k or k+1 and the mean
 * is fast enough, so most players never pay it. No allocation but the ring, which a new N re-sizes. Main thread, per
 * player.
 */
public final class CadenceWatch {

    /** neue-combat §12.2's defaults. */
    public static final int LOCK_CLICKS = 150;
    public static final double LOCK_RESIDUAL = 0.75;
    public static final double LOCK_MIN_CPS = 8.0;

    // A second between two clicks: past it the chain starts over (no rate B looks for is that slow)
    private static final int MAX_INTERVAL = 20;

    private int streak;
    private int longest;

    // B: the last N + 1 click ticks of the chain on the window counter, oldest at `first`, and its intervals by value
    private int[] ticks = new int[LOCK_CLICKS + 1];
    private int size;
    private int first;
    private final int[] intervals = new int[MAX_INTERVAL + 1];
    private int window;
    private int locked;
    private int longestLock;

    /** One window, with B on neue-combat §12.2's defaults. */
    public int onWindow(final boolean eligible, final int clicks) {
        return this.onWindow(eligible, clicks, LOCK_CLICKS, LOCK_RESIDUAL, LOCK_MIN_CPS);
    }

    /**
     * One window. {@code eligible}: it ran (not refused or exempt), the player is alive, not using an item at its
     * keybinds, no container open, no digging in the window. Two clicks in one window are an interval of 0. Returns A's
     * current run.
     */
    public int onWindow(final boolean eligible, final int clicks, final int lockClicks, final double lockResidual, final double lockMinCps) {
        if (!eligible) {
            this.streak = 0;
            this.breakChain();
            return 0;
        }
        if (clicks == 1) {
            this.streak++;
            this.longest = Math.max(this.longest, this.streak);
        } else {
            this.streak = 0;
        }
        this.window++;
        for (int i = 0; i < clicks; i++) {
            this.click(lockClicks, lockResidual, lockMinCps);
        }
        return this.streak;
    }

    private void click(final int n, final double residual, final double minCps) {
        if (this.ticks.length != n + 1) {
            this.ticks = new int[n + 1]; // a new click-lock-clicks: the chain starts over
            this.breakChain();
        }
        final int len = this.ticks.length;
        if (this.size > 0) {
            final int interval = this.window - this.ticks[(this.first + this.size - 1) % len];
            if (interval > MAX_INTERVAL) {
                this.breakChain();
            } else {
                if (this.size == len) {
                    // The oldest click leaves, and the interval after it
                    this.intervals[this.ticks[(this.first + 1) % len] - this.ticks[this.first]]--;
                    this.first = (this.first + 1) % len;
                    this.size--;
                }
                this.intervals[interval]++;
            }
        }
        this.ticks[(this.first + this.size) % len] = this.window;
        this.size++;
        if (this.size == len && this.spread() <= 1 && (this.window - this.ticks[this.first]) * minCps <= 20.0 * n
            && this.maxResidual() <= residual) {
            this.locked = this.locked == 0 ? n : this.locked + 1;
            this.longestLock = Math.max(this.longestLock, this.locked);
        } else {
            this.locked = 0;
        }
    }

    private void breakChain() {
        this.size = 0;
        this.first = 0;
        this.locked = 0;
        java.util.Arrays.fill(this.intervals, 0);
    }

    // Largest interval less the smallest, from the counts
    private int spread() {
        int min = -1;
        int max = -1;
        for (int v = 0; v <= MAX_INTERVAL; v++) {
            if (this.intervals[v] > 0) {
                if (min < 0) {
                    min = v;
                }
                max = v;
            }
        }
        return max - min;
    }

    // The ring's clicks against their least-squares line t ≈ a + b·i: the largest distance, in ticks
    private double maxResidual() {
        final int len = this.ticks.length;
        final int base = this.ticks[this.first];
        double sumT = 0.0;
        double sumIT = 0.0;
        for (int i = 0; i < len; i++) {
            final int t = this.ticks[(this.first + i) % len] - base;
            sumT += t;
            sumIT += (double) i * t;
        }
        final double meanI = (len - 1) / 2.0;
        final double meanT = sumT / len;
        final double b = (sumIT - len * meanI * meanT) / (len * ((double) len * len - 1.0) / 12.0);
        final double a = meanT - b * meanI;
        double max = 0.0;
        for (int i = 0; i < len; i++) {
            max = Math.max(max, Math.abs(this.ticks[(this.first + i) % len] - base - (a + b * i)));
        }
        return max;
    }

    public int streak() {
        return this.streak;
    }

    public int longest() {
        return this.longest;
    }

    /** B: the clicks of the current lock (N at its first click, one more each click it holds), else 0. */
    public int locked() {
        return this.locked;
    }

    public int longestLock() {
        return this.longestLock;
    }
}
