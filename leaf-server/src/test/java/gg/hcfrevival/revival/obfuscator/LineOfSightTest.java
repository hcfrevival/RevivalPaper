package gg.hcfrevival.revival.obfuscator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

@RevivalTest
class LineOfSightTest {

    /** A grid of opaque blocks that counts lookups and remembers the last block visited. */
    private static final class Grid implements LineOfSight.Occluder {
        private final Set<Long> solid = new HashSet<>();
        int lookups;
        int lastX;
        int lastY;
        int lastZ;

        Grid fill(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        this.solid.add(key(x, y, z));
                    }
                }
            }
            return this;
        }

        Grid clear(final int x, final int y, final int z) {
            this.solid.remove(key(x, y, z));
            return this;
        }

        @Override
        public boolean opaque(final int x, final int y, final int z) {
            this.lookups++;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            return this.solid.contains(key(x, y, z));
        }

        private static long key(final int x, final int y, final int z) {
            return ((long) x << 42) ^ ((long) y << 21) ^ (z & 0x1FFFFFL);
        }
    }

    private static final double EYE = 1.62;

    // A wall across x = 5 between a viewer at x < 5 and a target at x > 5
    private static Grid wall() {
        return new Grid().fill(5, 0, -3, 5, 4, 3);
    }

    @Test
    void openAirIsClearAndEndsAtTheEndBlock() {
        final Grid grid = new Grid();
        assertEquals(-1, LineOfSight.blockedAt(grid, 0.5, 1.5, 0.5, 20.5, 3.5, -7.5));
        assertEquals(20 + 2 + 8 + 1, grid.lookups, "one lookup per block on the Manhattan path, start included");
        assertEquals(20, grid.lastX);
        assertEquals(3, grid.lastY);
        assertEquals(-8, grid.lastZ);
    }

    @Test
    void aWallBlocksFromEitherEnd() {
        assertEquals(3, LineOfSight.blockedAt(wall(), 2.5, 1.6, 0.5, 8.5, 1.6, 0.5), "blocks 2, 3, 4 then the wall at 5");
        assertEquals(3, LineOfSight.blockedAt(wall(), 8.5, 1.6, 0.5, 2.5, 1.6, 0.5), "blocks 8, 7, 6 then the wall at 5");
    }

    @Test
    void aWindowIsClearOnlyThroughTheHole() {
        final Grid grid = wall().clear(5, 1, 0);
        assertEquals(-1, LineOfSight.blockedAt(grid, 2.5, 1.5, 0.5, 8.5, 1.5, 0.5));
        assertEquals(3, LineOfSight.blockedAt(grid, 2.5, 2.5, 0.5, 8.5, 2.5, 0.5));
    }

    @Test
    void aPointInsideStoneNeitherSeesNorIsSeen() {
        assertEquals(0, LineOfSight.blockedAt(wall(), 5.5, 1.5, 0.5, 8.5, 1.5, 0.5), "the start block counts");
        assertEquals(3, LineOfSight.blockedAt(wall(), 2.5, 1.5, 0.5, 5.5, 1.5, 0.5), "the end block counts");
    }

    @Test
    void negativeCoordinatesFloorTowardsMinusInfinity() {
        final Grid grid = new Grid().fill(-5, 0, -3, -5, 4, 3);
        assertEquals(2, LineOfSight.blockedAt(grid, -2.5, 1.5, 0.5, -8.5, 1.5, 0.5), "blocks -3, -4 then the wall at -5");
    }

    @Test
    void visibleTriesTheCentreThenTheCorners() {
        final double[] eye = {0.5, EYE, 0.5};
        assertFalse(visible(wall(), eye), "a full wall hides the target");
        assertTrue(visible(wall().clear(5, 1, 0), eye), "a hole on the centre line shows it");
        assertTrue(visible(wall().clear(5, 0, 0), eye), "a hole only a bottom corner lines up with still shows it");
    }

    @Test
    void raysStartAtTheEnclosedEnd() {
        final Grid targetEnclosed = new Grid().fill(9, -1, -1, 12, 3, 2).clear(10, 0, 0).clear(10, 1, 0);
        assertFalse(visible(targetEnclosed, new double[] {0.5, EYE, 0.5}));
        assertTrue(targetEnclosed.lookups <= 9 * 3, "every ray dies at the target's own walls: " + targetEnclosed.lookups);

        final Grid viewerEnclosed = new Grid().fill(-1, 0, -1, 1, 3, 1).clear(0, 1, 0).clear(0, 2, 0);
        assertFalse(visible(viewerEnclosed, new double[] {0.5, EYE, 0.5}));
        assertTrue(viewerEnclosed.lookups <= 12 + 8 * 3, "after the centre ray, the corner rays start at the viewer's walls: " + viewerEnclosed.lookups);
    }

    @Test
    void cameraEndsClipAtWallsAndSkipWhenPinned() {
        final double[] ends = new double[12];
        assertEquals(2, LineOfSight.addCameraEnds(new Grid(), 0.5, EYE, 0.5, 0.0F, 0.0F, 4.0, ends, 0));
        assertEquals(-3.5, ends[2], 1.0E-6, "facing +z, the back camera sits 4 behind");
        assertEquals(4.5, ends[5], 1.0E-6, "and the front camera 4 ahead");

        final Grid walled = new Grid().fill(-2, 0, -2, 2, 3, -2).fill(-2, 0, 1, 2, 3, 1);
        assertEquals(1, LineOfSight.addCameraEnds(walled, 0.5, EYE, 0.5, 0.0F, 0.0F, 4.0, ends, 0), "a camera pinned to the eye is skipped");
        assertEquals(-1.0, ends[2], 1.0E-6, "the back camera stops before the wall at z = -2");

        assertEquals(2, LineOfSight.addCameraEnds(new Grid(), 0.5, EYE, 0.5, 90.0F, 0.0F, 4.0, ends, 0));
        assertEquals(4.5, ends[0], 1.0E-6, "yaw 90 faces -x, so the back camera is at +x");
    }

    @Test
    void theBackCameraSeesOverAWallTheEyeCannot() {
        final Grid grid = new Grid().fill(-2, 0, 3, 2, 1, 3); // a 2-high wall at z = 3
        final double[] ends = new double[12];
        ends[0] = 0.5;
        ends[1] = EYE;
        ends[2] = 0.5;
        assertFalse(LineOfSight.visible(grid, 0.2, 0.0, 7.2, 0.8, 1.5, 7.8, ends, 1, 1), "hidden from the eye");
        final int count = LineOfSight.addCameraEnds(grid, 0.5, EYE, 0.5, 0.0F, 60.0F, 4.0, ends, 1);
        assertEquals(3, count);
        assertTrue(LineOfSight.visible(grid, 0.2, 0.0, 7.2, 0.8, 1.5, 7.8, ends, count, 1), "looking down over the wall, the third-person camera sees the target");
    }

    @Test
    void theBoxStretchesTowardItsVelocity() {
        // Behind the +x end of a wall the target is hidden standing still; the box stretched 2 blocks toward +x rounds it
        final Grid grid = new Grid().fill(-3, 0, 8, 3, 4, 8);
        final double[] eye = {0.5, EYE, 0.5};
        assertFalse(LineOfSight.visible(grid, 2.2, 0.0, 9.2, 2.8, 1.5, 9.8, eye, 1, 1));
        assertTrue(LineOfSight.visible(grid, 2.2, 0.0, 9.2, 2.8 + 2.0, 1.5, 9.8, eye, 1, 1));
    }

    // A crouching target 10 blocks from the viewer, with the given viewer end points
    private static boolean visible(final Grid grid, final double[] ends) {
        return LineOfSight.visible(grid, 10.2, 0.0, 0.2, 10.8, 1.5, 0.8, ends, ends.length / 3, ends.length / 3);
    }
}
