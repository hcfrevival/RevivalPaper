package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.LegacyProjectileSettings;
import gg.hcfrevival.revival.config.ProjectileRules;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Where an ender pearl lands its thrower (docs/revival/projectiles.md §6, pvp-issues PR-7).
 * <p>
 * 1.7.10 ({@code EntityEnderPearl.onImpact}) and 26.3 ({@code ThrownEnderpearl.onHit}, {@code oldPosition()}) both
 * teleport the thrower's feet to where the pearl was at the start of its impact tick, with no check that the thrower's
 * box fits there. A box that starts inside a block can leave it in any direction (26.x's {@code Shapes.collide} ignores a
 * shape the box already overlaps), so a landing that overlaps a fence gate, a door, a slab or a stair walks straight
 * through it, and a pearl thrown up into a ceiling puts the head inside it. Fake walls (the client-only combat and claim
 * walls HCF sends) are worse: the server's pearl never sees them.
 * <p>
 * With {@code safe-landing}, the landing is the first spot, going back from 1.7's along the pearl's last flight
 * direction in {@code backtrack-step} steps for at most {@code max-backtrack} blocks, where a standing box of the thrower
 * fits both the server world and the thrower's client blocks (fake walls included), {@code pass-through} blocks ignored.
 * Every spot tried lies on the pearl's own path, so a landing never ends past anything the pearl hit. When none fits, the
 * teleport is dropped and (with {@code no-landing: refund}) the pearl is given back with its cooldown cleared.
 */
public final class PearlLanding {

    /** The landing 1.7 would use fits as it is. */
    public static final int AS_IS = 0;
    /** The landing moved back along the flight. */
    public static final int MOVED = 1;
    /** Nothing fits within the backtrack. */
    public static final int NONE = 2;

    /** {@code Entity.noCollision} tests the box shrunk by this, so a box resting on a face doesn't collide with it. */
    static final double SKIN = 1.0E-7;

    private PearlLanding() {
    }

    /** A block hit on a block only the thrower's client has: the pearl lands on it though the server's block there is air. */
    public static final class ClientWallHit extends BlockHitResult {
        public ClientWallHit(final Vec3 location, final Direction direction, final BlockPos pos, final boolean inside) {
            super(location, direction, pos, inside);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code Projectile.hitTargetOrDeflectSelf}, at {@code if (collidedWith.isAir()) return ProjectileDeflection.NONE}:
     * whether this block hit still counts though the server's block is air (a pearl stopped by a fake wall).
     */
    public static boolean clientWall(final BlockHitResult hit) {
        return hit instanceof ClientWallHit;
    }

    /**
     * {@code ThrownEnderpearl.onHit}, in place of {@code Vec3 teleportPos = this.oldPosition()}: where the owner lands, or
     * null when no spot fits (the pearl was refunded or dropped; the caller discards the pearl without teleporting).
     */
    public static @Nullable Vec3 destination(final ThrownEnderpearl pearl, final Entity owner, final Vec3 landing) {
        if (!(owner instanceof final ServerPlayer player) || !(pearl.level() instanceof final ServerLevel level)) {
            return landing;
        }
        final LegacyProjectileSettings settings = Revival.settings().legacyCombat(level.getWorld().getName()).projectiles();
        if (!settings.enabled() || !LegacyProjectiles.legacyType(pearl, settings) || !settings.rules().pearls().safeLanding()) {
            return landing;
        }
        final ProjectileRules.Pearls rules = settings.rules().pearls();
        final ProjectileBlocks server = ProjectileBlocks.of(level, CollisionContext.of(player));
        final ProjectileBlocks client = rules.clientWalls() ? ProjectileViews.current().clientBlocks(player) : null;
        final EntityDimensions dimensions = player.getDimensions(Pose.STANDING);
        final Vec3 flight = pearl.getDeltaMovement();
        final double[] out = new double[3];
        final int result = resolve(server, client, landing.x, landing.y, landing.z, flight.x, flight.y, flight.z, dimensions.width(), dimensions.height(),
            rules.maxBacktrack(), rules.backtrackStep(), rules, out);
        return switch (result) {
            case AS_IS -> landing;
            case MOVED -> new Vec3(out[0], out[1], out[2]);
            default -> {
                if (rules.refund()) {
                    refund(player, pearl);
                }
                yield null;
            }
        };
    }

    // Gives the pearl back and clears the pearl cooldown, as HCF pearl fixes do
    private static void refund(final ServerPlayer player, final ThrownEnderpearl pearl) {
        final ItemStack item = pearl.getItem().copyWithCount(1);
        if (item.isEmpty() || player.hasInfiniteMaterials()) {
            return;
        }
        player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(item));
        player.getInventory().placeItemBackInInventory(item, Prediction.SERVER_ONLY); // drops it when the inventory is full
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Clips
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * The server world clip of a pearl with {@code pass-through} blocks ({@code level.clipIncludingBorder} skips none):
     * {@link #clip} on the live world with the pearl's collision context, then the world border as
     * {@code CollisionGetter.clipIncludingBorder} applies it. A miss result when nothing is hit.
     */
    static BlockHitResult clipServer(final ServerLevel level, final Projectile pearl, final Vec3 from, final Vec3 to, final ProjectileRules.Pearls rules) {
        final BlockHitResult hit = clip(ProjectileBlocks.of(level, CollisionContext.of(pearl)), from, to, rules);
        final Vec3 at = hit != null ? hit.getLocation() : to;
        final WorldBorder border = level.getWorldBorder();
        if (border.isWithinBounds(from) && !border.isWithinBounds(at)) {
            final Vec3 delta = at.subtract(from);
            final Vec3 clamped = border.clampVec3ToBound(at);
            return new BlockHitResult(clamped, Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(clamped), false, true);
        }
        if (hit != null) {
            return hit;
        }
        final Vec3 delta = from.subtract(to);
        return BlockHitResult.miss(to, Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(to));
    }

    /**
     * {@code BlockGetter.clip} with {@code ClipContext.Block.COLLIDER} and no fluids, over any block source, skipping air
     * and {@code pass-through} blocks: the same block walk ({@code BlockGetter.traverseBlocks}, ends pulled 1e-7 outwards)
     * and the same per-block {@code VoxelShape.clip}, so on the same blocks it returns what the server's clip returns.
     * The first block on the walk whose shape the segment enters wins. Null when nothing is hit.
     */
    static @Nullable BlockHitResult clip(final ProjectileBlocks blocks, final Vec3 from, final Vec3 to, final ProjectileRules.@Nullable Pearls rules) {
        if (from.equals(to)) {
            return null;
        }
        final double toX = Mth.lerp(-1.0E-7, to.x, from.x);
        final double toY = Mth.lerp(-1.0E-7, to.y, from.y);
        final double toZ = Mth.lerp(-1.0E-7, to.z, from.z);
        final double fromX = Mth.lerp(-1.0E-7, from.x, to.x);
        final double fromY = Mth.lerp(-1.0E-7, from.y, to.y);
        final double fromZ = Mth.lerp(-1.0E-7, from.z, to.z);
        int x = Mth.floor(fromX);
        int y = Mth.floor(fromY);
        int z = Mth.floor(fromZ);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        BlockHitResult hit = clipBlock(blocks, pos, from, to, rules);
        if (hit != null) {
            return hit;
        }
        final double dx = toX - fromX;
        final double dy = toY - fromY;
        final double dz = toZ - fromZ;
        final int signX = Mth.sign(dx);
        final int signY = Mth.sign(dy);
        final int signZ = Mth.sign(dz);
        final double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / dx;
        final double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / dy;
        final double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / dz;
        double tX = tDeltaX * (signX > 0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
        double tY = tDeltaY * (signY > 0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    x += signX;
                    tX += tDeltaX;
                } else {
                    z += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                y += signY;
                tY += tDeltaY;
            } else {
                z += signZ;
                tZ += tDeltaZ;
            }
            hit = clipBlock(blocks, pos.set(x, y, z), from, to, rules);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static @Nullable BlockHitResult clipBlock(final ProjectileBlocks blocks, final BlockPos pos, final Vec3 from, final Vec3 to,
                                                      final ProjectileRules.@Nullable Pearls rules) {
        final BlockState state = blocks.blockState(pos.getX(), pos.getY(), pos.getZ());
        if (state.isAir() || rules != null && rules.passesThrough(state)) {
            return null;
        }
        final VoxelShape shape = blocks.collisionShape(state, pos);
        return shape.isEmpty() ? null : shape.clip(from, to, pos.immutable());
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The landing
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * The landing for feet at (x, y, z) after a flight along (dx, dy, dz): {@link #AS_IS} when a box of {@code width} ×
     * {@code height} fits there in {@code server} and {@code client} (null: no client view), else {@link #MOVED} with the
     * feet written to {@code out} at the first spot back along the flight, in {@code step}s up to {@code maxBacktrack},
     * where it fits, else {@link #NONE}.
     */
    static int resolve(final ProjectileBlocks server, final @Nullable ProjectileBlocks client, final double x, final double y, final double z,
                       final double dx, final double dy, final double dz, final double width, final double height, final double maxBacktrack,
                       final double step, final ProjectileRules.@Nullable Pearls rules, final double[] out) {
        if (fitsBoth(server, client, x, y, z, width, height, rules)) {
            return AS_IS;
        }
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 1.0E-7) || !(step > 0.0)) {
            return NONE;
        }
        final double ux = dx / length;
        final double uy = dy / length;
        final double uz = dz / length;
        final int steps = (int) Math.floor(maxBacktrack / step + 1.0E-9);
        for (int i = 1; i <= steps; i++) {
            final double back = i * step;
            final double fx = x - ux * back;
            final double fy = y - uy * back;
            final double fz = z - uz * back;
            if (fitsBoth(server, client, fx, fy, fz, width, height, rules)) {
                out[0] = fx;
                out[1] = fy;
                out[2] = fz;
                return MOVED;
            }
        }
        return NONE;
    }

    private static boolean fitsBoth(final ProjectileBlocks server, final @Nullable ProjectileBlocks client, final double x, final double y, final double z,
                                    final double width, final double height, final ProjectileRules.@Nullable Pearls rules) {
        final double half = width / 2.0;
        return fits(server, x - half, y, z - half, x + half, y + height, z + half, rules)
            && (client == null || fits(client, x - half, y, z - half, x + half, y + height, z + half, rules));
    }

    /**
     * Whether the box touches no block collision shape (shrunk by {@link #SKIN} as {@code Entity.noCollision} tests it, so
     * resting on a floor or against a wall fits). Blocks one past each side are read too, for shapes taller than a block
     * (fences and walls, 1.5). {@code pass-through} blocks don't count.
     */
    static boolean fits(final ProjectileBlocks blocks, final double minX, final double minY, final double minZ, final double maxX, final double maxY,
                        final double maxZ, final ProjectileRules.@Nullable Pearls rules) {
        final double bx0 = minX + SKIN;
        final double by0 = minY + SKIN;
        final double bz0 = minZ + SKIN;
        final double bx1 = maxX - SKIN;
        final double by1 = maxY - SKIN;
        final double bz1 = maxZ - SKIN;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(bx0) - 1, xEnd = Mth.floor(bx1) + 1; x <= xEnd; x++) {
            for (int y = Mth.floor(by0) - 1, yEnd = Mth.floor(by1) + 1; y <= yEnd; y++) {
                for (int z = Mth.floor(bz0) - 1, zEnd = Mth.floor(bz1) + 1; z <= zEnd; z++) {
                    final BlockState state = blocks.blockState(x, y, z);
                    if (state.isAir() || rules != null && rules.passesThrough(state)) {
                        continue;
                    }
                    final VoxelShape shape = blocks.collisionShape(state, pos.set(x, y, z));
                    if (shape.isEmpty()) {
                        continue;
                    }
                    final List<AABB> parts = shape.toAabbs();
                    for (int i = 0, size = parts.size(); i < size; i++) {
                        final AABB part = parts.get(i);
                        if (part.minX + x < bx1 && part.maxX + x > bx0 && part.minY + y < by1 && part.maxY + y > by0 && part.minZ + z < bz1 && part.maxZ + z > bz0) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
