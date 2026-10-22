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
    private long owed;
    private long owedUntilNanos;
    private long repaid;

    private long ticks;
    private long refused;
    private int refusedInARow;

    /** Starts (or restarts) the clock at {@code nowNanos} with a full bank. */
    public void start(final long nowNanos, final long periodNanos, final int maxBanked) {
        this.balance = nowNanos - maxBanked * periodNanos;
        this.started = true;
        this.refusedInARow = 0;
        this.owed = 0L;
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
        return this.tryRun(nowNanos, nowNanos, ackClockNanos, periodNanos, driftNanos, maxBanked);
    }

    /**
     * As above, for a window whose {@code TickEnd} came off the wire at {@code arrivalNanos} (torture F4). The bank caps
     * the client's idle time before the window arrived, not time it then waited in the server (a hitch). Time the bank
     * takes away is owed back to the windows right behind it, at most one bank's worth and for one bank's time: they
     * are the catch-up ticks the client ran during that idle time (a stall), so the queue drains instead of staying a
     * bank behind for good. Time before the ack clock is never owed: a client visibly alive can't bank.
     */
    public boolean tryRun(final long nowNanos, final long arrivalNanos, final long ackClockNanos, final long periodNanos, final long driftNanos,
                          final int maxBanked) {
        if (!this.started) {
            this.start(nowNanos, periodNanos, maxBanked);
        }
        final long bank = maxBanked * periodNanos;
        if (ackClockNanos != 0L && this.balance < ackClockNanos - driftNanos) {
            this.balance = ackClockNanos - driftNanos;
        }
        final long bankFloor = Math.min(arrivalNanos, nowNanos) - bank;
        if (this.balance < bankFloor) {
            this.owed = Math.min(bankFloor - this.balance, bank);
            this.owedUntilNanos = nowNanos + bank;
            this.balance = bankFloor;
        }
        long next = this.balance + periodNanos;
        if (next > nowNanos && this.owed > 0L && nowNanos <= this.owedUntilNanos) {
            final long back = Math.min(this.owed, next - nowNanos);
            this.balance -= back;
            this.owed -= back;
            next -= back;
            this.repaid += back;
        }
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

    /** Nanoseconds of bank-forfeited time given back to catch-up windows (torture F4). */
    public long repaidNanos() {
        return this.repaid;
    }

    public int refusedInARow() {
        return this.refusedInARow;
    }
}
