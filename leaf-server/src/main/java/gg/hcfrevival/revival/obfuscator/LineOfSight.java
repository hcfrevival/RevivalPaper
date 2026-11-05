package gg.hcfrevival.revival.obfuscator;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Voxel line of sight through full opaque cubes. Everything that isn't a full opaque cube (glass, leaves, slabs,
 * stairs, doors, water, barriers, unloaded chunks) is see-through, so every approximation errs on showing a player.
 * Pure functions over an {@link Occluder}, so the walk is unit tested on a grid.
 */
public final class LineOfSight {

    /** Which blocks stop sight. */
    public interface Occluder {
        boolean opaque(int x, int y, int z);
    }

    /** The level implementation: {@code BlockState.isSolidRender()}, with the last chunk cached across a ray. */
    public static final class LevelOccluder implements Occluder {
        private @Nullable ServerLevel level;
        private @Nullable LevelChunk chunk;
        private int chunkX = Integer.MIN_VALUE;
        private int chunkZ = Integer.MIN_VALUE;
        /** Block lookups since the last reset; the per-tick budget reads it. */
        public int lookups;

        public LevelOccluder reset(final ServerLevel level) {
            this.level = level;
            this.chunk = null;
            this.chunkX = Integer.MIN_VALUE;
            this.lookups = 0;
            return this;
        }

        @Override
        public boolean opaque(final int x, final int y, final int z) {
            this.lookups++;
            final int chunkX = x >> 4;
            final int chunkZ = z >> 4;
            if (chunkX != this.chunkX || chunkZ != this.chunkZ) {
                this.chunk = this.level == null ? null : this.level.getChunkIfLoaded(chunkX, chunkZ);
                this.chunkX = chunkX;
                this.chunkZ = chunkZ;
            }
            final LevelChunk chunk = this.chunk;
            return chunk != null && chunk.getBlockStateFinal(x, y, z).isSolidRender();
        }
    }

    private static final double CAMERA_STEP = 0.5;

    private LineOfSight() {
    }

    /**
     * Walks the blocks on the segment from (x0, y0, z0) to (x1, y1, z1), start and end blocks included (a point
     * inside stone neither sees nor is seen). Returns -1 when every block is see-through, otherwise the index of the
     * first opaque block, 0 being the start block. Amanatides-Woo DDA, ending by block count so it stops exactly at
     * the end block.
     */
    public static int blockedAt(final Occluder occluder, final double x0, final double y0, final double z0, final double x1, final double y1, final double z1) {
        int x = Mth.floor(x0);
        int y = Mth.floor(y0);
        int z = Mth.floor(z0);
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double dz = z1 - z0;
        final int sx = dx > 0.0 ? 1 : dx < 0.0 ? -1 : 0;
        final int sy = dy > 0.0 ? 1 : dy < 0.0 ? -1 : 0;
        final int sz = dz > 0.0 ? 1 : dz < 0.0 ? -1 : 0;
        final double tDeltaX = sx == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        final double tDeltaY = sy == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        final double tDeltaZ = sz == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        double tMaxX = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? x + 1 - x0 : x0 - x) * tDeltaX;
        double tMaxY = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? y + 1 - y0 : y0 - y) * tDeltaY;
        double tMaxZ = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? z + 1 - z0 : z0 - z) * tDeltaZ;
        final int steps = Math.abs(Mth.floor(x1) - x) + Math.abs(Mth.floor(y1) - y) + Math.abs(Mth.floor(z1) - z);
        for (int i = 0; ; i++) {
            if (occluder.opaque(x, y, z)) {
                return i;
            }
            if (i == steps) {
                return -1;
            }
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += sx;
                    tMaxX += tDeltaX;
                } else {
                    z += sz;
                    tMaxZ += tDeltaZ;
                }
            } else if (tMaxY < tMaxZ) {
                y += sy;
                tMaxY += tDeltaY;
            } else {
                z += sz;
                tMaxZ += tDeltaZ;
            }
        }
    }

    /**
     * Whether any of a box's nine sample points (its centre, then its corners) has a clear line to any viewer end
     * point. {@code ends} holds {@code count} xyz triples; the first {@code eyeCount} are eyes and also get the corner
     * rays, the rest (third-person camera points) only the centre ray. Rays start at the target, so an enclosed target
     * ends them within a few blocks; when the centre ray dies nearer the viewer, the viewer is the enclosed one and the
     * remaining rays start at the viewer instead.
     */
    public static boolean visible(final Occluder occluder, final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
                                  final double[] ends, final int count, final int eyeCount) {
        final double cx = (minX + maxX) * 0.5;
        final double cy = (minY + maxY) * 0.5;
        final double cz = (minZ + maxZ) * 0.5;
        boolean fromViewer = false;
        for (int e = 0; e < count; e++) {
            final double vx = ends[e * 3];
            final double vy = ends[e * 3 + 1];
            final double vz = ends[e * 3 + 2];
            final int blocked = blockedAt(occluder, cx, cy, cz, vx, vy, vz);
            if (blocked < 0) {
                return true;
            }
            if (e == 0) {
                final int length = Math.abs(Mth.floor(vx) - Mth.floor(cx)) + Math.abs(Mth.floor(vy) - Mth.floor(cy)) + Math.abs(Mth.floor(vz) - Mth.floor(cz));
                fromViewer = blocked * 2 > length;
            }
        }
        for (int e = 0; e < eyeCount && e < count; e++) {
            final double vx = ends[e * 3];
            final double vy = ends[e * 3 + 1];
            final double vz = ends[e * 3 + 2];
            for (int corner = 0; corner < 8; corner++) {
                final double px = (corner & 1) == 0 ? minX : maxX;
                final double py = (corner & 2) == 0 ? minY : maxY;
                final double pz = (corner & 4) == 0 ? minZ : maxZ;
                final int blocked = fromViewer ? blockedAt(occluder, vx, vy, vz, px, py, pz) : blockedAt(occluder, px, py, pz, vx, vy, vz);
                if (blocked < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds the third-person camera points, behind and then in front of the eye along the look direction, each pulled
     * back to the last see-through block (vanilla clips the camera against block shapes, so this reaches at least as
     * far). A camera that can't move a block from the eye is skipped. Returns the new count.
     */
    public static int addCameraEnds(final Occluder occluder, final double eyeX, final double eyeY, final double eyeZ, final float yRot, final float xRot, final double distance,
                                    final double[] ends, int count) {
        // Entity.calculateViewVector
        final float pitch = xRot * Mth.DEG_TO_RAD;
        final float yaw = -yRot * Mth.DEG_TO_RAD;
        final double lookX = Mth.sin(yaw) * Mth.cos(pitch);
        final double lookY = -Mth.sin(pitch);
        final double lookZ = Mth.cos(yaw) * Mth.cos(pitch);
        for (int side = -1; side <= 1; side += 2) {
            double reach = 0.0;
            for (double t = CAMERA_STEP; t <= distance; t += CAMERA_STEP) {
                if (occluder.opaque(Mth.floor(eyeX + side * lookX * t), Mth.floor(eyeY + side * lookY * t), Mth.floor(eyeZ + side * lookZ * t))) {
                    break;
                }
                reach = t;
            }
            if (reach >= 1.0) {
                ends[count * 3] = eyeX + side * lookX * reach;
                ends[count * 3 + 1] = eyeY + side * lookY * reach;
                ends[count * 3 + 2] = eyeZ + side * lookZ * reach;
                count++;
            }
        }
        return count;
    }
}
