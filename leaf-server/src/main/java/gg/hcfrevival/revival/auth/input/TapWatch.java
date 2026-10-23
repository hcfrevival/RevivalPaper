package gg.hcfrevival.revival.auth.input;

/**
 * Macro w-taps and block-hits (pvp-issues SP-4): evidence for staff ({@code AuthInputAnomalyEvent}), never authority.
 * <p>
 * A tap is what a hit is followed by: forward let go and pressed again (a w-tap), or use pressed and let go (a
 * block-hit). A tap that starts within {@link #MAX_GAP} client ticks of an attack is one sample: its lead (attack to the
 * tap's first tick) and its length, both in client ticks. A macro bound to the click and run on the client's ticks (a
 * cheat client's w-tap module) repeats the same sample every time; a human's timing spread makes a long run of one
 * sample all but impossible (a run of 100 at a 20 ms spread on both gaps: about 1e-21, the SP-4 study). A macro timed in
 * milliseconds lands on two neighbouring ticks with the click's phase in its tick, so it breaks the run and isn't
 * caught: this only proves the tick-locked kind.
 * <p>
 * O(1) per window, no allocation. Main thread, one per player and key.
 */
public final class TapWatch {

    /** Default {@code stats.tap-lock-samples}: identical taps in a row that raise the alert. */
    public static final int LOCK_SAMPLES = 100;

    /** A gap longer than this many client ticks isn't part of a tap (a hit, then walking away or blocking later). */
    static final int MAX_GAP = 10;

    private boolean rest = true;
    private boolean armed;
    private int attackTick;
    private boolean tapping;
    private int tapTick;
    private int lead;

    private int last = -1;
    private int run;
    private int longest;
    private long samples;

    /**
     * One client tick, after its keybinds.
     *
     * @param attacked the tick attacked (an attack before a tap's first tick, or in it, leads it)
     * @param rest     the key at rest: forward held for w-taps, use not held for block-hits
     */
    public void onTick(final int tick, final boolean attacked, final boolean rest) {
        if (attacked) {
            this.armed = true;
            this.attackTick = tick;
        }
        if (this.rest && !rest) {
            // The tap's first tick: led by the last attack, if it's recent and no tap took it yet
            this.tapping = this.armed && tick - this.attackTick <= MAX_GAP;
            this.lead = tick - this.attackTick;
            this.tapTick = tick;
            this.armed = false;
        } else if (!this.rest && rest) {
            if (this.tapping && tick - this.tapTick <= MAX_GAP) {
                this.sample(this.lead * (MAX_GAP + 1) + tick - this.tapTick);
            }
            this.tapping = false;
        }
        this.rest = rest;
    }

    private void sample(final int key) {
        this.run = key == this.last ? this.run + 1 : 1;
        this.last = key;
        this.longest = Math.max(this.longest, this.run);
        this.samples++;
    }

    /** Identical taps in a row, the last one included. */
    public int run() {
        return this.run;
    }

    /** The longest run this session. */
    public int longest() {
        return this.longest;
    }

    /** Taps measured this session. */
    public long samples() {
        return this.samples;
    }

    /** The last tap's lead in client ticks, or -1 before the first. */
    public int lastLead() {
        return this.last < 0 ? -1 : this.last / (MAX_GAP + 1);
    }

    /** The last tap's length in client ticks, or -1 before the first. */
    public int lastLength() {
        return this.last < 0 ? -1 : this.last % (MAX_GAP + 1);
    }
}
