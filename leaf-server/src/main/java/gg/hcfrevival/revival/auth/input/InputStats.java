package gg.hcfrevival.revival.auth.input;

/**
 * Click and aim statistics for staff (design §10): measured, not enforced. Click timing and aim are the player's input;
 * nobody can make them authoritative, so they are bounded ({@link InputEnvelope}) and described here.
 * <ul>
 *   <li>attacks per client tick (histogram 0..4, 5+), intervals between attack ticks (1..19, 20+), double clicks
 *       (two attacks in one tick);</li>
 *   <li>yaw deltas between ticks and the largest step that divides them all ({@code sensitivity GCD}): a vanilla mouse
 *       turns in multiples of one step per sensitivity, so a poor fit over many turns points at synthetic aim.</li>
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
    private long turns;
    private double yawGcd;
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
                final double delta = Math.abs((double) yaw - this.lastYaw);
                if (delta > 1.0E-4 && delta < 180.0) {
                    this.turns++;
                    this.fitGcd(delta);
                }
            }
            this.lastYaw = yaw;
            this.hasYaw = true;
        }
    }

    // A running float GCD with a tolerance: steps below 1e-3 degrees mean the deltas share no mouse step
    private void fitGcd(final double delta) {
        if (this.yawGcd == 0.0) {
            this.yawGcd = delta;
            return;
        }
        double a = Math.max(this.yawGcd, delta);
        double b = Math.min(this.yawGcd, delta);
        while (b > 1.0E-3) {
            final double r = a % b;
            a = b;
            b = r;
        }
        if (a < 1.0E-3) {
            this.gcdMisses++;
        } else {
            this.yawGcd = a;
        }
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
}
