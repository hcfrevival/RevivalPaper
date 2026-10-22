package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.auth.AuthPlayer;
import gg.hcfrevival.revival.auth.CombatWiring;
import gg.hcfrevival.revival.auth.combat.AttackerSnapshot;
import gg.hcfrevival.revival.auth.combat.MirrorBook;
import gg.hcfrevival.revival.auth.combat.PickWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Distances for Neue: the reach re-check a held click passes when its window opens (docs/revival/neue-combat.md §5.2
 * step 5), and the tipper's fallback distance. Aim is never re-checked: the click was aimed and judged when it was made.
 */
final class NeueReach {

    private NeueReach() {
    }

    /**
     * Under authority: eye to the closest point of the target's box as the attacker's client shows it now (the eye after
     * its latest tick, the box its mirror holds for that tick), or NaN when the click can't land: the mirror inexact, the
     * box at or past the client's entity range, or (with {@code lineOfSight}) a block in between on the client's world.
     * Past the rewind cap, the judge's rule ({@code HitValidator}): the target's truth at the capped age, in the drawn
     * box's size; NaN when it has no truth that new.
     */
    static double authority(final ServerPlayer attacker, final Entity target, final boolean lineOfSight) {
        final AuthPlayer auth = attacker.revivalAuth;
        final AttackerSnapshot eye = CombatWiring.snapshot(auth);
        // shortcut: one small sample per landing (a few a second at most), not per packet
        final MirrorBook.Sample sample = new MirrorBook.Sample();
        if (!CombatWiring.book(auth).sample(target.getId(), auth.tick(), sample) || !sample.pickable) {
            return Double.NaN;
        }
        final long cap = CombatWiring.sharedRewindCap(auth, target.getId(), auth.tick()); // the judge's own cap (HR-2), shared
        final long now = gg.hcfrevival.revival.auth.Authority.clockMillis();
        if (now - sample.stamp > cap) {
            final double[] truth = new double[3];
            if (TruthPaths.INSTANCE.positionSince(target.getId(), now - cap, truth) == Long.MIN_VALUE) {
                return Double.NaN;
            }
            sample.moveFeetTo(truth[0], truth[1], truth[2]);
        } else if (!sample.exact) {
            return Double.NaN;
        }
        return reach(eye.eyeX(), eye.eyeY(), eye.eyeZ(), sample.minX, sample.minY, sample.minZ, sample.maxX, sample.maxY, sample.maxZ,
            eye.entityRange(), lineOfSight ? auth.world() : null);
    }

    /** Outside authority (§11): the same check on server positions, which isn't lag-compensated. */
    static double server(final ServerPlayer attacker, final Entity target, final boolean lineOfSight) {
        final Vec3 eye = attacker.getEyePosition();
        final AABB box = target.getBoundingBox();
        final double range = attacker.entityInteractionRange();
        final double distance = closest(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        if (!(distance < range)) {
            return Double.NaN;
        }
        if (lineOfSight && distance > 0.0) {
            final Vec3 point = new Vec3(Math.clamp(eye.x, box.minX, box.maxX), Math.clamp(eye.y, box.minY, box.maxY), Math.clamp(eye.z, box.minZ, box.maxZ));
            final BlockHitResult hit = attacker.level().clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, attacker));
            if (hit.getType() == HitResult.Type.BLOCK && nearer(eye, hit.getLocation(), distance)) {
                return Double.NaN;
            }
        }
        return distance;
    }

    /** The tipper's distance when no lag-compensated one came with the hit (§6.2 case 3): server eye to server box. */
    static double serverDistance(final Entity attacker, final Entity target) {
        final Vec3 eye = attacker.getEyePosition();
        final AABB box = target.getBoundingBox();
        return closest(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /**
     * Eye to the box's closest point, 0 inside it; NaN at or past {@code range} (26.3 picks with a strict {@code <}) or
     * when {@code world} has a block nearer than that point along the way.
     */
    static double reach(final double ex, final double ey, final double ez, final double minX, final double minY, final double minZ,
                        final double maxX, final double maxY, final double maxZ, final double range, final @Nullable PickWorld world) {
        final double distance = closest(ex, ey, ez, minX, minY, minZ, maxX, maxY, maxZ);
        if (!(distance < range)) {
            return Double.NaN;
        }
        if (world != null && distance > 0.0) {
            final Vec3 from = new Vec3(ex, ey, ez);
            final Vec3 to = new Vec3(Math.clamp(ex, minX, maxX), Math.clamp(ey, minY, maxY), Math.clamp(ez, minZ, maxZ));
            final Vec3 hit = BlockGetter.traverseBlocks(from, to, new Segment(world, from, to), NeueReach::clipBlock, segment -> null);
            if (hit != null && nearer(from, hit, distance)) {
                return Double.NaN;
            }
        }
        return distance;
    }

    /** {@code d² = Σ max(min − e, 0, e − max)²}, square-rooted: 0 with the eye inside the box. */
    static double closest(final double ex, final double ey, final double ez, final double minX, final double minY, final double minZ,
                          final double maxX, final double maxY, final double maxZ) {
        final double dx = Math.max(Math.max(minX - ex, 0.0), ex - maxX);
        final double dy = Math.max(Math.max(minY - ey, 0.0), ey - maxY);
        final double dz = Math.max(Math.max(minZ - ez, 0.0), ez - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // A block stops the click only when it is nearer than the point it was aiming for, not a face that point rests on
    private static boolean nearer(final Vec3 from, final Vec3 hit, final double distance) {
        return from.distanceTo(hit) < distance - 1.0E-6;
    }

    private record Segment(PickWorld world, Vec3 from, Vec3 to) {
    }

    // The PickRay block pass for one block: its outline shape (OUTLINE, no fluids), as the client clips
    private static @Nullable Vec3 clipBlock(final Segment segment, final BlockPos pos) {
        final BlockState state = segment.world().blockState(pos.getX(), pos.getY(), pos.getZ());
        if (state.isAir()) {
            return null;
        }
        final BlockHitResult hit = segment.world().outlineShape(state, pos).clip(segment.from(), segment.to(), pos);
        return hit != null ? hit.getLocation() : null;
    }
}
