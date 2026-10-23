package gg.hcfrevival.revival.auth.input;

/**
 * Latches each input statistic past its threshold so {@code AuthInputAnomalyEvent} fires once per crossing, not every
 * tick; a statistic back under its threshold re-arms. One per player, main thread.
 */
public final class AnomalyWatch {

    public static final int DOUBLE_CLICKS = 0;
    public static final int AIM_GCD = 1;
    public static final int INPUT_GRAMMAR = 2;
    public static final int CLICK_STREAK = 3;
    public static final int JUMP_RESET = 4;
    public static final int CLICK_LOCK = 5;
    public static final int KINDS = 6;

    private static final String[] NAMES = {"double-clicks", "aim-gcd", "input-grammar", "click-streak", "jump-reset", "click-lock"};

    private final boolean[] raised = new boolean[KINDS];

    /** Whether {@code kind} crossed its threshold just now. {@code enough}: there are enough samples to judge. */
    public boolean crossed(final int kind, final double value, final double threshold, final boolean enough) {
        final boolean over = enough && value >= threshold;
        if (over && !this.raised[kind]) {
            this.raised[kind] = true;
            return true;
        }
        if (!over) {
            this.raised[kind] = false;
        }
        return false;
    }

    public static String name(final int kind) {
        return NAMES[kind];
    }
}
