package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
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
