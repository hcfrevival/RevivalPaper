package gg.hcfrevival.revival.auth.visibility;

/**
 * Line of sight between two boxes through a client's blocks (design §11). The viewer's box is its eye region grown by
 * what it can move before the client could show the target; the target's box is grown the same way. The target is
 * visible when any of the 9 sample points of one box (the corners and the centre) sees any of the other's: blocks
 * between are walked one by one (Amanatides-Woo), and only full opaque cubes hide.
 * <p>
 * Conservative on purpose: a false "visible" only sends a player as vanilla would; a false "hidden" would hide a player
 * someone can see. Allocation-free.
 */
public final class Occlusion {

    /** Rays longer than this count as visible (tracking range is shorter; a walk this long would be costly). */
    static final double MAX_RAY = 128.0;

    private Occlusion() {
    }

    /** Whether any sample point of box A sees any sample point of box B. */
    public static boolean boxesSeeEachOther(final OcclusionWorld world, final double aMinX, final double aMinY, final double aMinZ, final double aMaxX,
                                            final double aMaxY, final double aMaxZ, final double bMinX, final double bMinY, final double bMinZ,
                                            final double bMaxX, final double bMaxY, final double bMaxZ) {
        for (int i = 0; i < 9; i++) {
            final double ax = sample(i, 0, aMinX, aMaxX);
            final double ay = sample(i, 1, aMinY, aMaxY);
            final double az = sample(i, 2, aMinZ, aMaxZ);
            for (int j = 0; j < 9; j++) {
                if (clear(world, ax, ay, az, sample(j, 0, bMinX, bMaxX), sample(j, 1, bMinY, bMaxY), sample(j, 2, bMinZ, bMaxZ))) {
                    return true;
                }
            }
        }
        return false;
    }

    // Point i of a box: 0 the centre, 1..8 the corners (bit 0 x, bit 1 y, bit 2 z of i - 1)
    private static double sample(final int i, final int axis, final double min, final double max) {
        if (i == 0) {
            return (min + max) * 0.5;
        }
        return ((i - 1) >> axis & 1) == 0 ? min : max;
    }

    /** Whether no occluding block lies on the segment between the two points (the blocks they are in don't count). */
    public static boolean clear(final OcclusionWorld world, final double ax, final double ay, final double az, final double bx, final double by,
                                final double bz) {
        final double dx = bx - ax;
        final double dy = by - ay;
        final double dz = bz - az;
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > MAX_RAY) {
            return true;
        }
        int x = (int) Math.floor(ax);
        int y = (int) Math.floor(ay);
        int z = (int) Math.floor(az);
        final int endX = (int) Math.floor(bx);
        final int endY = (int) Math.floor(by);
        final int endZ = (int) Math.floor(bz);
        final int stepX = dx > 0.0 ? 1 : dx < 0.0 ? -1 : 0;
        final int stepY = dy > 0.0 ? 1 : dy < 0.0 ? -1 : 0;
        final int stepZ = dz > 0.0 ? 1 : dz < 0.0 ? -1 : 0;
        final double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        final double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        final double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double maxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? x + 1.0 - ax : ax - x) * deltaX;
        double maxY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? y + 1.0 - ay : ay - y) * deltaY;
        double maxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? z + 1.0 - az : az - z) * deltaZ;
        // t runs 0..1 along the segment; every block entered before the end block is tested
        while (true) {
            if (maxX < maxY && maxX < maxZ) {
                if (maxX > 1.0) {
                    return true;
                }
                x += stepX;
                maxX += deltaX;
            } else if (maxY < maxZ) {
                if (maxY > 1.0) {
                    return true;
                }
                y += stepY;
                maxY += deltaY;
            } else {
                if (maxZ > 1.0) {
                    return true;
                }
                z += stepZ;
                maxZ += deltaZ;
            }
            if (x == endX && y == endY && z == endZ) {
                return true;
            }
            if (world.occludes(x, y, z)) {
                return false;
            }
        }
    }
}
