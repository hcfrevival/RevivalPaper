package gg.hcfrevival.revival.auth.combat;

/**
 * What {@link HitValidator} decided about one attack.
 *
 * @param distance eye to where the ray met the target's box, in blocks (0 when the eye was inside it; NaN when the ray
 *                 never met it)
 * @param rewindMillis how old the target's displayed position was, server time now minus its stamp
 * @param capped the view was older than the rewind cap, so the ray was tested against the target's truth at the capped
 *               age instead of the box the client drew (Overwatch's rule, {@link TruthHistory})
 */
public record HitCheck(Verdict verdict, Reason reason, double distance, long rewindMillis, boolean capped) {

    /** A verdict on the box the client drew. */
    public HitCheck(final Verdict verdict, final Reason reason, final double distance, final long rewindMillis) {
        this(verdict, reason, distance, rewindMillis, false);
    }

    public enum Verdict {
        /** The attacker's client picked the target: apply the hit. */
        VALID,
        /** It could not have: drop the hit. */
        INVALID,
        /** The replay isn't exact for this case: fall back to the vanilla/Paper check. */
        UNKNOWN
    }

    public enum Reason {
        /** VALID: the target was under the crosshair. */
        PICKED,
        /** INVALID: the target wasn't on the attacker's client (never sent, removed, spectator) at the attack tick. */
        NOT_PRESENT,
        /** INVALID: the ray never met the target's box. */
        MISSED,
        /** INVALID: met it, but beyond the client's entity interaction range. */
        OUT_OF_REACH,
        /** INVALID: a block the client had was nearer along the ray. */
        BLOCKED,
        /** INVALID: another entity took the pick in every possible order. */
        OCCLUDED,
        /**
         * INVALID: the box the attacker aimed at shows the target older than the rewind cap allows, and the target has no
         * truth that new to judge against instead (no replication path: outside authority).
         */
        REWIND,
        /** UNKNOWN: the target's mirror is not exact at that tick (snap, riding, sleeping, late packet). */
        INEXACT_TARGET,
        /** UNKNOWN: an inexact candidate decides the outcome. */
        INEXACT_BLOCKER,
        /** UNKNOWN: no sample for the attack tick (the tick wasn't run yet, or is out of history). */
        NO_SAMPLE,
        /** UNKNOWN: a pick this replay doesn't cover (spear {@code ATTACK_RANGE}, riding attacker). */
        UNSUPPORTED
    }

    public boolean valid() {
        return this.verdict == Verdict.VALID;
    }

    public boolean invalid() {
        return this.verdict == Verdict.INVALID;
    }
}
