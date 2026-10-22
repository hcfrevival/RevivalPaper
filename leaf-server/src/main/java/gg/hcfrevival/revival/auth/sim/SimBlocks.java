package gg.hcfrevival.revival.auth.sim;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Block-driven movement as the vanilla 26.3 client applies it to the local player: support positions
 * ({@code Entity.getOnPos}), climbing ({@code LivingEntity.onClimbable}), and {@code Entity.applyEffectsFromBlocks}:
 * {@code stepOn} (slime) and the inside-block walk ({@code checkInsideBlocks} over
 * {@code BlockGetter.forEachBlockIntersectedBetween}) with the movement-relevant {@code entityInside} effects: cobweb,
 * sweet berry bush, powder snow, honey and bubble columns (report 01 §6.4.1). Every other {@code entityInside} only
 * damages, ignites, triggers or plays sounds, which the real server does.
 * <p>
 * Not thread-safe; one per {@link MovementSim}.
 */
final class SimBlocks {

    private final SimCollision collision;
    private final SimLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final LongOpenHashSet visitedBlocks = new LongOpenHashSet();
    private final LongOpenHashSet walkVisited = new LongOpenHashSet();
    private final SimBox deflated = new SimBox();
    private final SimBox from = new SimBox();
    private double[] boxes = new double[6 * 64];
    private final double[] hit = new double[3];
    private boolean[] cells = new boolean[64];

    // the segment being walked (checkInsideBlocks' lambda captures)
    private SimPlayer player;
    private SimWorld world;
    private double fromX;
    private double fromY;
    private double fromZ;
    private double toX;
    private double toY;
    private double toZ;
    private boolean movedFar;
    private int maxIterations;
    private int iterations;
    private boolean freeze;

    SimBlocks(final SimCollision collision) {
        this.collision = collision;
        this.level = collision.level;
    }

    // ------------------------------------------------------------------------------------------------ positions

    /**
     * Entity.getOnPos(offset) (Entity.java:1101-1120): the supporting block, or a block under the position. Writes
     * into {@code out}.
     */
    BlockPos.MutableBlockPos onPos(final SimPlayer p, final SimWorld world, final float offset, final BlockPos.MutableBlockPos out) {
        if (p.hasSupportingBlock) {
            if (!(offset > 1.0E-5F)) {
                return out.set(p.supportX, p.supportY, p.supportZ);
            }
            final BlockState belowState = world.blockState(p.supportX, p.supportY, p.supportZ);
            if ((!(offset <= 0.5) || !belowState.is(BlockTags.FENCES)) && !belowState.is(BlockTags.WALLS) && !(belowState.getBlock() instanceof FenceGateBlock)) {
                return out.set(p.supportX, Mth.floor(p.y - offset), p.supportZ);
            }
            return out.set(p.supportX, p.supportY, p.supportZ);
        }
        return out.set(Mth.floor(p.x), Mth.floor(p.y - offset), Mth.floor(p.z));
    }

    /** Entity.getBlockJumpFactor (Entity.java:1122-1126). */
    float jumpFactor(final SimPlayer p, final SimWorld world) {
        final float here = world.blockState(p.blockX, p.blockY, p.blockZ).getBlock().getJumpFactor();
        this.onPos(p, world, 0.500001F, this.pos);
        final float below = world.blockState(this.pos.getX(), this.pos.getY(), this.pos.getZ()).getBlock().getJumpFactor();
        return here == 1.0 ? below : here;
    }

    /** Entity.getBlockSpeedFactor (Entity.java:1128-1136). */
    float entitySpeedFactor(final SimPlayer p, final SimWorld world) {
        final BlockState state = world.blockState(p.blockX, p.blockY, p.blockZ);
        final float here = state.getBlock().getSpeedFactor();
        if (state.getBlock() != Blocks.WATER && state.getBlock() != Blocks.BUBBLE_COLUMN) {
            if (here == 1.0) {
                this.onPos(p, world, 0.500001F, this.pos);
                return world.blockState(this.pos.getX(), this.pos.getY(), this.pos.getZ()).getBlock().getSpeedFactor();
            }
            return here;
        }
        return here;
    }

    /**
     * LivingEntity.onClimbable (LivingEntity.java:1742-1768) with Player's flying override: the block at the player's
     * position is climbable, or an open trapdoor over a ladder facing the same way.
     */
    boolean onClimbable(final SimPlayer p, final SimWorld world) {
        if (p.flying || p.spectator) {
            return false;
        }
        final BlockState state = world.blockState(p.blockX, p.blockY, p.blockZ);
        if (p.fallFlying && state.is(BlockTags.CAN_GLIDE_THROUGH)) {
            return false;
        } else if (state.is(BlockTags.CLIMBABLE)) {
            return true;
        } else if (state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN)) {
            final BlockState belowState = world.blockState(p.blockX, p.blockY - 1, p.blockZ);
            return belowState.getBlock() == Blocks.LADDER && belowState.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ applyEffectsFromBlocks

    /**
     * Entity.applyEffectsFromBlocks (Entity.java:946-1018): consumes the tick's movements (adding the gap to the current
     * position), runs {@code stepOn} for slime, then the inside-block walk and its effects.
     */
    void applyEffectsFromBlocks(final SimPlayer p, final SimWorld world) {
        final SimPlayer.Movements movements = p.movements;
        if (movements.size == 0) {
            movements.add(p.xo, p.yo, p.zo, p.x, p.y, p.z, false, 0.0, 0.0, 0.0);
        } else {
            final int last = (movements.size - 1) * 3;
            final double dx = p.x - movements.to[last];
            final double dy = p.y - movements.to[last + 1];
            final double dz = p.z - movements.to[last + 2];
            if (dx * dx + dy * dy + dz * dz > 9.999999439624929E-11) {
                movements.add(movements.to[last], movements.to[last + 1], movements.to[last + 2], p.x, p.y, p.z, false, 0.0, 0.0, 0.0);
            }
        }
        if (!p.noPhysics) {
            if (p.onGround) {
                this.onPos(p, world, 0.2F, this.pos);
                final BlockState effectState = world.blockState(this.pos.getX(), this.pos.getY(), this.pos.getZ());
                if (effectState.getBlock() == Blocks.SLIME_BLOCK) {
                    // SlimeBlock.stepOn (SlimeBlock.java:22-29)
                    final double absDeltaY = Math.abs(p.vy);
                    if (absDeltaY < 0.1 && !p.isShiftKeyDown()) {
                        final double scale = 0.4 + absDeltaY * 0.2;
                        MovementSim.setDelta(p, p.vx * scale, p.vy * 1.0, p.vz * scale);
                    }
                }
            }
            this.freeze = false;
            this.checkInsideBlocks(p, world);
            if (this.freeze) {
                p.isInPowderSnow = true; // InsideBlockEffectType.FREEZE, applied by the collector after the walk
            }
        }
        movements.clear();
    }

    /** Entity.checkInsideBlocks(movements, collector) (Entity.java:1325-1356). */
    private void checkInsideBlocks(final SimPlayer p, final SimWorld world) {
        this.player = p;
        this.world = world;
        final SimPlayer.Movements m = p.movements;
        for (int i = 0; i < m.size; i++) {
            final double fx = m.from[i * 3];
            final double fy = m.from[i * 3 + 1];
            final double fz = m.from[i * 3 + 2];
            final double tx = m.to[i * 3];
            final double ty = m.to[i * 3 + 1];
            final double tz = m.to[i * 3 + 2];
            // delta = to.subtract(from)
            final double dx = tx - fx;
            final double dy = ty - fy;
            final double dz = tz - fz;
            int maxMovementIterations = 16;
            if (m.hasAxis[i] && dx * dx + dy * dy + dz * dz > 0.0) {
                double px = fx;
                double py = fy;
                double pz = fz;
                final boolean zFirst = Math.abs(m.axis[i * 3]) < Math.abs(m.axis[i * 3 + 2]);
                for (int k = 0; k < 3; k++) {
                    final int axis = k == 0 ? 1 : (k == 1) == zFirst ? 2 : 0;
                    final double axisMove = axis == 0 ? dx : axis == 1 ? dy : dz;
                    if (axisMove != 0.0) {
                        // pos.relative(axis.getPositive(), axisMove): this + distance * unit
                        final double nx = px + axisMove * (axis == 0 ? 1 : 0);
                        final double ny = py + axisMove * (axis == 1 ? 1 : 0);
                        final double nz = pz + axisMove * (axis == 2 ? 1 : 0);
                        maxMovementIterations -= this.checkInsideBlocks(px, py, pz, nx, ny, nz, maxMovementIterations);
                        px = nx;
                        py = ny;
                        pz = nz;
                    }
                }
            } else {
                maxMovementIterations -= this.checkInsideBlocks(fx, fy, fz, tx, ty, tz, 16);
            }
            if (maxMovementIterations <= 0) {
                this.checkInsideBlocks(tx, ty, tz, tx, ty, tz, 1);
            }
        }
        this.visitedBlocks.clear();
        this.player = null;
        this.world = null;
    }

    /** Entity.checkInsideBlocks(from, to, ...) (Entity.java:1358-1428): returns the iterations used. */
    private int checkInsideBlocks(final double fx, final double fy, final double fz, final double tx, final double ty, final double tz, final int max) {
        final SimPlayer p = this.player;
        p.makeBoundingBox(this.deflated, tx, ty, tz, p.width, p.height);
        this.deflated.deflate(9.999999747378752E-6);
        final double ddx = tx - fx;
        final double ddy = ty - fy;
        final double ddz = tz - fz;
        // from.distanceToSqr(to) > Mth.square(0.9999900000002526)
        final double sx = tx - fx;
        final double sy = ty - fy;
        final double sz = tz - fz;
        this.movedFar = sx * sx + sy * sy + sz * sz > 0.9999900000002526 * 0.9999900000002526;
        this.fromX = fx;
        this.fromY = fy;
        this.fromZ = fz;
        this.toX = tx;
        this.toY = ty;
        this.toZ = tz;
        this.maxIterations = max;
        this.iterations = 0;
        this.forEachBlockIntersectedBetween(fx, fy, fz, ddx, ddy, ddz, this.deflated);
        return this.iterations + 1;
    }

    /** The checkInsideBlocks visitor (Entity.java:1371-1424). */
    private boolean visit(final int x, final int y, final int z, final int iteration) {
        final SimPlayer p = this.player;
        if (p.dead) {
            return false; // !isAlive()
        }
        if (iteration >= this.maxIterations) {
            return false;
        }
        this.iterations = iteration;
        final SimWorld world = this.world;
        final BlockState state = world.blockState(x, y, z);
        if (state.isAir()) {
            return true;
        }
        this.pos.set(x, y, z);
        boolean insideBlock;
        final SimShape inside = this.insideShape(p, state);
        if (inside.block) {
            insideBlock = true;
        } else {
            insideBlock = this.collidedWithShapeMovingFrom(p, inside, x, y, z);
        }
        boolean insideFluid = false;
        if (!insideBlock) {
            insideFluid = this.collidedWithFluid(p, state.getFluidState(), x, y, z);
        }
        if ((insideBlock || insideFluid) && this.visitedBlocks.add(BlockPos.asLong(x, y, z))) {
            if (insideBlock) {
                final boolean isPrecise = this.movedFar
                    || this.deflated.intersects(x, y, z, x + 1, y + 1, z + 1);
                this.entityInside(p, world, state, x, y, z, isPrecise);
            }
            // fluids' entityInside only extinguishes or ignites
        }
        return true;
    }

    /**
     * BlockState.getEntityInsideCollisionShape: powder snow uses the collision shape against the player (block when
     * empty); every other block's override reads no entity, so it is called with none.
     */
    private SimShape insideShape(final SimPlayer p, final BlockState state) {
        if (state.getBlock() == Blocks.POWDER_SNOW) {
            // PowderSnowBlock.getEntityInsideCollisionShape: CollisionContext.of(entity) taken now
            final SimShape shape = this.collision.powderSnowShape(p, this.pos);
            return shape.empty ? SimShape.BLOCK : shape;
        }
        final VoxelShape shape = state.getEntityInsideCollisionShape(this.level, this.pos, null);
        return this.collision.shapes.get(shape);
    }

    /**
     * Entity.collidedWithShapeMovingFrom (Entity.java:1448-1452) over {@code shape.move(pos).toAabbs()}: the box at
     * {@code from} swept along the travel against each part (AABB.collidedAlongVector, AABB.java:403-419).
     */
    private boolean collidedWithShapeMovingFrom(final SimPlayer p, final SimShape shape, final int bx, final int by, final int bz) {
        final int n = shape.sizeX * shape.sizeY * shape.sizeZ;
        if (this.cells.length < n) {
            this.cells = new boolean[n];
            this.boxes = new double[6 * n];
        }
        final int count = toAabbs(shape, true, bx, by, bz, this.boxes, this.cells);
        for (int i = 0; i < count; i++) {
            if (this.collidedAlongVector(p, this.boxes[i * 6], this.boxes[i * 6 + 1], this.boxes[i * 6 + 2],
                this.boxes[i * 6 + 3], this.boxes[i * 6 + 4], this.boxes[i * 6 + 5])) {
                return true;
            }
        }
        return false;
    }

    /** Entity.collidedWithFluid (Entity.java:1443-1446) with Fluid.getAABB (Fluid.java:112-119). */
    private boolean collidedWithFluid(final SimPlayer p, final FluidState fluid, final int x, final int y, final int z) {
        if (fluid.isEmpty()) {
            return false;
        }
        final float height = SimFluids.height(this.world, fluid, x, y, z);
        final double minX = x;
        final double minY = y;
        final double minZ = z;
        return this.collidedAlongVector(p, Math.min(minX, x + 1.0), Math.min(minY, y + (double) height), Math.min(minZ, z + 1.0),
            Math.max(minX, x + 1.0), Math.max(minY, y + (double) height), Math.max(minZ, z + 1.0));
    }

    /** AABB.collidedAlongVector for {@code makeBoundingBox(from)} and the travel {@code to - from}. */
    private boolean collidedAlongVector(final SimPlayer p, final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        p.makeBoundingBox(this.from, this.fromX, this.fromY, this.fromZ, p.width, p.height);
        final SimBox b = this.from;
        final double vx = this.toX - this.fromX;
        final double vy = this.toY - this.fromY;
        final double vz = this.toZ - this.fromZ;
        // getCenter: Mth.lerp(0.5, min, max)
        final double cx = b.minX + 0.5 * (b.maxX - b.minX);
        final double cy = b.minY + 0.5 * (b.maxY - b.minY);
        final double cz = b.minZ + 0.5 * (b.maxZ - b.minZ);
        final double ex = cx + vx;
        final double ey = cy + vy;
        final double ez = cz + vz;
        final double ix = (b.maxX - b.minX) * 0.5 - 1.0E-7;
        final double iy = (b.maxY - b.minY) * 0.5 - 1.0E-7;
        final double iz = (b.maxZ - b.minZ) * 0.5 - 1.0E-7;
        // inflate: new AABB(min - add, max + add), normalized
        final double x1 = minX - ix;
        final double y1 = minY - iy;
        final double z1 = minZ - iz;
        final double x2 = maxX + ix;
        final double y2 = maxY + iy;
        final double z2 = maxZ + iz;
        final double ax = Math.min(x1, x2);
        final double ay = Math.min(y1, y2);
        final double az = Math.min(z1, z2);
        final double bx = Math.max(x1, x2);
        final double by = Math.max(y1, y2);
        final double bz = Math.max(z1, z2);
        if (contains(ax, ay, az, bx, by, bz, ex, ey, ez) || contains(ax, ay, az, bx, by, bz, cx, cy, cz)) {
            return true;
        }
        return SimClip.clip(ax, ay, az, bx, by, bz, cx, cy, cz, ex, ey, ez, this.hit);
    }

    /** AABB.contains (AABB.java:261-263). */
    private static boolean contains(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
                                    final double x, final double y, final double z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }

    /**
     * VoxelShape.toAabbs (VoxelShape.java:131-135): BitSetDiscreteVoxelShape.forAllBoxes with merged neighbours
     * (BitSetDiscreteVoxelShape.java:160-200), coordinates {@code base + offset} when the shape is moved by a block
     * position (OffsetDoubleList), as is otherwise. {@code cells} holds one flag per cell and {@code out} six doubles per
     * cell. Returns the box count.
     */
    static int toAabbs(final SimShape shape, final boolean moved, final double bx, final double by, final double bz, final double[] out,
                       final boolean[] cells) {
        final int nx = shape.sizeX;
        final int ny = shape.sizeY;
        final int nz = shape.sizeZ;
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    cells[(x * ny + y) * nz + z] = shape.isFullWide(x, y, z);
                }
            }
        }
        int count = 0;
        for (int y = 0; y < ny; y++) {
            for (int x = 0; x < nx; x++) {
                int lastStartZ = -1;
                for (int z = 0; z <= nz; z++) {
                    if (z < nz && cells[(x * ny + y) * nz + z]) {
                        if (lastStartZ == -1) {
                            lastStartZ = z;
                        }
                    } else if (lastStartZ != -1) {
                        int endX = x;
                        int endY = y;
                        clearZStrip(cells, ny, nz, lastStartZ, z, x, y);
                        while (isZStripFull(cells, nx, ny, nz, lastStartZ, z, endX + 1, y)) {
                            clearZStrip(cells, ny, nz, lastStartZ, z, endX + 1, y);
                            endX++;
                        }
                        while (isXZRectangleFull(cells, nx, ny, nz, x, endX + 1, lastStartZ, z, endY + 1)) {
                            for (int cx = x; cx <= endX; cx++) {
                                clearZStrip(cells, ny, nz, lastStartZ, z, cx, endY + 1);
                            }
                            endY++;
                        }
                        // at most one box per cell, and out holds 6 doubles per cell
                        out[count * 6] = shape.get(0, x, moved, bx);
                        out[count * 6 + 1] = shape.get(1, y, moved, by);
                        out[count * 6 + 2] = shape.get(2, lastStartZ, moved, bz);
                        out[count * 6 + 3] = shape.get(0, endX + 1, moved, bx);
                        out[count * 6 + 4] = shape.get(1, endY + 1, moved, by);
                        out[count * 6 + 5] = shape.get(2, z, moved, bz);
                        count++;
                        lastStartZ = -1;
                    }
                }
            }
        }
        return count;
    }

    private static void clearZStrip(final boolean[] cells, final int ny, final int nz, final int startZ, final int endZ, final int x, final int y) {
        for (int z = startZ; z < endZ; z++) {
            cells[(x * ny + y) * nz + z] = false;
        }
    }

    private static boolean isZStripFull(final boolean[] cells, final int nx, final int ny, final int nz, final int startZ, final int endZ, final int x, final int y) {
        if (x >= nx || y >= ny) {
            return false;
        }
        for (int z = startZ; z < endZ; z++) {
            if (!cells[(x * ny + y) * nz + z]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isXZRectangleFull(final boolean[] cells, final int nx, final int ny, final int nz, final int startX, final int endX,
                                             final int startZ, final int endZ, final int y) {
        for (int x = startX; x < endX; x++) {
            if (!isZStripFull(cells, nx, ny, nz, startZ, endZ, x, y)) {
                return false;
            }
        }
        return true;
    }

    /** The movement-relevant {@code entityInside} implementations, as they run on the client for the local player. */
    private void entityInside(final SimPlayer p, final SimWorld world, final BlockState state, final int x, final int y, final int z, final boolean isPrecise) {
        if (state.getBlock() == Blocks.COBWEB) {
            // WebBlock.entityInside (WebBlock.java:19-28)
            if (p.weaving >= 0) {
                makeStuckInBlock(p, 0.5, 0.25, 0.5);
            } else {
                makeStuckInBlock(p, 0.25, 0.05000000074505806, 0.25);
            }
        } else if (state.getBlock() == Blocks.SWEET_BERRY_BUSH) {
            // SweetBerryBushBlock.entityInside: living, not a fox or bee (damage is server-side)
            makeStuckInBlock(p, 0.800000011920929, 0.75, 0.800000011920929);
        } else if (state.getBlock() == Blocks.POWDER_SNOW) {
            // PowderSnowBlock.entityInside (PowderSnowBlock.java:56-92): stuck while the player's own block is powder snow
            if (world.blockState(p.blockX, p.blockY, p.blockZ).getBlock() == Blocks.POWDER_SNOW) {
                makeStuckInBlock(p, 0.8999999761581421, 1.5, 0.8999999761581421);
            }
            this.freeze = true;
        } else if (state.getBlock() == Blocks.HONEY_BLOCK) {
            this.honey(p, x, y, z);
        } else if (state.getBlock() == Blocks.BUBBLE_COLUMN) {
            // BubbleColumnBlock.entityInside (BubbleColumnBlock.java:46-57)
            if (isPrecise) {
                final BlockState stateAbove = world.blockState(x, y + 1, z);
                // stateAbove's collision shape at pos (not pos.above()), empty context, as vanilla asks it
                final boolean nothingAbove = stateAbove.getCollisionShape(this.level, this.pos).isEmpty() && stateAbove.getFluidState().isEmpty();
                final boolean dragDown = state.getValue(BubbleColumnBlock.DRAG_DOWN);
                if (!p.flying) { // Player.onAboveBubbleColumn / onInsideBubbleColumn skip while flying
                    if (nothingAbove) {
                        final double yd = dragDown ? Math.max(-0.9, p.vy - 0.03) : Math.min(1.8, p.vy + 0.1);
                        MovementSim.setDelta(p, p.vx, yd, p.vz);
                    } else {
                        final double yd = dragDown ? Math.max(-0.3, p.vy - 0.03) : Math.min(0.7, p.vy + 0.06);
                        MovementSim.setDelta(p, p.vx, yd, p.vz);
                        p.fallDistance = 0.0;
                    }
                }
            }
        }
    }

    /** Player.makeStuckInBlock (Player.java:1482-1488): not while flying. */
    private static void makeStuckInBlock(final SimPlayer p, final double x, final double y, final double z) {
        if (!p.flying) {
            p.fallDistance = 0.0;
            p.stuckX = x;
            p.stuckY = y;
            p.stuckZ = z;
        }
    }

    /** HoneyBlock.entityInside: the wall slide (HoneyBlock.java:57-107). */
    private void honey(final SimPlayer p, final int x, final int y, final int z) {
        if (p.onGround) {
            return;
        }
        if (p.y > y + 0.9375 - 1.0E-7) {
            return;
        }
        if (p.vy / 0.9800000190734863 + 0.08 >= -0.08) {
            return;
        }
        final double dx = Math.abs(x + 0.5 - p.x);
        final double dz = Math.abs(z + 0.5 - p.z);
        final double overlapDistance = 0.4375 + p.width / 2.0F;
        if (!(dx + 1.0E-7 > overlapDistance || dz + 1.0E-7 > overlapDistance)) {
            return;
        }
        final double oldDeltaY = p.vy / 0.9800000190734863 + 0.08;
        final double newDeltaY = (-0.05 - 0.08) * 0.9800000190734863;
        if (oldDeltaY < -0.13) {
            final double horizontalReductionFactor = -0.05 / (p.vy / 0.9800000190734863 + 0.08);
            MovementSim.setDelta(p, p.vx * horizontalReductionFactor, newDeltaY, p.vz * horizontalReductionFactor);
        } else {
            MovementSim.setDelta(p, p.vx, newDeltaY, p.vz);
        }
        p.fallDistance = 0.0;
    }

    // ------------------------------------------------------------------------------------------------ forEachBlockIntersectedBetween

    /**
     * BlockGetter.forEachBlockIntersectedBetween (BlockGetter.java:173-201) for {@code from + travel}: start-box cells,
     * then the DDA of {@code addCollisionsAlongTravel}, then the target-box cells.
     */
    private boolean forEachBlockIntersectedBetween(final double fx, final double fy, final double fz, final double tx, final double ty, final double tz,
                                                   final SimBox aabbAtTarget) {
        if (tx * tx + ty * ty + tz * tz < Mth.square(1.0E-5F)) {
            // BlockPos.betweenClosed(aabbAtTarget): containing(min)..containing(max), x fastest, then y, then z
            final int x0 = Mth.floor(aabbAtTarget.minX);
            final int y0 = Mth.floor(aabbAtTarget.minY);
            final int z0 = Mth.floor(aabbAtTarget.minZ);
            final int x1 = Mth.floor(aabbAtTarget.maxX);
            final int y1 = Mth.floor(aabbAtTarget.maxY);
            final int z1 = Mth.floor(aabbAtTarget.maxZ);
            final int minX = Math.min(x0, x1);
            final int minY = Math.min(y0, y1);
            final int minZ = Math.min(z0, z1);
            final int maxX = Math.max(x0, x1);
            final int maxY = Math.max(y0, y1);
            final int maxZ = Math.max(z0, z1);
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        if (!this.visit(x, y, z, 0)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
        final LongOpenHashSet visited = this.walkVisited;
        visited.clear();
        // betweenCornersInDirection(aabbAtTarget.move(travel.scale(-1.0)), travel)
        final double sx = tx * -1.0;
        final double sy = ty * -1.0;
        final double sz = tz * -1.0;
        if (!this.cornersInDirection(
            Mth.floor(Math.min(aabbAtTarget.minX + sx, aabbAtTarget.maxX + sx)), Mth.floor(Math.min(aabbAtTarget.minY + sy, aabbAtTarget.maxY + sy)),
            Mth.floor(Math.min(aabbAtTarget.minZ + sz, aabbAtTarget.maxZ + sz)), Mth.floor(Math.max(aabbAtTarget.minX + sx, aabbAtTarget.maxX + sx)),
            Mth.floor(Math.max(aabbAtTarget.minY + sy, aabbAtTarget.maxY + sy)), Mth.floor(Math.max(aabbAtTarget.minZ + sz, aabbAtTarget.maxZ + sz)),
            tx, ty, tz, visited, 0, true)) {
            return false;
        }
        final int iterations = this.addCollisionsAlongTravel(visited, tx, ty, tz, aabbAtTarget);
        if (iterations < 0) {
            return false;
        }
        return this.cornersInDirection(Mth.floor(aabbAtTarget.minX), Mth.floor(aabbAtTarget.minY), Mth.floor(aabbAtTarget.minZ),
            Mth.floor(aabbAtTarget.maxX), Mth.floor(aabbAtTarget.maxY), Mth.floor(aabbAtTarget.maxZ), tx, ty, tz, visited, iterations + 1, false);
    }

    /**
     * BlockPos.betweenCornersInDirection (BlockPos.java:526-598): the cells between two corners, starting at the corner
     * the travel comes from, axes in {@code axisStepOrder(direction)} order. {@code always}: visit every cell and add it
     * to {@code visited} (the start box); otherwise only cells {@code visited.add} accepts.
     */
    private boolean cornersInDirection(final int ax, final int ay, final int az, final int bx, final int by, final int bz,
                                       final double dx, final double dy, final double dz, final LongOpenHashSet visited, final int iteration,
                                       final boolean always) {
        final int minX = Math.min(ax, bx);
        final int minY = Math.min(ay, by);
        final int minZ = Math.min(az, bz);
        final int maxX = Math.max(ax, bx);
        final int maxY = Math.max(ay, by);
        final int maxZ = Math.max(az, bz);
        final int diffX = maxX - minX;
        final int diffY = maxY - minY;
        final int diffZ = maxZ - minZ;
        final int startX = dx >= 0.0 ? minX : maxX;
        final int startY = dy >= 0.0 ? minY : maxY;
        final int startZ = dz >= 0.0 ? minZ : maxZ;
        // axisStepOrder: Y first; then Z, X when |x| < |z|, else X, Z
        final boolean zFirst = Math.abs(dx) < Math.abs(dz);
        final int second = zFirst ? 2 : 0;
        final int third = zFirst ? 0 : 2;
        final int stepY = dy >= 0.0 ? 1 : -1;
        final int stepX = dx >= 0.0 ? 1 : -1;
        final int stepZ = dz >= 0.0 ? 1 : -1;
        final int secondMax = second == 0 ? diffX : diffZ;
        final int thirdMax = third == 0 ? diffX : diffZ;
        for (int i = 0; i <= diffY; i++) {
            for (int j = 0; j <= secondMax; j++) {
                for (int k = 0; k <= thirdMax; k++) {
                    final int x = startX + stepX * (second == 0 ? j : k);
                    final int y = startY + stepY * i;
                    final int z = startZ + stepZ * (second == 2 ? j : k);
                    if (always) {
                        if (!this.visit(x, y, z, iteration)) {
                            return false;
                        }
                        visited.add(BlockPos.asLong(x, y, z));
                    } else if (visited.add(BlockPos.asLong(x, y, z)) && !this.visit(x, y, z, iteration)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** BlockGetter.addCollisionsAlongTravel (BlockGetter.java:203-275). Returns the iterations, or -1 when stopped. */
    private int addCollisionsAlongTravel(final LongOpenHashSet visited, final double mx, final double my, final double mz, final SimBox target) {
        final double boxSizeX = target.maxX - target.minX;
        final double boxSizeY = target.maxY - target.minY;
        final double boxSizeZ = target.maxZ - target.minZ;
        // getFurthestCorner (BlockGetter.java:277-289)
        final double xDot = Math.abs(mx);
        final double yDot = Math.abs(my);
        final double zDot = Math.abs(mz);
        final int xSign = mx >= 0.0 ? 1 : -1;
        final int ySign = my >= 0.0 ? 1 : -1;
        final int zSign = mz >= 0.0 ? 1 : -1;
        final int cornerX;
        final int cornerY;
        final int cornerZ;
        if (xDot <= yDot && xDot <= zDot) {
            cornerX = -xSign;
            cornerY = -zSign;
            cornerZ = ySign;
        } else if (yDot <= zDot) {
            cornerX = zSign;
            cornerY = -ySign;
            cornerZ = -xSign;
        } else {
            cornerX = -ySign;
            cornerY = xSign;
            cornerZ = -zSign;
        }
        final double centerX = target.minX + 0.5 * (target.maxX - target.minX);
        final double centerY = target.minY + 0.5 * (target.maxY - target.minY);
        final double centerZ = target.minZ + 0.5 * (target.maxZ - target.minZ);
        final double toCornerX = centerX + boxSizeX * 0.5 * cornerX;
        final double toCornerY = centerY + boxSizeY * 0.5 * cornerY;
        final double toCornerZ = centerZ + boxSizeZ * 0.5 * cornerZ;
        final double fromCornerX = toCornerX - mx;
        final double fromCornerY = toCornerY - my;
        final double fromCornerZ = toCornerZ - mz;
        int blockX = Mth.floor(fromCornerX);
        int blockY = Mth.floor(fromCornerY);
        int blockZ = Mth.floor(fromCornerZ);
        final int signX = Mth.sign(mx);
        final int signY = Mth.sign(my);
        final int signZ = Mth.sign(mz);
        final double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / mx;
        final double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / my;
        final double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / mz;
        double tX = tDeltaX * (signX > 0 ? 1.0 - Mth.frac(fromCornerX) : Mth.frac(fromCornerX));
        double tY = tDeltaY * (signY > 0 ? 1.0 - Mth.frac(fromCornerY) : Mth.frac(fromCornerY));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - Mth.frac(fromCornerZ) : Mth.frac(fromCornerZ));
        int iterations = 0;
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
            final double[] hit = this.hit;
            if (SimClip.clip(blockX, blockY, blockZ, blockX + 1, blockY + 1, blockZ + 1,
                fromCornerX, fromCornerY, fromCornerZ, toCornerX, toCornerY, toCornerZ, hit)) {
                iterations++;
                final double cornerHitX = Mth.clamp(hit[0], blockX + 9.999999747378752E-6, blockX + 1.0 - 9.999999747378752E-6);
                final double cornerHitY = Mth.clamp(hit[1], blockY + 9.999999747378752E-6, blockY + 1.0 - 9.999999747378752E-6);
                final double cornerHitZ = Mth.clamp(hit[2], blockZ + 9.999999747378752E-6, blockZ + 1.0 - 9.999999747378752E-6);
                final int oppositeX = Mth.floor(cornerHitX - boxSizeX * cornerX);
                final int oppositeY = Mth.floor(cornerHitY - boxSizeY * cornerY);
                final int oppositeZ = Mth.floor(cornerHitZ - boxSizeZ * cornerZ);
                if (!this.cornersInDirection(blockX, blockY, blockZ, oppositeX, oppositeY, oppositeZ, mx, my, mz, visited, iterations, false)) {
                    return -1;
                }
            }
        }
        return iterations;
    }
}
