package gg.hcfrevival.revival.auth.combat;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Small fixtures for the pick and hit tests: a sparse block world and attackers. */
final class CombatScenes {

    /** Sparse blocks; anything unset is air. */
    static final class Blocks2 implements PickWorld {
        final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();

        Blocks2 set(final int x, final int y, final int z, final BlockState state) {
            this.blocks.put(BlockPos.asLong(x, y, z), state);
            return this;
        }

        @Override
        public BlockState blockState(final int x, final int y, final int z) {
            final BlockState state = this.blocks.get(BlockPos.asLong(x, y, z));
            return state == null ? Blocks.AIR.defaultBlockState() : state;
        }
    }

    /** A standing player's box with its feet at (x, y, z): {@code EntityDimensions.makeBoundingBox} of 0.6F x 1.8F. */
    static AABB player(final double x, final double y, final double z) {
        final float w = 0.6F / 2.0F;
        return new AABB(x - w, y, z - w, x + w, y + 1.8F, z + w);
    }

    /** A standing attacker with feet at (x, y, z), not moving (xo == x), eye height 1.62F. */
    static AttackerSnapshot attacker(final double x, final double y, final double z, final float yRot, final float xRot, final double blockRange,
                                     final double entityRange) {
        return new AttackerSnapshot(x, y, z, x, y, z, 1.62F, yRot, xRot, player(x, y, z), blockRange, entityRange, false, false);
    }

    private CombatScenes() {
    }
}
