package gg.hcfrevival.revival.auth.input;

/**
 * Click and aim statistics for staff (design §10): measured, not enforced. Click timing and aim are the player's input;
 * nobody can make them authoritative, so they are bounded ({@link InputEnvelope}) and described here.
 * <ul>
 *   <li>attacks per client tick (histogram 0..4, 5+), intervals between attack ticks (1..19, 20+), double clicks
 *       (two attacks in one tick);</li>
 *   <li>yaw deltas between ticks and the largest step that divides them all ({@code sensitivity GCD}): a vanilla mouse
 *       turns in multiples of one step per sensitivity, so a poor fit over many turns points at synthetic aim. Two steps
 *       are allowed per session (a zoom with its own sensitivity, Lunar's included), and turns whose change from the
 *       previous turn is under the step are skipped: a smoothed camera eases rotation in fractions of a step
 *       (client-compat N7).</li>
 * </ul>
 */
public final class InputStats {

    private final long[] attacksPerTick = new long[6];
    private final long[] attackIntervals = new long[21];
    private long attackTicks;
    private long doubleClicks;
    private int lastAttackTick = Integer.MIN_VALUE;

    private float lastYaw;
    private boolean hasYaw;
    private double lastDelta;
    private boolean hasDelta;
    private long turns;
    private long smoothedTurns;
    private double yawGcd;
    private double secondGcd;
    private long gcdMisses;

    public void onTick(final int tick, final int attacks, final boolean hasRotation, final float yaw) {
        this.attacksPerTick[Math.min(attacks, 5)]++;
        if (attacks > 0) {
            this.attackTicks++;
            if (attacks > 1) {
                this.doubleClicks++;
            }
            if (this.lastAttackTick != Integer.MIN_VALUE) {
                this.attackIntervals[Math.min(tick - this.lastAttackTick, 20)]++;
            }
            this.lastAttackTick = tick;
        }
        if (hasRotation) {
            if (this.hasYaw) {
                final double signed = (double) yaw - this.lastYaw;
                final double delta = Math.abs(signed);
                if (delta > NOISE && delta < 180.0) {
                    final boolean easing = this.hasDelta && Math.signum(signed) == Math.signum(this.lastDelta) && delta < Math.abs(this.lastDelta);
                    if (easing && !onGrid(delta, this.yawGcd) && !onGrid(delta, this.secondGcd)) {
                        this.smoothedTurns++; // a turn easing out off the step: a smoothed camera, not the mouse (N7)
                    } else {
                        this.turns++;
                        this.fitGcd(delta);
                    }
                    this.lastDelta = signed;
                    this.hasDelta = true;
                } else {
                    this.hasDelta = false;
                }
            }
            this.lastYaw = yaw;
            this.hasYaw = true;
        }
    }

    /** The smallest yaw a vanilla mouse count turns ({@code MouseHandler.turnPlayer} at sensitivity 0): (0.2)^3 * 8 * 0.15. */
    static final double MIN_STEP = 0.0096;
    /** Float noise on a yaw delta (a float yaw near 360 has an ulp of about 3e-5). */
    static final double NOISE = 2.0E-4;

    private static boolean onGrid(final double delta, final double step) {
        if (step < MIN_STEP) {
            return false;
        }
        final double r = delta % step;
        return r < NOISE || step - r < NOISE;
    }

    // The mouse step of each sensitivity, learned from the turns: a turn on a step's grid fits it; one off the grid may
    // refine the step to a whole fraction of itself (the first turns seen can be multiples of it), never to an arbitrary
    // float GCD, which noise would drive to anything. The second sensitivity (a zoom) gets its own step; a turn that
    // fits neither is a miss
    private void fitGcd(final double delta) {
        if (this.yawGcd == 0.0) {
            this.yawGcd = delta;
            return;
        }
        final double first = refine(this.yawGcd, delta);
        if (first > 0.0) {
            this.yawGcd = first;
            return;
        }
        if (this.secondGcd == 0.0) {
            this.secondGcd = delta;
            return;
        }
        final double second = refine(this.secondGcd, delta);
        if (second > 0.0) {
            this.secondGcd = second;
        } else {
            this.gcdMisses++;
        }
    }

    /** The step after {@code delta}: {@code step} when it is on its grid, a whole fraction of it that fits both, else 0. */
    static double refine(final double step, final double delta) {
        if (onGrid(delta, step)) {
            return step;
        }
        final double g = gcd(step, delta);
        if (g < MIN_STEP) {
            return 0.0;
        }
        final double parts = step / g;
        return parts <= MAX_PARTS && Math.abs(parts - Math.rint(parts)) * g < NOISE * 2.0 && onGrid(delta, g) ? g : 0.0;
    }

    /** The most a learned step is split into when a turn shows a finer one. */
    static final int MAX_PARTS = 32;

    static double gcd(final double x, final double y) {
        double a = Math.max(x, y);
        double b = Math.min(x, y);
        while (b > NOISE) {
            double r = a % b;
            if (b - r < NOISE) {
                r = 0.0; // a whole multiple, short by float noise
            }
            a = b;
            b = r;
        }
        return a;
    }

    public long attacksPerTick(final int bucket) {
        return this.attacksPerTick[Math.clamp(bucket, 0, 5)];
    }

    public long attackInterval(final int ticks) {
        return this.attackIntervals[Math.clamp(ticks, 0, 20)];
    }

    public long attackTicks() {
        return this.attackTicks;
    }

    public double doubleClickRatio() {
        return this.attackTicks == 0 ? 0.0 : (double) this.doubleClicks / this.attackTicks;
    }

    public long turns() {
        return this.turns;
    }

    public double yawGcd() {
        return this.yawGcd;
    }

    public long gcdMisses() {
        return this.gcdMisses;
    }

    /** The second sensitivity's step (a zoom), or 0. */
    public double secondGcd() {
        return this.secondGcd;
    }

    /** Turns skipped as a smoothed camera's easing. */
    public long smoothedTurns() {
        return this.smoothedTurns;
    }
}
