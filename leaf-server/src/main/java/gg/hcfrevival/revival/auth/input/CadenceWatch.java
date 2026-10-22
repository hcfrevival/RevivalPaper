package gg.hcfrevival.revival.auth.input;

/**
 * The locked-cadence detector, A (neue-combat §12.2): the run of consecutive eligible windows with exactly one click
 * ({@code Punch}). A 20 CPS lock or a one-per-tick macro holds it; humans don't for {@code click-streak-ticks} (100,
 * 5 s). Evidence for staff ({@code AuthInputAnomalyEvent} kind {@code click-streak}), never authority. Detector B
 * ({@code click-lock}) is p2. O(1) per window, no allocation. Main thread, per player.
 */
public final class CadenceWatch {

    private int streak;
    private int longest;

    /**
     * One window. {@code eligible}: it ran (not refused or exempt), the player is alive, not using an item at its
     * keybinds, no container open, no digging in the window. Returns the current run.
     */
    public int onWindow(final boolean eligible, final int clicks) {
        if (eligible && clicks == 1) {
            this.streak++;
            this.longest = Math.max(this.longest, this.streak);
        } else {
            this.streak = 0;
        }
        return this.streak;
    }

    public int streak() {
        return this.streak;
    }

    public int longest() {
        return this.longest;
    }
}
