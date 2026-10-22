package gg.hcfrevival.revival.auth.sim;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/**
 * An in-memory {@link SimWorld} for tests (the offline rig's RigWorld without the vanilla oracle): a sparse block map,
 * every chunk loaded unless {@link #unload}ed (then void air, like the client's EmptyLevelChunk), a far border, optional
 * solid entities (in the order added, which stands for the client's).
 */
final class SimTestWorld implements SimWorld {

    private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap sectionFluids = new Long2IntOpenHashMap();
    /** Solid entities as the client holds them, in its order: box, position, rule ({@code SimWorld.ENTITY_*}). */
    final List<Solid> solids = new ArrayList<>();
    private final LongOpenHashSet unloaded = new LongOpenHashSet();
    double borderMinX = -2.9999984E7;
    double borderMinZ = -2.9999984E7;
    double borderMaxX = 2.9999984E7;
    double borderMaxZ = 2.9999984E7;

    SimTestWorld set(final int x, final int y, final int z, final BlockState state) {
        final long key = BlockPos.asLong(x, y, z);
        final BlockState old = this.blocks.put(key, state);
        if (old != null && !old.getFluidState().isEmpty()) {
            this.sectionFluids.addTo(SectionPos.asLong(x >> 4, y >> 4, z >> 4), -1);
        }
        if (!state.getFluidState().isEmpty()) {
            this.sectionFluids.addTo(SectionPos.asLong(x >> 4, y >> 4, z >> 4), 1);
        }
        return this;
    }

    SimTestWorld floor(final int y, final BlockState state) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                this.set(x, y, z, state);
            }
        }
        return this;
    }

    record Solid(AABB box, double x, double y, double z, int rule) {
    }

    /** A solid entity at the bottom centre of its box (a boat, a shulker on the floor). */
    SimTestWorld solid(final AABB box) {
        return this.solid(box, (box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5, SimWorld.ENTITY_SOLID);
    }

    SimTestWorld solid(final AABB box, final double x, final double y, final double z, final int rule) {
        this.solids.add(new Solid(box, x, y, z, rule));
        return this;
    }

    /** The client does not have this chunk (yet). */
    SimTestWorld unload(final int chunkX, final int chunkZ) {
        this.unloaded.add(ChunkPos.pack(chunkX, chunkZ));
        return this;
    }

    SimTestWorld load(final int chunkX, final int chunkZ) {
        this.unloaded.remove(ChunkPos.pack(chunkX, chunkZ));
        return this;
    }

    @Override
    public BlockState blockState(final int x, final int y, final int z) {
        if (!this.isChunkLoaded(x >> 4, z >> 4)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        final BlockState s = this.blocks.get(BlockPos.asLong(x, y, z));
        return s == null ? Blocks.AIR.defaultBlockState() : s;
    }

    @Override
    public FluidState fluidState(final int x, final int y, final int z) {
        return this.blockState(x, y, z).getFluidState();
    }

    @Override
    public boolean isChunkLoaded(final int chunkX, final int chunkZ) {
        return !this.unloaded.contains(ChunkPos.pack(chunkX, chunkZ));
    }

    @Override
    public boolean sectionHasFluid(final int chunkX, final int sectionY, final int chunkZ) {
        return this.sectionFluids.get(SectionPos.asLong(chunkX, sectionY, chunkZ)) > 0;
    }

    @Override
    public int minY() {
        return -64;
    }

    @Override
    public int height() {
        return 384;
    }

    @Override
    public double borderMinX() {
        return this.borderMinX;
    }

    @Override
    public double borderMinZ() {
        return this.borderMinZ;
    }

    @Override
    public double borderMaxX() {
        return this.borderMaxX;
    }

    @Override
    public double borderMaxZ() {
        return this.borderMaxZ;
    }

    @Override
    public void collectEntityColliders(final SimBox area, final SimBoxList out) {
        // indexed: an iterator allocates unless escape analysis removes it, which allocation tests must not count on
        for (int i = 0; i < this.solids.size(); i++) {
            final Solid e = this.solids.get(i);
            final AABB b = e.box();
            if (b.intersects(area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ)) {
                out.add(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, e.x(), e.y(), e.z(), e.rule());
            }
        }
    }

    @Override
    public boolean fastLava() {
        return false;
    }
}
