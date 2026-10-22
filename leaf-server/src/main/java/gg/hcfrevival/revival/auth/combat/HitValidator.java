package gg.hcfrevival.revival.auth.combat;

import java.util.List;
import net.minecraft.world.phys.AABB;

/**
 * Lag-compensated hit validation (design §7.1): an attack sent in the attacker's client tick {@code T} is valid iff the
 * attacker's client, casting its ray from the authoritative eye with its own rotation at the start of tick {@code T},
 * picked the attacked player. Player boxes come from the attacker's {@link MirrorBook} at tick {@code T} (bit-exact),
 * blocks from the attacker's client world ({@link PickWorld}), the ray from {@link PickRay}.
 * <p>
 * The verdict is UNKNOWN, never a guess, when something it relies on is not exact (the target's or a deciding blocker's
 * mirror, a spear's ray, a riding attacker): the caller then falls back to the vanilla/Paper range check.
 * <p>
 * Past the rewind cap, Overwatch's rule: when the box the attacker's client drew is older than the cap, the ray is
 * tested against the target's truth at the capped age ({@link TruthHistory}: the oldest step of its path stamped at or
 * after {@code now - cap}, never older), in the drawn box's size. The client never drew it there, so a high-ping
 * attacker has to lead; a backtrack or a held view gains nothing past the cap. Only a target with no truth that new
 * (no path: outside authority) is INVALID {@code REWIND}. Other players stay where the client drew them.
 * <p>
 * One instance per thread (it reuses a {@link PickRay}); {@link #validate} allocates only its result.
 */
public final class HitValidator {

    /**
     * A non-player entity the attacker's client could pick and that may stand in front of the target.
     *
     * @param box its bounding box (not inflated)
     * @param pickRadius {@code getPickRadius()}
     * @param exact whether the box is the client's exactly; non-player interpolation is not mirrored, so usually false
     */
    public record Candidate(int id, AABB box, float pickRadius, boolean pickableFromInside, boolean exact) {
    }

    private final PickRay ray = new PickRay();
    private final MirrorBook.Sample sample = new MirrorBook.Sample();
    private final TruthHistory history;
    private final double[] truth = new double[3];

    /** Judges past the cap on the live paths ({@code TruthPaths}): the server's judge. */
    public HitValidator() {
        this(gg.hcfrevival.revival.combat.TruthPaths.INSTANCE);
    }

    /** Judges past the cap on {@code history}; {@link TruthHistory#NONE} refuses every view past it. */
    public HitValidator(final TruthHistory history) {
        this.history = history;
    }

    /**
     * Validates one attack.
     *
     * @param attacker the attacker at the start of client tick {@code tick}
     * @param targetId the attacked player's entity id
     * @param book the attacker's mirrors, run up to {@code tick} ({@link MirrorBook#onViewerTickEnd} for {@code tick} done)
     * @param tick the attacker's client tick the attack was sent in (its window)
     * @param others non-player candidates (may be empty)
     * @param world the attacker's client blocks
     * @param nowMillis server time now, on the clock the event stamps use
     * @param maxRewindMillis the attacker's rewind cap ({@code CombatWiring.sharedRewindCap}), inclusive
     */
    public HitCheck validate(final AttackerSnapshot attacker, final int targetId, final MirrorBook book, final int tick, final List<Candidate> others,
                             final PickWorld world, final long nowMillis, final long maxRewindMillis) {
        if (attacker.attackRangeItem() || attacker.riding()) {
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.UNSUPPORTED, Double.NaN, 0L);
        }
        if (!book.knows(targetId)) {
            return new HitCheck(HitCheck.Verdict.INVALID, HitCheck.Reason.NOT_PRESENT, Double.NaN, 0L);
        }
        final MirrorBook.Sample s = this.sample;
        if (!book.sample(targetId, tick, s)) {
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.NO_SAMPLE, Double.NaN, 0L);
        }
        if (!s.pickable) {
            return new HitCheck(HitCheck.Verdict.INVALID, HitCheck.Reason.NOT_PRESENT, Double.NaN, 0L);
        }
        final long rewind = nowMillis - s.stamp;
        final boolean capped = rewind > maxRewindMillis;
        if (capped) {
            // Overwatch's rule: the truth at the capped age, in the size the client drew (its pose at the attack tick)
            final double[] truth = this.truth;
            if (this.history.positionSince(targetId, nowMillis - maxRewindMillis, truth) == Long.MIN_VALUE) {
                return new HitCheck(HitCheck.Verdict.INVALID, HitCheck.Reason.REWIND, Double.NaN, rewind);
            }
            s.moveFeetTo(truth[0], truth[1], truth[2]);
        }
        // The truth is the server's own: exact whatever the mirror was
        final boolean targetExact = capped || s.exact;

        final PickRay ray = this.ray;
        ray.clear();
        // Players: pick radius 0 (Entity.getPickRadius), pickable from inside (Entity.canBePickedFromInside)
        final int target = ray.add(targetId, s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ, 0.0F, true, targetExact);
        for (int i = 0, size = book.size(); i < size; i++) {
            if (book.sampleAt(i, tick, s) && s.pickable && s.targetId != targetId) {
                ray.add(s.targetId, s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ, 0.0F, true, s.exact);
            }
        }
        for (int i = 0, size = others.size(); i < size; i++) {
            final Candidate other = others.get(i);
            ray.add(other.id(), other.box(), other.pickRadius(), other.pickableFromInside(), other.exact());
        }
        ray.cast(attacker, world);

        final double distance = ray.touched(target) ? Math.sqrt(ray.distanceSq(target)) : Double.NaN;
        if (!targetExact) {
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.INEXACT_TARGET, distance, rewind, false);
        }
        final boolean picked = ray.canPick(target, true);
        if (picked != ray.canPick(target, false)) {
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.INEXACT_BLOCKER, distance, rewind, capped);
        }
        if (picked) {
            return new HitCheck(HitCheck.Verdict.VALID, HitCheck.Reason.PICKED, distance, rewind, capped);
        }
        return new HitCheck(HitCheck.Verdict.INVALID, this.reason(target, attacker.entityRange()), distance, rewind, capped);
    }

    private HitCheck.Reason reason(final int target, final double entityRange) {
        final PickRay ray = this.ray;
        if (!ray.touched(target)) {
            // The entity pass stops at the first block; a box behind it is "blocked", anything else was not aimed at
            return ray.blockHit() && ray.onFullRay(target) ? HitCheck.Reason.BLOCKED : HitCheck.Reason.MISSED;
        }
        final double distanceSq = ray.distanceSq(target);
        if (!(distanceSq < entityRange * entityRange)) {
            return HitCheck.Reason.OUT_OF_REACH;
        }
        if (!(distanceSq < ray.blockDistanceSq())) {
            return HitCheck.Reason.BLOCKED;
        }
        return HitCheck.Reason.OCCLUDED;
    }

    /** The ray of the last {@link #validate}, for diagnostics. */
    public PickRay ray() {
        return this.ray;
    }
}
