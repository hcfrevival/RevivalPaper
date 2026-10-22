package gg.hcfrevival.revival.auth.visibility;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * One viewer's view of which players it may be sent (design §11). A target is checked at most every
 * {@code check-interval-ticks}; it is hidden only after {@code hide-after-checks} hidden checks in a row and shown again
 * at the first check that sees it, so a player stepping out of cover appears at once. Main thread, per viewer.
 */
public final class Visibility {

    private final Int2IntOpenHashMap lastCheck = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap hiddenChecks = new Int2IntOpenHashMap();
    private final IntOpenHashSet hidden = new IntOpenHashSet();
    private long hides;

    /** Whether target {@code id}'s last decision is due for a new check at {@code tick}. */
    public boolean due(final int id, final int tick, final int interval) {
        return !this.lastCheck.containsKey(id) || tick - this.lastCheck.get(id) >= interval;
    }

    /** A check's outcome: returns whether the target is to be sent. */
    public boolean decide(final int id, final int tick, final boolean seen, final int hideAfter) {
        this.lastCheck.put(id, tick);
        if (seen) {
            this.hiddenChecks.remove(id);
            this.hidden.remove(id);
            return true;
        }
        final int checks = this.hiddenChecks.get(id) + 1;
        this.hiddenChecks.put(id, checks);
        if (checks >= hideAfter && this.hidden.add(id)) {
            this.hides++;
        }
        return !this.hidden.contains(id);
    }

    /** The decision in force (between checks). */
    public boolean sent(final int id) {
        return !this.hidden.contains(id);
    }

    /** Players currently hidden from this viewer. */
    public IntOpenHashSet hidden() {
        return this.hidden;
    }

    /** Times a player was hidden from this viewer. */
    public long hides() {
        return this.hides;
    }

    /** The target is gone (logout, untracked): forget it. */
    public void forget(final int id) {
        this.lastCheck.remove(id);
        this.hiddenChecks.remove(id);
        this.hidden.remove(id);
    }

    public void clear() {
        this.lastCheck.clear();
        this.hiddenChecks.clear();
        this.hidden.clear();
    }
}
