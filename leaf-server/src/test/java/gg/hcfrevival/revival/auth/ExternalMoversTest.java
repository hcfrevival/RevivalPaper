package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.combat.ProjectileBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** Classic audit #4: a push explains only what it moves, along its own axis, as far as it travels. */
@RevivalTest
@RevivalRegistries
class ExternalMoversTest {

    private static AABB player(final double x, final double y, final double z) {
        return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    void aPistonPushesOnlyItsLaneAlongItsFacing() {
        final ExternalMovers movers = new ExternalMovers();
        // A piston at the origin facing east extends: its head and the 12 blocks it can push, x 1..13
        movers.blockEvent(10, BlockPos.ZERO, Blocks.PISTON, 0, Direction.EAST.get3DDataValue(), Blocks.PISTON.defaultBlockState(), 14.0);
        final double[] part = new double[3];
        assertTrue(movers.lane(11, player(5.5, 0.0, 0.5), 12), "in the lane");
        assertTrue(movers.adoptable(0.4, 0.0, 0.0, part), "pushed east");
        assertEquals(0.4, part[0]);
        assertTrue(movers.adoptable(0.4, 0.3, 0.2, part), "only the push's part of a claim that also climbs");
        assertEquals(0.4, part[0]);
        assertEquals(0.0, part[1]);
        assertEquals(0.0, part[2]);
        assertFalse(movers.adoptable(-0.4, 0.0, 0.0, part), "never against the push");
        assertFalse(movers.adoptable(0.0, 0.55, 0.0, part), "never up: a claim-wall climb");
        assertFalse(movers.adoptable(0.6, 0.0, 0.0, part), "at most one tick of piston travel");
        movers.adopted(new double[] {0.51, 0.0, 0.0});
        movers.adopted(new double[] {0.48, 0.0, 0.0});
        assertTrue(movers.adoptable(0.03, 0.0, 0.0, part), "the push's last 0.01s");
        assertFalse(movers.adoptable(0.4, 0.0, 0.0, part), "a piston moves a block, no further");
        assertFalse(movers.lane(11, player(-3.5, 0.0, 0.5), 12), "behind the piston");
        assertFalse(movers.lane(11, player(5.5, 0.0, 2.5), 12), "beside the lane");
        assertFalse(movers.lane(30, player(5.5, 0.0, 0.5), 12), "the event's window is over");
    }

    // A column at x = z = 0: what is below y = 0, potent sulfur at 0, water sources from 1 up to (not including) top
    private static Long2ObjectOpenHashMap<BlockState> column(final BlockState below, final PotentSulfurState state, final int top) {
        final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        blocks.put(BlockPos.asLong(0, -1, 0), below);
        blocks.put(BlockPos.asLong(0, 0, 0), Blocks.POTENT_SULFUR.defaultBlockState().setValue(PotentSulfurBlock.STATE, state));
        for (int y = 1; y < top; y++) {
            blocks.put(BlockPos.asLong(0, y, 0), Blocks.WATER.defaultBlockState());
        }
        return blocks;
    }

    private static boolean geyser(final Long2ObjectOpenHashMap<BlockState> blocks, final double feetY) {
        final ProjectileBlocks world = (x, y, z) -> {
            final BlockState state = blocks.get(BlockPos.asLong(x, y, z));
            return state != null ? state : Blocks.AIR.defaultBlockState();
        };
        return ExternalMovers.geyserBelow(player(0.5, feetY, 0.5), world, 24);
    }

    @Test
    void onlyAnActiveGeyserUnderUnbrokenWaterReachingAirExemptsATick() {
        // Review 3 #6: potent sulfur launches only ERUPTING or CONTINUOUS (PotentSulfurBlock.getTicker), what is in its water
        // column, and only while that column is unbroken water reaching air within 4 blocks (findNoxiousGasSourceBlock)
        assertTrue(geyser(column(Blocks.LAVA.defaultBlockState(), PotentSulfurState.CONTINUOUS, 4), 1.0), "swimming in a geyser");
        assertTrue(geyser(column(Blocks.LAVA.defaultBlockState(), PotentSulfurState.CONTINUOUS, 4), 2.5), "at its surface");
        assertTrue(geyser(column(Blocks.MAGMA_BLOCK.defaultBlockState(), PotentSulfurState.ERUPTING, 5), 1.0), "an eruption, 4 deep");
        assertFalse(geyser(column(Blocks.MAGMA_BLOCK.defaultBlockState(), PotentSulfurState.DORMANT, 4), 1.0), "between eruptions");
        assertFalse(geyser(column(Blocks.STONE.defaultBlockState(), PotentSulfurState.WET, 4), 1.0), "nothing under it to erupt");
        assertFalse(geyser(column(Blocks.LAVA.defaultBlockState(), PotentSulfurState.CONTINUOUS, 7), 1.0), "6 deep: no launch");
        final Long2ObjectOpenHashMap<BlockState> lid = column(Blocks.LAVA.defaultBlockState(), PotentSulfurState.CONTINUOUS, 4);
        lid.put(BlockPos.asLong(0, 4, 0), Blocks.STONE.defaultBlockState());
        assertFalse(geyser(lid, 1.0), "a sealed column launches nothing");
        // The audit's case: a sulfur buried under stone below a base's pool (dry: no water above it)
        final Long2ObjectOpenHashMap<BlockState> buried = column(Blocks.STONE.defaultBlockState(), PotentSulfurState.DRY, 1);
        buried.put(BlockPos.asLong(0, 1, 0), Blocks.STONE.defaultBlockState());
        for (int y = 2; y < 5; y++) {
            buried.put(BlockPos.asLong(0, y, 0), Blocks.WATER.defaultBlockState());
        }
        assertFalse(geyser(buried, 2.0), "a pool over buried sulfur");
        // ... and an active one in its own pocket, a stone layer between it and the pool
        final Long2ObjectOpenHashMap<BlockState> pocket = column(Blocks.LAVA.defaultBlockState(), PotentSulfurState.CONTINUOUS, 2);
        pocket.put(BlockPos.asLong(0, 2, 0), Blocks.STONE.defaultBlockState());
        for (int y = 3; y < 6; y++) {
            pocket.put(BlockPos.asLong(0, y, 0), Blocks.WATER.defaultBlockState());
        }
        assertFalse(geyser(pocket, 3.0), "a pool over a geyser's sealed pocket");
    }

    @Test
    void aStickyRetractPullsBackAndADropMovesNothing() {
        final ExternalMovers movers = new ExternalMovers();
        movers.blockEvent(10, BlockPos.ZERO, Blocks.STICKY_PISTON, 1, Direction.NORTH.get3DDataValue(), Blocks.STICKY_PISTON.defaultBlockState(), 14.0);
        final double[] part = new double[3];
        assertTrue(movers.lane(11, player(0.5, 0.0, -2.5), 12));
        assertTrue(movers.adoptable(0.0, 0.0, 0.3, part), "pulled back south, toward the base");
        assertFalse(movers.adoptable(0.0, 0.0, -0.3, part));
        final ExternalMovers dropped = new ExternalMovers();
        dropped.blockEvent(10, BlockPos.ZERO, Blocks.PISTON, 2, Direction.EAST.get3DDataValue(), Blocks.PISTON.defaultBlockState(), 14.0);
        assertFalse(dropped.lane(11, player(1.5, 0.0, 0.5), 12));
    }

    @Test
    void aShulkerBoxLiftsWhatStandsOnItsLid() {
        final ExternalMovers movers = new ExternalMovers();
        movers.blockEvent(10, BlockPos.ZERO, Blocks.SHULKER_BOX, 1, 1,
            Blocks.SHULKER_BOX.defaultBlockState().setValue(ShulkerBoxBlock.FACING, Direction.UP), 14.0);
        final double[] part = new double[3];
        assertTrue(movers.lane(11, player(0.5, 1.0, 0.5), 12), "standing on it");
        assertTrue(movers.adoptable(0.0, 0.3, 0.0, part));
        assertFalse(movers.adoptable(0.3, 0.0, 0.0, part), "a lid lifts, it doesn't shove sideways");
        movers.adopted(new double[] {0.0, 0.55, 0.0});
        assertFalse(movers.adoptable(0.0, 0.1, 0.0, part), "a lid opens half a block (and 0.01 a tick)");
        assertFalse(movers.lane(11, player(4.5, 1.0, 0.5), 12), "three blocks away");
    }
}
