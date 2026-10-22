package gg.hcfrevival.revival.auth.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * A {@link BlockGetter} over a {@link SimWorld}, only so vanilla's per-state data methods ({@code getCollisionShape},
 * {@code isSuffocating}, {@code isFaceSturdy}, {@code getOffset}) can be called. It has no block entities, so blocks
 * whose shape comes from one (moving pistons) read as empty.
 * <p>
 * The two {@code ...IfLoaded} methods are Paper's additions to {@code BlockGetter}; they carry no {@code @Override} so
 * the class also compiles against the vanilla jar (report 07 §1.5).
 */
final class SimLevel implements BlockGetter {

    private SimWorld world;

    void world(final SimWorld world) {
        this.world = world;
    }

    SimWorld world() {
        return this.world;
    }

    @Override
    public BlockState getBlockState(final BlockPos pos) {
        return this.world.blockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(final BlockPos pos) {
        return this.world.fluidState(pos.getX(), pos.getY(), pos.getZ());
    }

    public @Nullable BlockState getBlockStateIfLoaded(final BlockPos pos) {
        return this.world.isChunkLoaded(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
            ? this.getBlockState(pos) : null;
    }

    public @Nullable FluidState getFluidIfLoaded(final BlockPos pos) {
        return this.world.isChunkLoaded(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
            ? this.getFluidState(pos) : null;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(final BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return this.world.height();
    }

    @Override
    public int getMinY() {
        return this.world.minY();
    }
}
