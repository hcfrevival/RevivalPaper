package gg.hcfrevival.revival.auth.sim;

/**
 * A mutable axis-aligned box with vanilla {@code net.minecraft.world.phys.AABB} arithmetic, so the simulation can move
 * and grow boxes without allocating. Every setter normalizes like the AABB constructor (26.3 AABB.java:22-29:
 * {@code Math.min}/{@code Math.max} per axis), and every operation keeps AABB's operand order, so results are bit-equal
 * to the immutable vanilla calls they replace.
 */
public final class SimBox {

    public double minX;
    public double minY;
    public double minZ;
    public double maxX;
    public double maxY;
    public double maxZ;

    /** {@code new AABB(x1, y1, z1, x2, y2, z2)}. */
    public SimBox set(final double x1, final double y1, final double z1, final double x2, final double y2, final double z2) {
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
        return this;
    }

    public SimBox set(final SimBox other) {
        this.minX = other.minX;
        this.minY = other.minY;
        this.minZ = other.minZ;
        this.maxX = other.maxX;
        this.maxY = other.maxY;
        this.maxZ = other.maxZ;
        return this;
    }

    /** {@code AABB.move(xa, ya, za)} (AABB.java:221). */
    public SimBox move(final double xa, final double ya, final double za) {
        return this.set(this.minX + xa, this.minY + ya, this.minZ + za, this.maxX + xa, this.maxY + ya, this.maxZ + za);
    }

    /** {@code AABB.expandTowards(xa, ya, za)} (AABB.java:159-185). */
    public SimBox expandTowards(final double xa, final double ya, final double za) {
        double x1 = this.minX;
        double y1 = this.minY;
        double z1 = this.minZ;
        double x2 = this.maxX;
        double y2 = this.maxY;
        double z2 = this.maxZ;
        if (xa < 0.0) {
            x1 += xa;
        } else if (xa > 0.0) {
            x2 += xa;
        }
        if (ya < 0.0) {
            y1 += ya;
        } else if (ya > 0.0) {
            y2 += ya;
        }
        if (za < 0.0) {
            z1 += za;
        } else if (za > 0.0) {
            z2 += za;
        }
        return this.set(x1, y1, z1, x2, y2, z2);
    }

    /** {@code AABB.inflate(x, y, z)} (AABB.java:187-195); {@code deflate(a)} is {@code inflate(-a)}. */
    public SimBox inflate(final double xAdd, final double yAdd, final double zAdd) {
        return this.set(this.minX - xAdd, this.minY - yAdd, this.minZ - zAdd, this.maxX + xAdd, this.maxY + yAdd, this.maxZ + zAdd);
    }

    /** {@code AABB.deflate(amount)} = {@code inflate(-amount)} (AABB.java:288). */
    public SimBox deflate(final double amount) {
        return this.inflate(-amount, -amount, -amount);
    }

    /** {@code AABB.intersects(...)} (AABB.java:243). */
    public boolean intersects(final double x1, final double y1, final double z1, final double x2, final double y2, final double z2) {
        return this.minX < x2 && this.maxX > x1 && this.minY < y2 && this.maxY > y1 && this.minZ < z2 && this.maxZ > z1;
    }

    /** {@code AABB.getSize()} (AABB.java:265-270). */
    public double size() {
        final double xs = this.maxX - this.minX;
        final double ys = this.maxY - this.minY;
        final double zs = this.maxZ - this.minZ;
        return (xs + ys + zs) / 3.0;
    }

    @Override
    public String toString() {
        return "SimBox[" + this.minX + ", " + this.minY + ", " + this.minZ + "] -> [" + this.maxX + ", " + this.maxY + ", " + this.maxZ + "]";
    }
}
