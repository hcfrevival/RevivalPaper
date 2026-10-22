package gg.hcfrevival.revival.auth.input;

/**
 * The click cap (neue-combat §4.3): a token bucket refilled once per client tick that runs, charged per {@code Attack}
 * packet that passed the input grammar and the per-tick envelope. {@code refill} is {@code max-cps / 20} (0.6 for
 * Neue), {@code burst} the cap (2.0): 12 attacks a second sustained, an even 12 CPS clicker loses nothing, and banked
 * windows refill at most to the cap. {@code Punch}-only clicks (air, blocks, a held mining button) are never charged.
 * Main thread, per player.
 */
public final class ClickBucket {

    private double tokens;
    private boolean started;

    /** A window runs (not refused, not exempt), before its keybinds. */
    public void refill(final double refill, final double burst) {
        if (!this.started) {
            this.started = true;
            this.tokens = burst; // a new bucket starts full
            return;
        }
        this.tokens = Math.min(burst, this.tokens + refill);
    }

    /** Charges one attack: whether a token was there (a hair under one counts: the refills are float sums). */
    public boolean take(final double burst) {
        if (!this.started) {
            this.started = true;
            this.tokens = burst;
        }
        if (this.tokens >= 1.0 - 1.0E-9) {
            this.tokens -= 1.0;
            return true;
        }
        return false;
    }

    public double tokens() {
        return Math.max(0.0, this.tokens);
    }
}
