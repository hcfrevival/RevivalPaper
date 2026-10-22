package gg.hcfrevival.revival.auth.reconcile;

/**
 * Decides, per simulated client tick, whether the client is on the true path (design §6.2, report 06 §7.3).
 * <ul>
 *   <li>{@code d <= rebase-epsilon}: exact; the claim's position is adopted (kills float drift);</li>
 *   <li>{@code d <= correction-threshold}: added to a decaying budget; a correction goes out when the budget passes
 *       {@code correction-budget};</li>
 *   <li>otherwise: correct now.</li>
 * </ul>
 * One correction is in flight at a time. While it is, the client's claims are expected to be off by the known Δ, the
 * truth keeps advancing on the client's inputs, and nothing else is corrected. The accept carries the client's exact
 * post-correction position; its residual against the truth is judged like a claim. Corrections are rate limited.
 * <p>
 * Correction ids are our own, in a band below the ping band ({@code [Integer.MIN_VALUE, PING_MIN)}), so the vanilla
 * accept handling ({@code absSnapTo} of {@code awaitingPositionFromClient}, ids from 0 up) never sees them. A correction
 * counts as applied at its pong's window; its accept must be the next packet after that pong, else it is a violation
 * and a new correction may go out (§15.3). Main thread only.
 */
public final class Reconciler {

    public static final int EXACT = 0;
    public static final int WITHIN = 1;
    public static final int CORRECT = 2;
    /** A correction is in flight: the claim is not judged. */
    public static final int IN_FLIGHT = 3;
    /** Over threshold, but the rate limit holds the correction back. */
    public static final int RATE_LIMITED = 4;

    private double budget;
    public static final int ID_MAX = gg.hcfrevival.revival.auth.net.Transactions.PING_MIN - 1;

    private int nextId = ID_MAX - java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 24);
    private boolean applied;
    private long violations;
    private int inFlightId;
    private boolean inFlight;
    private int inFlightTicks;
    private long secondStartNanos;
    private int sentThisSecond;
    private double inFlightDx;
    private double inFlightDy;
    private double inFlightDz;

    /**
     * Judges one tick.
     *
     * @param distance {@code |claim - truth|}, or the residual of an accept
     */
    public int judge(final double distance, final double epsilon, final double threshold, final double budgetLimit, final double decay,
                     final int maxPerSecond, final long nowNanos) {
        if (this.inFlight) {
            this.inFlightTicks++;
            return IN_FLIGHT;
        }
        this.budget *= decay;
        if (distance <= epsilon) {
            return EXACT;
        }
        if (distance <= threshold) {
            this.budget += distance;
            if (this.budget <= budgetLimit) {
                return WITHIN;
            }
        }
        if (!this.mayCorrect(maxPerSecond, nowNanos)) {
            return RATE_LIMITED;
        }
        return CORRECT;
    }

    private boolean mayCorrect(final int maxPerSecond, final long nowNanos) {
        if (nowNanos - this.secondStartNanos >= 1_000_000_000L) {
            this.secondStartNanos = nowNanos;
            this.sentThisSecond = 0;
        }
        return this.sentThisSecond < maxPerSecond;
    }

    /**
     * A correction that moves the client by {@code (dx, dy, dz)} goes out with the returned teleport id. While it is in
     * flight the client is off the truth by about that much (§15.3: the attacker's eye, {@link #bounded}).
     */
    public int send(final double dx, final double dy, final double dz) {
        this.inFlightDx = Math.abs(dx);
        this.inFlightDy = Math.abs(dy);
        this.inFlightDz = Math.abs(dz);
        final int id = this.nextId;
        this.nextId = id == Integer.MIN_VALUE ? ID_MAX : id - 1;
        this.inFlight = true;
        this.applied = false;
        this.inFlightId = id;
        this.inFlightTicks = 0;
        this.sentThisSecond++;
        this.budget = 0.0;
        return id;
    }

    /** Whether {@code id} is in our correction band (the vanilla accept path must skip it). */
    public static boolean ours(final int id) {
        return id <= ID_MAX;
    }

    /** The correction's pong (or its deadline) came: the client applied it before this window. */
    public void applied(final int id) {
        if (this.inFlight && id == this.inFlightId) {
            this.applied = true;
        }
    }

    /** The client accepted correction {@code id}. Returns whether it was the one in flight. */
    public boolean accepted(final int id) {
        if (this.inFlight && id == this.inFlightId) {
            this.inFlight = false;
            this.applied = false;
            return true;
        }
        this.violations++;
        return false;
    }

    /**
     * A window ran: a correction that was applied but whose accept did not follow is a violation; it stops being in
     * flight so another (absolute if needed) can go out.
     */
    public void afterWindow() {
        if (this.inFlight && this.applied) {
            this.violations++;
            this.inFlight = false;
            this.applied = false;
        }
    }

    public long violations() {
        return this.violations;
    }

    public boolean inFlight() {
        return this.inFlight;
    }

    /** The in-flight correction's position change on each axis, as magnitudes (0 when none is in flight). */
    public double inFlightDelta(final int axis) {
        if (!this.inFlight) {
            return 0.0;
        }
        return axis == 0 ? this.inFlightDx : axis == 1 ? this.inFlightDy : this.inFlightDz;
    }

    /**
     * The client's offset from the truth on one axis, bounded by the in-flight correction's change on that axis (§15.3):
     * while a correction is in flight attacks are judged from the client's own eye, but no farther from the truth than
     * the correction itself moves it.
     */
    public static double bounded(final double offset, final double delta) {
        return Math.clamp(offset, -delta, delta);
    }

    public int inFlightTicks() {
        return this.inFlightTicks;
    }

    public double budget() {
        return this.budget;
    }

    /** Forget an in-flight correction (respawn, server teleport, mode change). */
    public void reset() {
        this.inFlight = false;
        this.budget = 0.0;
    }
}
