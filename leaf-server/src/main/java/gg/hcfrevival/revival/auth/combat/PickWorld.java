package gg.hcfrevival.revival.auth.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The blocks the attacker's client has, as {@link PickRay}'s block pass reads them. The live implementation is the
 * attacker's client world view (server world plus unacked block changes and the client's own predictions, design §4);
 * when {@code auth.sim.SimWorld} lands this is a one-method adapter over it.
 * <p>
 * Only the outline shape is read ({@code ClipContext.Block.OUTLINE}); fluids never stop the attack ray
 * ({@code ClipContext.Fluid.NONE}, Entity.java:2075-2080).
 */
public interface PickWorld {

    /** The block state the attacker's client has at (x, y, z); air for anything it doesn't have. */
    BlockState blockState(int x, int y, int z);

    /**
     * {@code state.getShape(level, pos, CollisionContext.of(attacker))}, in block-local coordinates. The default reads it
     * with no level and an empty context, which is exact for every block except two whose outline depends on the held
     * item (light and scaffolding, LightBlock.java:59-61, ScaffoldingBlock.java:51-57: an attacker holding a sword sees
     * the empty-context shape) and the few that read a block entity (an open shulker box reads as closed). Override to
     * pass the real level or context.
     */
    default VoxelShape outlineShape(final BlockState state, final BlockPos pos) {
        return state.getShape(EmptyBlockGetter.INSTANCE, pos, CollisionContext.empty());
    }

    /** A world over any {@code BlockGetter} (the live level, a test world), with the given outline context. */
    static PickWorld of(final BlockGetter level, final CollisionContext context) {
        return new PickWorld() {
            private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            @Override
            public BlockState blockState(final int x, final int y, final int z) {
                return level.getBlockState(this.cursor.set(x, y, z));
            }

            @Override
            public VoxelShape outlineShape(final BlockState state, final BlockPos pos) {
                return state.getShape(level, pos, context);
            }
        };
    }
}
