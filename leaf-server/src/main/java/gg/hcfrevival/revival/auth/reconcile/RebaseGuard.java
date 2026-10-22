package gg.hcfrevival.revival.auth.reconcile;

/**
 * The drift cap on adopted claims when {@code rebase-epsilon} is on (design §15.5). Each adopted claim moves the truth
 * by at most the epsilon, but a client could keep claiming the same tiny offset to creep (the 1e-7 creep through doors
 * and floors). The signed sum of adopted offsets per axis within a window of client ticks stays within the cap, so float
 * noise that goes back and forth is adopted and a steady creep is not. The collision side of the guard (a claim may not
 * move the box into or against a shape) is {@code Simulation.clearPath}.
 * <p>
 * Owned by the player's tick thread.
 */
public final class RebaseGuard {

    private double driftX;
    private double driftY;
    private double driftZ;
    private int windowStart;
    private boolean started;
    private long refused;

    /**
     * Whether the truth may adopt a claim {@code (dx, dy, dz)} away at client tick {@code tick}; an admitted offset counts
     * toward the window's drift.
     */
    public boolean admit(final int tick, final double dx, final double dy, final double dz, final double cap, final int windowTicks) {
        if (!this.started || tick - this.windowStart >= windowTicks || tick < this.windowStart) {
            this.started = true;
            this.windowStart = tick;
            this.driftX = 0.0;
            this.driftY = 0.0;
            this.driftZ = 0.0;
        }
        final double x = this.driftX + dx;
        final double y = this.driftY + dy;
        final double z = this.driftZ + dz;
        if (Math.abs(x) > cap || Math.abs(y) > cap || Math.abs(z) > cap) {
            this.refused++;
            return false;
        }
        this.driftX = x;
        this.driftY = y;
        this.driftZ = z;
        return true;
    }

    /** Claims refused for drift so far. */
    public long refused() {
        return this.refused;
    }

    /** A teleport or a re-seed: the truth starts over. */
    public void reset() {
        this.started = false;
    }
}
