package gg.hcfrevival.revival.auth.sim;

import java.util.Arrays;

/**
 * A growable list of entity colliders as flat doubles (box, position, rule), so {@link SimWorld#collectEntityColliders}
 * can answer without allocating.
 */
public final class SimBoxList {

    private static final int STRIDE = 10;

    private double[] data = new double[STRIDE * 8];
    private int size;

    public void clear() {
        this.size = 0;
    }

    public int size() {
        return this.size;
    }

    /**
     * An entity with box {@code min..max} at position {@code x, y, z} ({@code Entity.position()}, which picks its entity
     * section) and rule {@link SimWorld#ENTITY_SOLID} or {@link SimWorld#ENTITY_SOLID_FROM_ABOVE}.
     */
    public void add(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ, final double x,
                    final double y, final double z, final int rule) {
        if ((this.size + 1) * STRIDE > this.data.length) {
            this.data = Arrays.copyOf(this.data, this.data.length * 2);
        }
        final int at = this.size * STRIDE;
        this.data[at] = minX;
        this.data[at + 1] = minY;
        this.data[at + 2] = minZ;
        this.data[at + 3] = maxX;
        this.data[at + 4] = maxY;
        this.data[at + 5] = maxZ;
        this.data[at + 6] = x;
        this.data[at + 7] = y;
        this.data[at + 8] = z;
        this.data[at + 9] = rule;
        this.size++;
    }

    /**
     * A box that always collides ({@link SimWorld#ENTITY_SOLID}), positioned at its bottom centre: the position of a boat
     * or a ghast, whose box is built around it. Prefer the full form, which takes the position the client holds.
     */
    public void add(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        this.add(minX, minY, minZ, maxX, maxY, maxZ, (minX + maxX) * 0.5, minY, (minZ + maxZ) * 0.5, SimWorld.ENTITY_SOLID);
    }

    /** Component {@code k} (0..5 = minX, minY, minZ, maxX, maxY, maxZ; 6..8 = the position x, y, z) of entry {@code i}. */
    public double get(final int i, final int k) {
        return this.data[i * STRIDE + k];
    }

    /** The rule of entry {@code i}. */
    public int rule(final int i) {
        return (int) this.data[i * STRIDE + 9];
    }
}
