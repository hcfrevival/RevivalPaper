package gg.hcfrevival.revival.auth.sim;

import java.util.Arrays;

/** A growable list of boxes as flat doubles, so {@link SimWorld#collectEntityColliders} can answer without allocating. */
public final class SimBoxList {

    private double[] data = new double[6 * 8];
    private int size;

    public void clear() {
        this.size = 0;
    }

    public int size() {
        return this.size;
    }

    public void add(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        if ((this.size + 1) * 6 > this.data.length) {
            this.data = Arrays.copyOf(this.data, this.data.length * 2);
        }
        final int at = this.size * 6;
        this.data[at] = minX;
        this.data[at + 1] = minY;
        this.data[at + 2] = minZ;
        this.data[at + 3] = maxX;
        this.data[at + 4] = maxY;
        this.data[at + 5] = maxZ;
        this.size++;
    }

    /** Component {@code k} (0..5 = minX, minY, minZ, maxX, maxY, maxZ) of box {@code i}. */
    public double get(final int i, final int k) {
        return this.data[i * 6 + k];
    }
}
