package gg.hcfrevival.revival.auth.sim;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@link SimShape}s by {@link VoxelShape} identity. Block collision shapes are almost always shared constants (one
 * per state, or per context case for scaffolding and powder snow), so a small direct-mapped table keeps the lookup
 * allocation-free; the few blocks that build a new shape per call (bamboo's random offset) just evict each other.
 * Not thread-safe: one per {@link MovementSim}.
 */
final class ShapeCache {

    private static final int SIZE = 2048; // power of two

    private final VoxelShape[] keys = new VoxelShape[SIZE];
    private final SimShape[] values = new SimShape[SIZE];

    SimShape get(final VoxelShape shape) {
        if (shape == Shapes.empty()) {
            return SimShape.EMPTY;
        }
        if (shape == Shapes.block()) {
            return SimShape.BLOCK;
        }
        final int slot = mix(System.identityHashCode(shape)) & (SIZE - 1);
        if (this.keys[slot] == shape) {
            return this.values[slot];
        }
        final SimShape data = SimShape.of(shape);
        this.keys[slot] = shape;
        this.values[slot] = data;
        return data;
    }

    private static int mix(final int h) {
        final int x = h * 0x9E3779B9;
        return x ^ (x >>> 16);
    }
}
