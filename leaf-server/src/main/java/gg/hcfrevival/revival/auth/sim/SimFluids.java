package gg.hcfrevival.revival.auth.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * Fluid contact as the vanilla 26.3 client computes it: {@code EntityFluidInteraction} (the 26.3 rewrite,
 * EntityFluidInteraction.java:38-233), {@code Entity.updateFluidInteraction} (Entity.java:1699-1723) and the fluid
 * methods it calls ({@code FlowingFluid.getHeight/getFlow}, {@code FluidState.getHeightForCamera}), ported so no
 * server-side fluid code (which Paper patches) is involved.
 * <p>
 * Vanilla keeps one tracker per fluid holder (source and flowing water are different holders) and one current
 * accumulator per tag. Only the four vanilla fluids exist, so trackers are fixed slots here; their order never shows in
 * any result ({@code getFluidHeight} is a max, {@code isEyeInFluid} an any).
 */
final class SimFluids {

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final SimLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
    private final SimBox box = new SimBox();
    private double flowX;
    private double flowY;
    private double flowZ;

    SimFluids(final SimLevel level) {
        this.level = level;
    }

    /** Tracker and current state; lives in {@link SimPlayer}. */
    static final class State {
        // trackers: 0 water, 1 flowing water, 2 lava, 3 flowing lava
        final double[] height = new double[4];
        final boolean[] eyesInside = new boolean[4];
        // current accumulators: 0 WATER tag, 1 LAVA tag
        final double[] currentHeight = new double[2];
        final double[] currentX = new double[2];
        final double[] currentY = new double[2];
        final double[] currentZ = new double[2];
        final int[] currentCount = new int[2];

        /** EntityFluidInteraction.getFluidHeight(tag) for WATER (0) or LAVA (1). */
        double fluidHeight(final int tag) {
            double h = 0.0;
            for (int t = tag * 2; t < tag * 2 + 2; t++) {
                if (this.height[t] > h) {
                    h = this.height[t];
                }
            }
            return h;
        }

        boolean isInLava() {
            return this.fluidHeight(1) > 0.0;
        }

        boolean isInWater() {
            return this.fluidHeight(0) > 0.0;
        }

        /** EntityFluidInteraction.isEyeInFluid(WATER). */
        boolean isEyeInWater() {
            return this.eyesInside[0] || this.eyesInside[1];
        }

        void copyFrom(final State o) {
            System.arraycopy(o.height, 0, this.height, 0, 4);
            System.arraycopy(o.eyesInside, 0, this.eyesInside, 0, 4);
            System.arraycopy(o.currentHeight, 0, this.currentHeight, 0, 2);
            System.arraycopy(o.currentX, 0, this.currentX, 0, 2);
            System.arraycopy(o.currentY, 0, this.currentY, 0, 2);
            System.arraycopy(o.currentZ, 0, this.currentZ, 0, 2);
            System.arraycopy(o.currentCount, 0, this.currentCount, 0, 2);
        }
    }

    private static int tracker(final Fluid fluid) {
        if (fluid == Fluids.WATER) {
            return 0;
        } else if (fluid == Fluids.FLOWING_WATER) {
            return 1;
        } else if (fluid == Fluids.LAVA) {
            return 2;
        } else if (fluid == Fluids.FLOWING_LAVA) {
            return 3;
        }
        return -1;
    }

    /**
     * Entity.updateFluidInteraction (Entity.java:1699-1723) for a player: trackers and currents, the fall reset in
     * water, {@code wasTouchingWater}, then the currents pushed into the velocity unless flying.
     */
    boolean updateFluidInteraction(final SimPlayer p, final SimWorld world) {
        final boolean pushedByFluid = !p.flying; // Player.isPushedByFluid
        final boolean inAny = this.update(p, world, !pushedByFluid);
        final boolean inWater = p.fluids.isInWater();
        final boolean inLava = p.fluids.isInLava();
        if (inWater) {
            p.fallDistance = 0.0;
        }
        p.wasTouchingWater = inWater;
        if (pushedByFluid) {
            if (inWater) {
                this.applyCurrent(p, 0, 0.014);
            }
            if (inLava) {
                this.applyCurrent(p, 1, world.fastLava() ? 0.007 : 0.0023333333333333335);
            }
        }
        return inAny;
    }

    /** EntityFluidInteraction.update (EntityFluidInteraction.java:38-108). */
    private boolean update(final SimPlayer p, final SimWorld world, final boolean ignoreCurrent) {
        final State s = p.fluids;
        for (int t = 0; t < 4; t++) {
            s.height[t] = 0.0;
            s.eyesInside[t] = false;
        }
        for (int c = 0; c < 2; c++) {
            s.currentHeight[c] = 0.0;
            s.currentX[c] = 0.0;
            s.currentY[c] = 0.0;
            s.currentZ[c] = 0.0;
            s.currentCount[c] = 0;
        }
        // getFluidInteractionBox: bb.deflate(0.001) (no vehicle)
        final SimBox fluidBox = this.box.set(p.bb).deflate(0.001);
        final int x0 = Mth.floor(fluidBox.minX);
        final int y0 = Mth.floor(fluidBox.minY);
        final int z0 = Mth.floor(fluidBox.minZ);
        final int x1 = Mth.ceil(fluidBox.maxX) - 1;
        final int y1 = Mth.ceil(fluidBox.maxY) - 1;
        final int z1 = Mth.ceil(fluidBox.maxZ) - 1;
        if (!hasFluidAndLoaded(world, x0 - 1, y0, z0 - 1, x1 + 1, y1, z1 + 1)) {
            return false;
        }
        final double entityY = p.bb.minY;
        final int eyeBlockX = p.blockX;
        final double eyeY = p.eyeY();
        final int eyeBlockZ = p.blockZ;
        Fluid lastFluidType = null;
        int tracker = -1;
        int current = -1;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    final FluidState fluidState = world.fluidState(x, y, z);
                    if (fluidState.isEmpty()) {
                        continue;
                    }
                    final double fluidBottom = y;
                    final double fluidTop = fluidBottom + height(world, fluidState, x, y, z);
                    if (fluidTop < fluidBox.minY) {
                        continue;
                    }
                    final Fluid fluidType = fluidState.getType();
                    if (fluidType != lastFluidType) {
                        lastFluidType = fluidType;
                        tracker = tracker(fluidType);
                        if (!ignoreCurrent) {
                            current = tracker < 0 ? -1 : tracker / 2;
                        }
                    }
                    if (tracker < 0) {
                        continue; // a non-vanilla fluid: no tracker slot (vanilla would track it but it is in no movement tag)
                    }
                    if (x == eyeBlockX && z == eyeBlockZ && eyeY >= fluidBottom) {
                        final double fluidTopForCamera = fluidBottom + this.heightForCamera(world, fluidState, x, y, z);
                        if (eyeY <= fluidTopForCamera) {
                            s.eyesInside[tracker] = true;
                        }
                    }
                    s.height[tracker] = Math.max(fluidTop - entityY, s.height[tracker]);
                    if (current >= 0) {
                        this.flow(world, fluidState, x, y, z);
                        s.currentHeight[current] = Math.max(s.height[tracker], s.currentHeight[current]);
                        double fx = this.flowX;
                        double fy = this.flowY;
                        double fz = this.flowZ;
                        if (s.currentHeight[current] < 0.4) {
                            final double scale = s.currentHeight[current];
                            fx = fx * scale;
                            fy = fy * scale;
                            fz = fz * scale;
                        }
                        // CurrentAccumulator.accumulate: Vec3.add
                        s.currentX[current] = s.currentX[current] + fx;
                        s.currentY[current] = s.currentY[current] + fy;
                        s.currentZ[current] = s.currentZ[current] + fz;
                        s.currentCount[current]++;
                    }
                }
            }
        }
        return lastFluidType != null;
    }

    /** EntityFluidInteraction.hasFluidAndLoaded (EntityFluidInteraction.java:110-138). */
    private static boolean hasFluidAndLoaded(final SimWorld world, final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
        final int sx0 = x0 >> 4;
        final int sy0 = y0 >> 4;
        final int sz0 = z0 >> 4;
        final int sx1 = x1 >> 4;
        final int sy1 = y1 >> 4;
        final int sz1 = z1 >> 4;
        boolean hasFluid = false;
        for (int cz = sz0; cz <= sz1; cz++) {
            for (int cx = sx0; cx <= sx1; cx++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return false;
                }
                for (int sy = sy0; sy <= sy1; sy++) {
                    hasFluid |= world.sectionHasFluid(cx, sy, cz);
                }
            }
        }
        return hasFluid;
    }

    /** CurrentAccumulator.applyTo for a player (EntityFluidInteraction.java:215-233): the average flow, scaled. */
    private void applyCurrent(final SimPlayer p, final int tag, final double scale) {
        final State s = p.fluids;
        final double ax = s.currentX[tag];
        final double ay = s.currentY[tag];
        final double az = s.currentZ[tag];
        if (s.currentCount[tag] != 0 && !(ax * ax + ay * ay + az * az < 9.999999747378752E-6)) {
            final double inv = 1.0 / s.currentCount[tag];
            double ix = ax * inv;
            double iy = ay * inv;
            double iz = az * inv;
            ix = ix * scale;
            iy = iy * scale;
            iz = iz * scale;
            if (Math.abs(p.vx) < 0.003 && Math.abs(p.vz) < 0.003 && Math.sqrt(ix * ix + iy * iy + iz * iz) < 0.0045000000000000005) {
                // impulse.normalize().scale(0.0045000000000000005)
                final double dist = Math.sqrt(ix * ix + iy * iy + iz * iz);
                if (dist < 9.999999747378752E-6) {
                    ix = 0.0;
                    iy = 0.0;
                    iz = 0.0;
                } else {
                    ix = ix / dist;
                    iy = iy / dist;
                    iz = iz / dist;
                }
                ix = ix * 0.0045000000000000005;
                iy = iy * 0.0045000000000000005;
                iz = iz * 0.0045000000000000005;
            }
            // addDeltaMovement: only finite momentum, then setDeltaMovement's own finite check
            if (Double.isFinite(ix) && Double.isFinite(iy) && Double.isFinite(iz)) {
                MovementSim.setDelta(p, p.vx + ix, p.vy + iy, p.vz + iz);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ fluid methods

    /** FluidState.getHeight → FlowingFluid.getHeight (FlowingFluid.java:449-456), EmptyFluid 0. */
    static float height(final SimWorld world, final FluidState fluid, final int x, final int y, final int z) {
        if (fluid.isEmpty()) {
            return 0.0F;
        }
        return fluid.getType().isSame(world.fluidState(x, y + 1, z).getType()) ? 1.0F : ownHeight(fluid);
    }

    /** FlowingFluid.getOwnHeight: amount / 9 (FlowingFluid.java:459-461), EmptyFluid 0. */
    static float ownHeight(final FluidState fluid) {
        return fluid.isEmpty() ? 0.0F : fluid.getAmount() / 9.0F;
    }

    /** FluidState.getHeightForCamera (FluidState.java:53-62): a source under a sturdy face counts as full. */
    private float heightForCamera(final SimWorld world, final FluidState fluid, final int x, final int y, final int z) {
        if (fluid.isSource()) {
            this.pos.set(x, y + 1, z);
            if (world.blockState(x, y + 1, z).isFaceSturdy(this.level, this.pos, Direction.DOWN)) {
                return 1.0F;
            }
        }
        return height(world, fluid, x, y, z);
    }

    /** FlowingFluid.getFlow (FlowingFluid.java:53-98), into {@code flowX/Y/Z}. */
    private void flow(final SimWorld world, final FluidState fluidState, final int x, final int y, final int z) {
        final Fluid self = fluidState.getType();
        double fx = 0.0;
        double fz = 0.0;
        for (final Direction direction : HORIZONTAL) {
            final int nx = x + direction.getStepX();
            final int nz = z + direction.getStepZ();
            final FluidState neighbourFluid = world.fluidState(nx, y, nz);
            if (affectsFlow(self, neighbourFluid)) {
                float neighborHeight = ownHeight(neighbourFluid);
                float distance = 0.0F;
                if (neighborHeight == 0.0F) {
                    if (!world.blockState(nx, y, nz).is(BlockTags.BLOCKS_FLUID_FLOW)) {
                        final FluidState belowNeighborState = world.fluidState(nx, y - 1, nz);
                        if (affectsFlow(self, belowNeighborState)) {
                            neighborHeight = ownHeight(belowNeighborState);
                            if (neighborHeight > 0.0F) {
                                distance = ownHeight(fluidState) - (neighborHeight - 0.8888889F);
                            }
                        }
                    }
                } else if (neighborHeight > 0.0F) {
                    distance = ownHeight(fluidState) - neighborHeight;
                }
                if (distance != 0.0F) {
                    fx += direction.getStepX() * distance;
                    fz += direction.getStepZ() * distance;
                }
            }
        }
        double rx = fx;
        double ry = 0.0;
        double rz = fz;
        if (fluidState.getValue(FlowingFluid.FALLING)) {
            final SimLevel level = this.level;
            for (final Direction direction : HORIZONTAL) {
                final int nx = x + direction.getStepX();
                final int nz = z + direction.getStepZ();
                if (this.isSolidFace(world, level, self, nx, y, nz, direction) || this.isSolidFace(world, level, self, nx, y + 1, nz, direction)) {
                    // flow.normalize().add(0, -6, 0)
                    final double d = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    if (d < 9.999999747378752E-6) {
                        rx = 0.0;
                        ry = 0.0;
                        rz = 0.0;
                    } else {
                        rx = rx / d;
                        ry = ry / d;
                        rz = rz / d;
                    }
                    rx = rx + 0.0;
                    ry = ry + -6.0;
                    rz = rz + 0.0;
                    break;
                }
            }
        }
        final double d = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (d < 9.999999747378752E-6) {
            this.flowX = 0.0;
            this.flowY = 0.0;
            this.flowZ = 0.0;
        } else {
            this.flowX = rx / d;
            this.flowY = ry / d;
            this.flowZ = rz / d;
        }
    }

    /** FlowingFluid.affectsFlow: empty or the same fluid. */
    private static boolean affectsFlow(final Fluid self, final FluidState neighbour) {
        return neighbour.isEmpty() || neighbour.getType().isSame(self);
    }

    /** FlowingFluid.isSolidFace (FlowingFluid.java:104-115). */
    private boolean isSolidFace(final SimWorld world, final SimLevel level, final Fluid self, final int x, final int y, final int z, final Direction direction) {
        final BlockState state = world.blockState(x, y, z);
        final FluidState fluidState = world.fluidState(x, y, z);
        if (fluidState.getType().isSame(self)) {
            return false;
        } else if (direction == Direction.UP) {
            return true;
        }
        this.side.set(x, y, z);
        return !(state.getBlock() instanceof IceBlock) && state.isFaceSturdy(level, this.side, direction);
    }
}
