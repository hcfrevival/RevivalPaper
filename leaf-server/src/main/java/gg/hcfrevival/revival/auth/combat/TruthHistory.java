package gg.hcfrevival.revival.auth.combat;

/**
 * Where a player's truth was on the server's clock: its replication path, one step per simulated client tick, each
 * stamped with the server time it was simulated (the stamps {@link MirrorEvent}s carry). {@link HitValidator} reads it
 * past the rewind cap (Overwatch's rule): an attacker whose view of the target is older than the cap is judged against
 * the truth at the capped age, which its client never drew, so it has to lead.
 */
@FunctionalInterface
public interface TruthHistory {

    /** No history: a view past the cap stays {@link HitCheck.Reason#REWIND}. */
    TruthHistory NONE = (targetId, sinceMillis, out) -> Long.MIN_VALUE;

    /**
     * The target's feet position at the oldest step of its path stamped at or after {@code sinceMillis}, written to
     * {@code out[0..2]}; returns that step's stamp, or {@code Long.MIN_VALUE} when the target has no path or no step that
     * new. Main thread.
     */
    long positionSince(int targetId, long sinceMillis, double[] out);
}
