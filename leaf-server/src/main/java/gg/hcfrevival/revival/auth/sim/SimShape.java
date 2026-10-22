package gg.hcfrevival.revival.auth.sim;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.reflect.Field;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A voxel shape as plain coordinate data: the coordinate list of each axis and the full/empty flag of each cell, read
 * once from a {@link VoxelShape} and then queried with the simulation's own copy of vanilla's algorithms.
 * <p>
 * Why: the server's {@code VoxelShape.collide}, {@code CollisionUtil} and {@code BlockCollisions} are patched by
 * Paper/Moonrise and differ from the vanilla client (report 07 §1.6). Only the data (coordinates, cells) is shared
 * with the patched classes; every algorithm that reads it here is a port of the vanilla 26.3 source.
 * <p>
 * A shape is used either as is (entity boxes, the world border) or "moved" by a block position. Vanilla moves block
 * shapes with {@code VoxelShape.move} (VoxelShape.java:87-97), which wraps every coordinate list in an
 * {@code OffsetDoubleList} ({@code base + offset}) and turns the shape into an {@code ArrayVoxelShape}, whose
 * {@code findIndex} is the binary search, never {@code CubeVoxelShape}'s floor formula. The {@code moved} flag passed to
 * every query keeps both semantics exact, down to the sign of a zero ({@code -0.0 + 0.0}).
 */
public final class SimShape {

    /** {@code Shapes.empty()}. */
    public static final SimShape EMPTY = new SimShape(new double[] {0.0}, new double[] {0.0}, new double[] {0.0}, new boolean[0],
        new int[] {0, 0, 0}, new int[] {0, 0, 0}, false, false);

    private static final Field DISCRETE;

    static {
        try {
            // protected in vanilla, public in Paper (Moonrise); read once per distinct shape, never on the hot path
            DISCRETE = VoxelShape.class.getDeclaredField("shape");
            DISCRETE.setAccessible(true);
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    final double[] xs;
    final double[] ys;
    final double[] zs;
    final int sizeX;
    final int sizeY;
    final int sizeZ;
    private final boolean[] full;
    private final int[] firstFull;
    private final int[] lastFull;
    /** {@code CubeVoxelShape}: unmoved coordinates are {@code i / size} and findIndex is the floor formula. */
    final boolean cube;
    /** Identity with {@code Shapes.block()}, which {@code BlockCollisions} tests with {@code ==}. */
    final boolean block;
    /** {@code DiscreteVoxelShape.isEmpty()}: some axis has {@code firstFull >= lastFull}. */
    final boolean empty;

    SimShape(final double[] xs, final double[] ys, final double[] zs, final boolean[] full, final int[] firstFull, final int[] lastFull,
             final boolean cube, final boolean block) {
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.sizeX = xs.length - 1;
        this.sizeY = ys.length - 1;
        this.sizeZ = zs.length - 1;
        this.full = full;
        this.firstFull = firstFull;
        this.lastFull = lastFull;
        this.cube = cube;
        this.block = block;
        this.empty = firstFull[0] >= lastFull[0] || firstFull[1] >= lastFull[1] || firstFull[2] >= lastFull[2];
    }

    /** Copies a vanilla or patched shape's data. Allocates; callers cache by shape identity ({@link ShapeCache}). */
    public static SimShape of(final VoxelShape shape) {
        if (shape == Shapes.empty()) {
            return EMPTY;
        }
        final DiscreteVoxelShape discrete;
        try {
            discrete = (DiscreteVoxelShape) DISCRETE.get(shape);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        final int nx = discrete.getXSize();
        final int ny = discrete.getYSize();
        final int nz = discrete.getZSize();
        final boolean cube = shape instanceof CubeVoxelShape;
        final double[] xs = coords(shape, Direction.Axis.X, nx, cube);
        final double[] ys = coords(shape, Direction.Axis.Y, ny, cube);
        final double[] zs = coords(shape, Direction.Axis.Z, nz, cube);
        final boolean[] full = new boolean[nx * ny * nz];
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    full[(x * ny + y) * nz + z] = discrete.isFull(x, y, z);
                }
            }
        }
        final int[] first = {discrete.firstFull(Direction.Axis.X), discrete.firstFull(Direction.Axis.Y), discrete.firstFull(Direction.Axis.Z)};
        final int[] last = {discrete.lastFull(Direction.Axis.X), discrete.lastFull(Direction.Axis.Y), discrete.lastFull(Direction.Axis.Z)};
        return new SimShape(xs, ys, zs, full, first, last, cube, shape == Shapes.block());
    }

    private static double[] coords(final VoxelShape shape, final Direction.Axis axis, final int size, final boolean cube) {
        final double[] out = new double[size + 1];
        if (cube) {
            // CubePointRange.getDouble (CubePointRange.java:15), not the server's Lithium multiply
            for (int i = 0; i <= size; i++) {
                out[i] = (double) i / size;
            }
            return out;
        }
        final DoubleList list = shape.getCoords(axis);
        if (list.size() != size + 1) {
            throw new IllegalStateException("coordinate list of " + axis + " has " + list.size() + " entries for " + size + " cells");
        }
        for (int i = 0; i <= size; i++) {
            out[i] = list.getDouble(i);
        }
        return out;
    }

    /**
     * {@code Shapes.create(minX, minY, minZ, maxX, maxY, maxZ)} (Shapes.java:56-88): the shape vanilla gives an entity's
     * box, as a collider ({@code EntityGetter.getEntityCollisions}) or as the probe of {@code BlockCollisions}.
     */
    public static SimShape create(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        if (!(maxX - minX < 1.0E-7) && !(maxY - minY < 1.0E-7) && !(maxZ - minZ < 1.0E-7)) {
            final int xBits = findBits(minX, maxX);
            final int yBits = findBits(minY, maxY);
            final int zBits = findBits(minZ, maxZ);
            if (xBits < 0 || yBits < 0 || zBits < 0) {
                // ArrayVoxelShape over BLOCK's 1x1x1 discrete shape
                return new SimShape(new double[] {minX, maxX}, new double[] {minY, maxY}, new double[] {minZ, maxZ}, new boolean[] {true},
                    new int[] {0, 0, 0}, new int[] {1, 1, 1}, false, false);
            } else if (xBits == 0 && yBits == 0 && zBits == 0) {
                return BLOCK;
            } else {
                final int xSize = 1 << xBits;
                final int ySize = 1 << yBits;
                final int zSize = 1 << zBits;
                final int x0 = (int) Math.round(minX * xSize);
                final int y0 = (int) Math.round(minY * ySize);
                final int z0 = (int) Math.round(minZ * zSize);
                final int x1 = (int) Math.round(maxX * xSize);
                final int y1 = (int) Math.round(maxY * ySize);
                final int z1 = (int) Math.round(maxZ * zSize);
                // BitSetDiscreteVoxelShape.withFilledBounds (BitSetDiscreteVoxelShape.java:23-43)
                final boolean[] full = new boolean[xSize * ySize * zSize];
                for (int x = x0; x < x1; x++) {
                    for (int y = y0; y < y1; y++) {
                        for (int z = z0; z < z1; z++) {
                            full[(x * ySize + y) * zSize + z] = true;
                        }
                    }
                }
                return new SimShape(unit(xSize), unit(ySize), unit(zSize), full, new int[] {x0, y0, z0}, new int[] {x1, y1, z1}, true, false);
            }
        }
        return EMPTY;
    }

    /**
     * Whether {@code Shapes.create} gives this box as an {@code ArrayVoxelShape} over one full cell (Shapes.java:56-88):
     * not degenerate, and some axis not on the 1/8 grid of [0, 1]. Any box away from the origin is, so entity colliders
     * nearly always are.
     */
    static boolean isArrayBox(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        return !(maxX - minX < 1.0E-7) && !(maxY - minY < 1.0E-7) && !(maxZ - minZ < 1.0E-7)
            && (findBits(minX, maxX) < 0 || findBits(minY, maxY) < 0 || findBits(minZ, maxZ) < 0);
    }

    /** An array box shape to fill with {@link #setArrayBox}: the same data {@link #create} builds for such a box. */
    static SimShape arrayBox() {
        return new SimShape(new double[2], new double[2], new double[2], new boolean[] {true}, new int[] {0, 0, 0}, new int[] {1, 1, 1}, false, false);
    }

    /** Refills an {@link #arrayBox} shape (only those): its coordinate lists are the box's bounds. */
    void setArrayBox(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        this.xs[0] = minX;
        this.xs[1] = maxX;
        this.ys[0] = minY;
        this.ys[1] = maxY;
        this.zs[0] = minZ;
        this.zs[1] = maxZ;
    }

    /** {@code Shapes.block()}: a CubeVoxelShape of one full cell. */
    static final SimShape BLOCK = new SimShape(new double[] {0.0, 1.0}, new double[] {0.0, 1.0}, new double[] {0.0, 1.0}, new boolean[] {true},
        new int[] {0, 0, 0}, new int[] {1, 1, 1}, true, true);

    private static double[] unit(final int size) {
        final double[] out = new double[size + 1];
        for (int i = 0; i <= size; i++) {
            out[i] = (double) i / size;
        }
        return out;
    }

    /** Shapes.findBits (Shapes.java:90-108). */
    static int findBits(final double min, final double max) {
        if (!(min < -1.0E-7) && !(max > 1.0000001)) {
            for (int bits = 0; bits <= 3; bits++) {
                final int intervals = 1 << bits;
                final double shMin = min * intervals;
                final double shMax = max * intervals;
                final boolean foundMin = Math.abs(shMin - Math.round(shMin)) < 1.0E-7 * intervals;
                final boolean foundMax = Math.abs(shMax - Math.round(shMax)) < 1.0E-7 * intervals;
                if (foundMin && foundMax) {
                    return bits;
                }
            }
        }
        return -1;
    }

    /**
     * The world border's collision shape, {@code Shapes.join(INFINITY, box(floor(minX), -inf, floor(minZ), ceil(maxX),
     * +inf, ceil(maxZ)), ONLY_FIRST)} (WorldBorder.java:447-459, 581-592), as the optimized 3x1x3 ring vanilla builds.
     */
    static SimShape border(final double minX, final double minZ, final double maxX, final double maxZ) {
        final boolean[] full = new boolean[9];
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                full[x * 3 + z] = x != 1 || z != 1;
            }
        }
        return new SimShape(new double[] {Double.NEGATIVE_INFINITY, Math.floor(minX), Math.ceil(maxX), Double.POSITIVE_INFINITY},
            new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
            new double[] {Double.NEGATIVE_INFINITY, Math.floor(minZ), Math.ceil(maxZ), Double.POSITIVE_INFINITY},
            full, new int[] {0, 0, 0}, new int[] {3, 1, 3}, false, false);
    }

    int size(final int axis) {
        return axis == 0 ? this.sizeX : axis == 1 ? this.sizeY : this.sizeZ;
    }

    double[] coords(final int axis) {
        return axis == 0 ? this.xs : axis == 1 ? this.ys : this.zs;
    }

    /** {@code DiscreteVoxelShape.isFullWide(x, y, z)} (DiscreteVoxelShape.java:64-70). */
    boolean isFullWide(final int x, final int y, final int z) {
        if (x < 0 || y < 0 || z < 0) {
            return false;
        }
        return x < this.sizeX && y < this.sizeY && z < this.sizeZ && this.full[(x * this.sizeY + y) * this.sizeZ + z];
    }

    /** {@code VoxelShape.get(axis, i)}: the coordinate, plus the block offset when moved (OffsetDoubleList.getDouble). */
    double get(final int axis, final int i, final boolean moved, final double offset) {
        final double base = this.coords(axis)[i];
        return moved ? base + offset : base;
    }

    /**
     * {@code VoxelShape.findIndex(axis, coord)}: the binary search of VoxelShape.java:149-151 (also for every moved
     * shape), or CubeVoxelShape's {@code floor(clamp(coord * size, -1, size))} (CubeVoxelShape.java:17-21).
     */
    int findIndex(final int axis, final double coord, final boolean moved, final double offset) {
        final int size = this.size(axis);
        if (this.cube && !moved) {
            final double scaled = coord * size;
            final double clamped = scaled < -1.0 ? -1.0 : Math.min(scaled, (double) size);
            return (int) Math.floor(clamped);
        }
        // Mth.binarySearch(0, size + 1, i -> coord < get(axis, i)) - 1 (Mth.java binarySearch), verbatim
        final double[] c = this.coords(axis);
        int from = 0;
        int len = size + 1;
        while (len > 0) {
            final int half = len / 2;
            final int middle = from + half;
            final double at = moved ? c[middle] + offset : c[middle];
            if (coord < at) {
                len = half;
            } else {
                from = middle + 1;
                len -= half + 1;
            }
        }
        return from - 1;
    }

    /** {@code VoxelShape.min(axis)} (VoxelShape.java:28-31). */
    double min(final int axis, final boolean moved, final double offset) {
        final int i = this.firstFull[axis];
        return i >= this.size(axis) ? Double.POSITIVE_INFINITY : this.get(axis, i, moved, offset);
    }

    /** {@code VoxelShape.max(axis)} (VoxelShape.java:33-36). */
    double max(final int axis, final boolean moved, final double offset) {
        final int i = this.lastFull[axis];
        return i <= 0 ? Double.NEGATIVE_INFINITY : this.get(axis, i, moved, offset);
    }

    /**
     * {@code VoxelShape.collide(axis, moving, distance)} → {@code collideX} (VoxelShape.java:233-300), for this shape
     * moved by {@code (ox, oy, oz)} when {@code moved}. Axis 0/1/2 = X/Y/Z; the (a, b, c) cycle is
     * {@code AxisCycle.between(axis, X).inverse()}: X→(X,Y,Z), Y→(Y,Z,X), Z→(Z,X,Y).
     */
    double collide(final int axis, final SimBox moving, double distance, final boolean moved, final double ox, final double oy, final double oz) {
        if (this.empty) {
            return distance;
        }
        if (Math.abs(distance) < 1.0E-7) {
            return 0.0;
        }
        final int bAxis = axis == 0 ? 1 : axis == 1 ? 2 : 0;
        final int cAxis = axis == 0 ? 2 : axis == 1 ? 0 : 1;
        final double offA = axis == 0 ? ox : axis == 1 ? oy : oz;
        final double offB = bAxis == 0 ? ox : bAxis == 1 ? oy : oz;
        final double offC = cAxis == 0 ? ox : cAxis == 1 ? oy : oz;
        final double maxA = max(moving, axis);
        final double minA = min(moving, axis);
        final int aMin = this.findIndex(axis, minA + 1.0E-7, moved, offA);
        final int aMax = this.findIndex(axis, maxA - 1.0E-7, moved, offA);
        final int bMin = Math.max(0, this.findIndex(bAxis, min(moving, bAxis) + 1.0E-7, moved, offB));
        final int bMax = Math.min(this.size(bAxis), this.findIndex(bAxis, max(moving, bAxis) - 1.0E-7, moved, offB) + 1);
        final int cMin = Math.max(0, this.findIndex(cAxis, min(moving, cAxis) + 1.0E-7, moved, offC));
        final int cMax = Math.min(this.size(cAxis), this.findIndex(cAxis, max(moving, cAxis) - 1.0E-7, moved, offC) + 1);
        final int aSize = this.size(axis);
        if (distance > 0.0) {
            for (int a = aMax + 1; a < aSize; a++) {
                for (int b = bMin; b < bMax; b++) {
                    for (int c = cMin; c < cMax; c++) {
                        if (this.isFullCycled(axis, a, b, c)) {
                            final double newDistance = this.get(axis, a, moved, offA) - maxA;
                            if (newDistance >= -1.0E-7) {
                                distance = Math.min(distance, newDistance);
                            }
                            return distance;
                        }
                    }
                }
            }
        } else if (distance < 0.0) {
            for (int a = aMin - 1; a >= 0; a--) {
                for (int b = bMin; b < bMax; b++) {
                    for (int c = cMin; c < cMax; c++) {
                        if (this.isFullCycled(axis, a, b, c)) {
                            final double newDistance = this.get(axis, a + 1, moved, offA) - minA;
                            if (newDistance <= 1.0E-7) {
                                distance = Math.max(distance, newDistance);
                            }
                            return distance;
                        }
                    }
                }
            }
        }
        return distance;
    }

    /** {@code isFullWide(inverse, a, b, c)}: X→(a,b,c), Y→(c,a,b), Z→(b,c,a) as (x,y,z). */
    private boolean isFullCycled(final int axis, final int a, final int b, final int c) {
        return axis == 0 ? this.isFullWide(a, b, c) : axis == 1 ? this.isFullWide(c, a, b) : this.isFullWide(b, c, a);
    }

    static double min(final SimBox box, final int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    static double max(final SimBox box, final int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }
}
