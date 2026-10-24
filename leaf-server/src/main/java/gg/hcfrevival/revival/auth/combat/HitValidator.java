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
 * (outside authority and moved since its tracker last sent it) is INVALID {@code REWIND}. Other players stay where the
 * client drew them.
 * <p>
 * Non-player targets get only {@link #blockClip}: no mirror says where the client drew a mob, so reach and aim stay
 * vanilla's, but a block the server has between the eye and every place the mob may have been is a veto.
 * <p>
 * One instance per thread (it reuses a {@link PickRay}); {@link #validate} and {@link #blockClip} allocate only their
 * result.
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
        if (attacker.riding() || attacker.attackRangeItem() && attacker.reach() == null) {
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
        if (attacker.attackRangeItem()) {
            // pvp-issues HR-7: a spear picks along its reach first (LocalPlayer.raycastHitResult); a miss there picks as usual
            ray.castAttackRange(attacker, world);
            if (ray.rangeKind() == PickRay.Kind.BLOCK) {
                return new HitCheck(HitCheck.Verdict.INVALID, HitCheck.Reason.BLOCKED, Double.NaN, rewind, capped);
            }
            if (ray.rangeKind() == PickRay.Kind.ENTITY) {
                final double along = ray.touched(target) ? Math.sqrt(ray.distanceSq(target)) : Double.NaN;
                if (!targetExact) {
                    return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.INEXACT_TARGET, along, rewind, false);
                }
                final boolean pickedAlong = ray.canPickAlong(target, true);
                if (pickedAlong != ray.canPickAlong(target, false)) {
                    return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.INEXACT_BLOCKER, along, rewind, capped);
                }
                return pickedAlong ? new HitCheck(HitCheck.Verdict.VALID, HitCheck.Reason.PICKED, along, rewind, capped)
                    : new HitCheck(HitCheck.Verdict.INVALID, ray.touched(target) ? HitCheck.Reason.OCCLUDED : HitCheck.Reason.MISSED, along, rewind, capped);
            }
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

    // ------------------------------------------------------------------------------------------------------------------
    // Non-player targets: the block clip
    // ------------------------------------------------------------------------------------------------------------------

    /** Probe points per side of each face of the region that faces the eye ({@link #blockClip}). */
    static final int SIGHT_GRID = 5;
    /**
     * The block clip looks this far from the eye, to the region's nearest point. Past it vanilla's reach check (the entity
     * interaction range plus Paper's 3-block leniency) refuses the hit anyway unless an owner raised the range attribute
     * past 13; the bound keeps a cheater's swings at far mobs from costing long rays.
     */
    public static final double SIGHT_REACH = 16.0;
    // The grid's fractions along a face side, centre first; the ends a hair inside, off the region's own edges
    private static final double[] GRID = gridFractions(SIGHT_GRID);

    /**
     * Where a non-player target may have been on the attacker's screen, as one box: the union of the boxes the caller
     * includes (its server box now and a tick ago, its {@link SentTrail} over the view's lag), then grown by a margin. Its
     * bounding box also holds every box between two of them, so every position the client's interpolation could draw on
     * the way. Bigger is more tolerant: a veto needs a block in front of all of it. Reusable, one per thread.
     */
    public static final class Region {
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;
        private boolean empty = true;

        public Region clear() {
            this.empty = true;
            return this;
        }

        public Region include(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
            if (this.empty) {
                this.minX = minX;
                this.minY = minY;
                this.minZ = minZ;
                this.maxX = maxX;
                this.maxY = maxY;
                this.maxZ = maxZ;
                this.empty = false;
                return this;
            }
            this.minX = Math.min(this.minX, minX);
            this.minY = Math.min(this.minY, minY);
            this.minZ = Math.min(this.minZ, minZ);
            this.maxX = Math.max(this.maxX, maxX);
            this.maxY = Math.max(this.maxY, maxY);
            this.maxZ = Math.max(this.maxZ, maxZ);
            return this;
        }

        public Region include(final AABB box) {
            return this.include(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }

        /** Grows every side by {@code margin}. */
        public Region inflate(final double margin) {
            this.minX -= margin;
            this.minY -= margin;
            this.minZ -= margin;
            this.maxX += margin;
            this.maxY += margin;
            this.maxZ += margin;
            return this;
        }

        public boolean empty() {
            return this.empty;
        }

        public AABB box() {
            return new AABB(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
        }

        // Eye to the region's nearest point, 0 when the eye is in it (closed: a face counts)
        double distanceFrom(final double x, final double y, final double z) {
            final double dx = Math.max(Math.max(this.minX - x, 0.0), x - this.maxX);
            final double dy = Math.max(Math.max(this.minY - y, 0.0), y - this.maxY);
            final double dz = Math.max(Math.max(this.minZ - z, 0.0), z - this.maxZ);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * The non-player block clip (design §7.1, a scope change of 2026-10-24). Blocks are what the server knows exactly;
     * where the attacker's client drew a mob it doesn't (no mirror), so this is strict about blocks and tolerant about the
     * target: INVALID {@code BLOCKED} only when a block on {@code world} stands between the attacker's eye and every point
     * of {@code region}; else UNKNOWN ({@code IN_SIGHT}), and vanilla/Paper's checks decide as before. It never refuses on
     * reach or aim. The blocks are read as {@link PickRay}'s block pass reads them (OUTLINE shapes, no fluids, the client's
     * {@code BlockGetter.clip}).
     * <p>
     * Lines tried, the first in sight ends it: the client's own crosshair ({@link PickRay#cast} from the eye along the
     * tick's rotation), then a {@value #SIGHT_GRID}x{@value #SIGHT_GRID} grid on every face of the region that faces the
     * eye, face centres first. An honest client that picked the target drew it inside the region (its view no older than
     * the span the caller swept), so its crosshair met the region before any block: the grid only matters for a swing whose
     * crosshair wasn't on the target (a killaura's), and
     * any point of the region in sight puts a point of one of those faces in sight (the line to it enters the region
     * through one). shortcut: the grid samples those faces, so a sliver of a target in sight between its points reads
     * as hidden; only a swing whose crosshair missed the region can meet that.
     */
    public HitCheck blockClip(final AttackerSnapshot attacker, final int targetId, final Region region, final PickWorld world) {
        final double ex = attacker.eyeX();
        final double ey = attacker.eyeY();
        final double ez = attacker.eyeZ();
        final double distance = region.distanceFrom(ex, ey, ez);
        if (distance == 0.0) {
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.IN_SIGHT, 0.0, 0L); // the eye is in it: no block between
        }
        if (distance > SIGHT_REACH) {
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.OUT_OF_REACH, distance, 0L);
        }
        final PickRay ray = this.ray;
        ray.clear();
        final int index = ray.add(targetId, region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ, 0.0F, true, true);
        ray.cast(attacker, world);
        if (ray.touched(index)) {
            return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.IN_SIGHT, distance, 0L); // the crosshair meets it first
        }
        // The faces toward the eye: the eye is outside the region on at least one axis
        final boolean west = ex < region.minX;
        final boolean east = ex > region.maxX;
        final boolean below = ey < region.minY;
        final boolean above = ey > region.maxY;
        final boolean north = ez < region.minZ;
        final boolean south = ez > region.maxZ;
        for (int pass = 0; pass < 2; pass++) {
            final int from = pass == 0 ? 0 : 1; // pass 0: each face's centre; pass 1: the rest of its grid
            final int to = pass == 0 ? 1 : GRID.length * GRID.length;
            if ((west || east) && this.faceInSight(ray, world, ex, ey, ez, 0, west ? region.minX : region.maxX, region.minY, region.maxY, region.minZ,
                region.maxZ, from, to)) {
                return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.IN_SIGHT, distance, 0L);
            }
            if ((below || above) && this.faceInSight(ray, world, ex, ey, ez, 1, below ? region.minY : region.maxY, region.minX, region.maxX, region.minZ,
                region.maxZ, from, to)) {
                return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.IN_SIGHT, distance, 0L);
            }
            if ((north || south) && this.faceInSight(ray, world, ex, ey, ez, 2, north ? region.minZ : region.maxZ, region.minX, region.maxX, region.minY,
                region.maxY, from, to)) {
                return new HitCheck(HitCheck.Verdict.UNKNOWN, HitCheck.Reason.IN_SIGHT, distance, 0L);
            }
        }
        return new HitCheck(HitCheck.Verdict.INVALID, HitCheck.Reason.BLOCKED, distance, 0L);
    }

    // Grid points [from, to) of the face at `plane` on `axis` (0 x, 1 y, 2 z), its other two axes spanning [minA, maxA] and
    // [minB, maxB] in x, y, z order: whether a line from the eye reaches one with no block on the way (PickRay.clipTo)
    private boolean faceInSight(final PickRay ray, final PickWorld world, final double ex, final double ey, final double ez, final int axis,
                                final double plane, final double minA, final double maxA, final double minB, final double maxB, final int from,
                                final int to) {
        for (int k = from; k < to; k++) {
            final double a = minA + (maxA - minA) * GRID[k / GRID.length];
            final double b = minB + (maxB - minB) * GRID[k % GRID.length];
            final double px = axis == 0 ? plane : a;
            final double py = axis == 0 ? a : axis == 1 ? plane : b;
            final double pz = axis == 2 ? plane : b;
            ray.clipTo(world, ex, ey, ez, px, py, pz);
            if (!ray.blockHit()) {
                return true;
            }
        }
        return false;
    }

    // 0.5 first, then 0 .. 1 in (n - 1) steps without it (an even n has no 0.5 of its own), the ends pulled in by 1e-3
    private static double[] gridFractions(final int n) {
        final double[] fractions = new double[n % 2 == 1 ? n : n + 1];
        fractions[0] = 0.5;
        int next = 1;
        for (int i = 0; i < n; i++) {
            final double f = (double) i / (n - 1);
            if (f != 0.5) {
                fractions[next++] = Math.clamp(f, 1.0E-3, 1.0 - 1.0E-3);
            }
        }
        return fractions;
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
