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
 * mirror, a spear's ray, a riding attacker): the caller then falls back to the vanilla/Paper range check. A target whose
 * displayed position is older than the rewind cap is INVALID whatever the geometry (anti-backtrack, ping spoof).
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
     * @param maxRewindMillis {@code authoritative.max-rewind-ms}
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
        if (rewind > maxRewindMillis) {
            return new HitCheck(HitCheck.Verdict.INVALID, HitCheck.Reason.REWIND, Double.NaN, rewind);
        }
        final boolean targetExact = s.exact;

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
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.INEXACT_TARGET, distance, rewind);
        }
        final boolean picked = ray.canPick(target, true);
        if (picked != ray.canPick(target, false)) {
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.INEXACT_BLOCKER, distance, rewind);
        }
        if (picked) {
            return new HitCheck(HitCheck.Verdict.VALID, HitCheck.Reason.PICKED, distance, rewind);
        }
        return new HitCheck(HitCheck.Verdict.INVALID, this.reason(target, attacker.entityRange()), distance, rewind);
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
