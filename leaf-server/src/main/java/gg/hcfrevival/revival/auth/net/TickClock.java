package gg.hcfrevival.revival.auth.net;

/**
 * Tick authority (design §9, report 06 §9.1/§9.3): a client can't run more client ticks than real time allows.
 * <p>
 * A balance timer. {@code balance} is the client time the player's ticks have used so far; each {@code TickEnd} adds
 * one client tick period. A tick that would push the balance past the server's clock ({@code now}) is refused: the
 * client claims to have run more ticks than wall time allows. Two floors keep the balance from lagging behind:
 * <ul>
 *   <li>the ack clock: the client's clock is at least the send time of the newest ping it answered, so time before
 *       that (minus {@code timer-drift-ms}) can't be claimed later (no banking while visibly alive);</li>
 *   <li>the bank: at most {@code max-banked-ticks} of unused time, the most a vanilla client runs in one burst after a
 *       stall (report 02 §1.2).</li>
 * </ul>
 * A window over budget is not refused at once (§15.5): it waits in the stream's queue and runs as the budget refills.
 * Only a window that waited past {@code timer-queue-cap-ms} is refused: it does not move the player, and the divergence
 * that follows is corrected. A player who stops sending ticks does not move at all (no extrapolation).
 * <p>
 * The client's tick period is {@code max(50 ms, server mspt)} while the game runs normally and 50 ms while frozen
 * (report 02 §1.2, H18); the caller passes the period from the {@code TickingState} the client has acked.
 */
public final class TickClock {

    private long balance;
    private boolean started;

    private long ticks;
    private long refused;
    private int refusedInARow;

    /** Starts (or restarts) the clock at {@code nowNanos} with a full bank. */
    public void start(final long nowNanos, final long periodNanos, final int maxBanked) {
        this.balance = nowNanos - maxBanked * periodNanos;
        this.started = true;
        this.refusedInARow = 0;
    }

    /**
     * Charges one client tick if the budget allows it.
     *
     * @param nowNanos {@code System.nanoTime()} now
     * @param ackClockNanos send time of the newest acked ping, or 0 if none yet
     * @param periodNanos the client's tick period
     * @return whether the window may run now (false: it waits)
     */
    public boolean tryRun(final long nowNanos, final long ackClockNanos, final long periodNanos, final long driftNanos, final int maxBanked) {
        if (!this.started) {
            this.start(nowNanos, periodNanos, maxBanked);
        }
        long floor = nowNanos - maxBanked * periodNanos;
        if (ackClockNanos != 0L) {
            floor = Math.max(floor, ackClockNanos - driftNanos);
        }
        if (this.balance < floor) {
            this.balance = floor;
        }
        final long next = this.balance + periodNanos;
        if (next > nowNanos) {
            return false;
        }
        this.ticks++;
        this.balance = next;
        this.refusedInARow = 0;
        return true;
    }

    /** A queued window waited too long and is refused: it uses no time (the client never got to run it in real time). */
    public void refuse() {
        this.ticks++;
        this.refused++;
        this.refusedInARow++;
    }

    /** Unused client time in ticks: how many ticks the player could run at once right now. */
    public double banked(final long nowNanos, final long periodNanos) {
        return (double) (nowNanos - this.balance) / periodNanos;
    }

    public long ticks() {
        return this.ticks;
    }

    public long refused() {
        return this.refused;
    }

    public int refusedInARow() {
        return this.refusedInARow;
    }
}
