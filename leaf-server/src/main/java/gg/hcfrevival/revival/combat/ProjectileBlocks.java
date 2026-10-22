package gg.hcfrevival.revival.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The blocks a pearl or a landing box is tested against: the server world, or one player's client view. The client view
 * is {@code auth.view.WorldView}, which has the same {@code blockState(int, int, int)}, so the netcode adapts it with a
 * method reference ({@code worldView::blockState}).
 * <p>
 * Only collision shapes are read. The default reads them with no level and an empty context, which is exact for every
 * block except the few whose collision depends on the entity (scaffolding, powder snow) or a block entity (a moving
 * piston); the server world passes the real level and the pearl's or the player's context instead.
 */
@FunctionalInterface
public interface ProjectileBlocks {

    /** The block state at (x, y, z); air for anything not there (an unloaded or unsent chunk). */
    BlockState blockState(int x, int y, int z);

    /** {@code state.getCollisionShape(level, pos, context)}, in block-local coordinates. */
    default VoxelShape collisionShape(final BlockState state, final BlockPos pos) {
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos, CollisionContext.empty());
    }

    /** The server world as an entity with {@code context} collides with it. Unloaded chunks read as air and are never loaded. */
    static ProjectileBlocks of(final Level level, final CollisionContext context) {
        return new ProjectileBlocks() {
            private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            @Override
            public BlockState blockState(final int x, final int y, final int z) {
                final BlockState state = level.getBlockStateIfLoaded(this.cursor.set(x, y, z));
                return state == null ? Blocks.AIR.defaultBlockState() : state;
            }

            @Override
            public VoxelShape collisionShape(final BlockState state, final BlockPos pos) {
                return state.getCollisionShape(level, pos, context);
            }
        };
    }
}
