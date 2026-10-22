package gg.hcfrevival.revival.auth.sim;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Ray clips the movement code needs, ported from vanilla 26.3: {@code AABB.clip} (AABB.java:296-401) and the
 * {@code level.clip(ClipContext(from, to, FALLDAMAGE_RESETTING, WATER, player))} test of {@code Entity.move}
 * (Entity.java:779-787) over {@code BlockGetter.traverseBlocks} (BlockGetter.java:121-170). Only hit or miss matters to
 * movement, so no hit results are built.
 */
final class SimClip {

    private SimClip() {
    }

    /**
     * {@code AABB.clip(minX, ..., from, to)} (static): writes the hit point to {@code out} and returns true, or returns
     * false for no hit.
     */
    static boolean clip(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
                        final double fromX, final double fromY, final double fromZ, final double toX, final double toY, final double toZ, final double[] out) {
        final double dx = toX - fromX;
        final double dy = toY - fromY;
        final double dz = toZ - fromZ;
        final double scale = scale(minX, minY, minZ, maxX, maxY, maxZ, fromX, fromY, fromZ, dx, dy, dz, 1.0);
        if (Double.isNaN(scale)) {
            return false;
        }
        // from.add(scale * dx, scale * dy, scale * dz)
        out[0] = fromX + scale * dx;
        out[1] = fromY + scale * dy;
        out[2] = fromZ + scale * dz;
        return true;
    }

    /**
     * AABB.getDirection (AABB.java:333-356): the clip scale against one box, NaN when no face is hit, starting from the
     * current best {@code scale} (the {@code scaleReference} array of vanilla).
     */
    static double scale(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
                        final double fromX, final double fromY, final double fromZ, final double dx, final double dy, final double dz, final double start) {
        double scale = start;
        boolean hit = false;
        double s;
        if (dx > 1.0E-7) {
            s = clipPoint(scale, dx, dy, dz, minX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ);
            if (!Double.isNaN(s)) {
                scale = s;
                hit = true;
            }
        } else if (dx < -1.0E-7) {
            s = clipPoint(scale, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, fromX, fromY, fromZ);
            if (!Double.isNaN(s)) {
                scale = s;
                hit = true;
            }
        }
        if (dy > 1.0E-7) {
            s = clipPoint(scale, dy, dz, dx, minY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX);
            if (!Double.isNaN(s)) {
                scale = s;
                hit = true;
            }
        } else if (dy < -1.0E-7) {
            s = clipPoint(scale, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, fromY, fromZ, fromX);
            if (!Double.isNaN(s)) {
                scale = s;
                hit = true;
            }
        }
        if (dz > 1.0E-7) {
            s = clipPoint(scale, dz, dx, dy, minZ, minX, maxX, minY, maxY, fromZ, fromX, fromY);
            if (!Double.isNaN(s)) {
                scale = s;
                hit = true;
            }
        } else if (dz < -1.0E-7) {
            s = clipPoint(scale, dz, dx, dy, maxZ, minX, maxX, minY, maxY, fromZ, fromX, fromY);
            if (!Double.isNaN(s)) {
                scale = s;
                hit = true;
            }
        }
        return hit ? scale : Double.NaN;
    }

    /** AABB.clipPoint (AABB.java:358-381): the new scale, or NaN when this face is not hit closer. */
    private static double clipPoint(final double scaleReference, final double da, final double db, final double dc, final double point,
                                    final double minB, final double maxB, final double minC, final double maxC,
                                    final double fromA, final double fromB, final double fromC) {
        final double s = (point - fromA) / da;
        final double pb = fromB + s * db;
        final double pc = fromC + s * dc;
        if (0.0 < s && s < scaleReference && minB - 1.0E-7 < pb && pb < maxB + 1.0E-7 && minC - 1.0E-7 < pc && pc < maxC + 1.0E-7) {
            return s;
        }
        return Double.NaN;
    }

    /**
     * {@code level.clip(new ClipContext(from, to, FALLDAMAGE_RESETTING, WATER, player)).getType() != MISS}: a full
     * block for {@code FALL_DAMAGE_RESETTING} blocks (and end portals and gateways, for players), and water's fluid
     * shape, along the voxel walk of traverseBlocks.
     * <p>
     * shortcut: water's shape is taken as {@code box(0, 0, 0, 1, getHeight(level, pos), 1)} at this position; vanilla's
     * {@code FlowingFluid.getShape} caches the box per fluid state at the height of the first position ever asked, which
     * no server can know. It only matters for a fall of more than a block per tick through a water surface.
     */
    static boolean fallResetHit(final SimWorld world, final double fromX0, final double fromY0, final double fromZ0, final double toX0, final double toY0, final double toZ0) {
        if (Double.compare(fromX0, toX0) == 0 && Double.compare(fromY0, toY0) == 0 && Double.compare(fromZ0, toZ0) == 0) {
            return false; // from.equals(to)
        }
        // Mth.lerp(-1.0E-7, a, b) = a + -1.0E-7 * (b - a)
        final double toX = toX0 + -1.0E-7 * (fromX0 - toX0);
        final double toY = toY0 + -1.0E-7 * (fromY0 - toY0);
        final double toZ = toZ0 + -1.0E-7 * (fromZ0 - toZ0);
        final double fromX = fromX0 + -1.0E-7 * (toX0 - fromX0);
        final double fromY = fromY0 + -1.0E-7 * (toY0 - fromY0);
        final double fromZ = fromZ0 + -1.0E-7 * (toZ0 - fromZ0);
        int blockX = Mth.floor(fromX);
        int blockY = Mth.floor(fromY);
        int blockZ = Mth.floor(fromZ);
        if (hitsBlock(world, blockX, blockY, blockZ, fromX0, fromY0, fromZ0, toX0, toY0, toZ0)) {
            return true;
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
                    blockX += signX;
                    tX += tDeltaX;
                } else {
                    blockZ += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                blockY += signY;
                tY += tDeltaY;
            } else {
                blockZ += signZ;
                tZ += tDeltaZ;
            }
            if (hitsBlock(world, blockX, blockY, blockZ, fromX0, fromY0, fromZ0, toX0, toY0, toZ0)) {
                return true;
            }
        }
        return false;
    }

    /** BlockGetter.clip's per-block consumer: the block shape's clip or the fluid shape's clip is not null. */
    private static boolean hitsBlock(final SimWorld world, final int x, final int y, final int z,
                                     final double fromX, final double fromY, final double fromZ, final double toX, final double toY, final double toZ) {
        final BlockState state = world.blockState(x, y, z);
        if (state.is(BlockTags.FALL_DAMAGE_RESETTING) || state.getBlock() == Blocks.END_GATEWAY || state.getBlock() == Blocks.END_PORTAL) {
            if (shapeClip(x, y, z, 1.0, true, fromX, fromY, fromZ, toX, toY, toZ)) {
                return true;
            }
        }
        final FluidState fluid = world.fluidState(x, y, z);
        if (fluid.is(FluidTags.WATER)) {
            final float height = SimFluids.height(world, fluid, x, y, z);
            // Shapes.box(0, 0, 0, 1, height, 1): Shapes.block() at height 1, else an ArrayVoxelShape
            return shapeClip(x, y, z, height, height == 1.0F, fromX, fromY, fromZ, toX, toY, toZ);
        }
        return false;
    }

    /**
     * VoxelShape.clip (VoxelShape.java:153-170) for the box {@code [0,1] x [0,height] x [0,1]} at the block: a hit when
     * the point 0.001 along the ray is inside, else the box's AABB clip.
     */
    private static boolean shapeClip(final int bx, final int by, final int bz, final double height, final boolean cube,
                                     final double fromX, final double fromY, final double fromZ, final double toX, final double toY, final double toZ) {
        if (height < 1.0E-7) {
            return false; // Shapes.create gives empty
        }
        final double dx = toX - fromX;
        final double dy = toY - fromY;
        final double dz = toZ - fromZ;
        if (dx * dx + dy * dy + dz * dz < 1.0E-7) {
            return false;
        }
        // testPoint = from.add(diff.scale(0.001))
        final double tx = fromX + dx * 0.001;
        final double ty = fromY + dy * 0.001;
        final double tz = fromZ + dz * 0.001;
        if (inside(tx - bx, 1.0, cube) && inside(ty - by, height, cube) && inside(tz - bz, 1.0, cube)) {
            return true;
        }
        // AABB.clip(toAabbs(), from, to, pos): the one box moved by pos
        final double s = scale(0.0 + bx, 0.0 + by, 0.0 + bz, 1.0 + bx, height + by, 1.0 + bz, fromX, fromY, fromZ, dx, dy, dz, 1.0);
        return !Double.isNaN(s);
    }

    /** {@code isFullWide(findIndex(axis, coord))} for a one-cell shape with coordinates [0, max]. */
    private static boolean inside(final double coord, final double max, final boolean cube) {
        final int index;
        if (cube) {
            final double clamped = coord < -1.0 ? -1.0 : Math.min(coord, 1.0);
            index = (int) Math.floor(clamped);
        } else {
            // binary search over [0, max]: first i with coord < c[i], minus one
            index = coord < 0.0 ? -1 : coord < max ? 0 : 1;
        }
        return index == 0;
    }
}
