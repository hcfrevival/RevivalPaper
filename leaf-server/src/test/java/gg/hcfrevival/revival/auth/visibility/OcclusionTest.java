package gg.hcfrevival.revival.auth.visibility;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §11: line of sight through the blocks a client was sent. */
@RevivalTest
class OcclusionTest {

    private static final class Blocks implements OcclusionWorld {
        final Set<Long> solid = new HashSet<>();

        Blocks wall(final int x, final int y0, final int y1, final int z0, final int z1) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    this.solid.add(((long) x << 40) ^ ((long) y << 20) ^ z);
                }
            }
            return this;
        }

        @Override
        public boolean occludes(final int x, final int y, final int z) {
            return this.solid.contains(((long) x << 40) ^ ((long) y << 20) ^ z);
        }
    }

    // A player box at (x, 0, z): 0.6 wide, 1.8 tall
    private static boolean sees(final OcclusionWorld world, final double ax, final double az, final double bx, final double bz) {
        return Occlusion.boxesSeeEachOther(world, ax - 0.3, 1.0, az - 0.3, ax + 0.3, 2.8, az + 0.3, bx - 0.3, 1.0, bz - 0.3, bx + 0.3, 2.8, bz + 0.3);
    }

    @Test
    void openGroundSees() {
        assertTrue(sees(new Blocks(), 0.5, 0.5, 10.5, 0.5));
    }

    @Test
    void aWideTallWallHides() {
        final Blocks world = new Blocks().wall(5, 0, 6, -4, 4);
        assertFalse(sees(world, 0.5, 0.5, 10.5, 0.5));
        assertFalse(Occlusion.clear(world, 0.5, 1.6, 0.5, 10.5, 1.6, 0.5));
    }

    @Test
    void aGapInTheWallShows() {
        final Blocks world = new Blocks().wall(5, 0, 6, -4, -1).wall(5, 0, 6, 1, 4); // z = 0 open
        assertTrue(sees(world, 0.5, 0.5, 10.5, 0.5));
    }

    @Test
    void aLowWallLetsTheHeadsSeeEachOther() {
        final Blocks world = new Blocks().wall(5, 0, 1, -4, 4); // up to y = 2
        assertTrue(sees(world, 0.5, 0.5, 10.5, 0.5));
    }

    @Test
    void theBlocksTheEndsAreInDontCount() {
        final Blocks world = new Blocks().wall(0, 1, 1, 0, 0);
        assertTrue(Occlusion.clear(world, 0.5, 1.5, 0.5, 4.5, 1.5, 0.5), "an eye inside a block still looks out");
    }

    @Test
    void visibilityHidesAfterRepeatedChecksAndShowsAtOnce() {
        final Visibility visibility = new Visibility();
        assertTrue(visibility.decide(7, 0, false, 2), "one hidden check is not enough");
        assertFalse(visibility.decide(7, 2, false, 2));
        assertFalse(visibility.sent(7));
        assertTrue(visibility.decide(7, 4, true, 2), "seen again: sent at once");
        assertTrue(visibility.due(7, 6, 2));
        assertFalse(visibility.due(7, 5, 2));
    }
}
