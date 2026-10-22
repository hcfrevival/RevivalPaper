package gg.hcfrevival.revival.auth.sim;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The world as one player's vanilla 26.3 client holds it: the narrow set of raw queries the movement port reads
 * (report 01 §11.3). Everything here is data, never server behaviour: the simulation evaluates collision shapes,
 * fluid heights and flows itself, with its own copies of the vanilla algorithms.
 * <p>
 * The live implementation ({@code auth.view.WorldView}) overlays the server world with the client's lag: block changes
 * not yet acked, the client's own block predictions, the chunks it holds and the border as it lerps. The offline rig
 * implements it over an in-memory map.
 * <p>
 * Contract for implementers, all of it the client's view:
 * <ul>
 *   <li>{@link #blockState} and {@link #fluidState} return air / empty for unloaded chunks and outside
 *       {@link #minY()}..{@code minY + height - 1} (ClientLevel answers with its empty chunk and {@code VOID_AIR});</li>
 *   <li>{@link #isChunkLoaded} is {@code ClientChunkCache.getChunk(x, z, FULL, false) != null}: unloaded chunks add no
 *       block colliders ({@code BlockCollisions}), and decide {@code travelInAir}'s {@code -0.1} branch;</li>
 *   <li>{@link #sectionHasFluid} is {@code LevelChunkSection.hasFluid()} of a loaded chunk's section, false outside the
 *       section range ({@code EntityFluidInteraction.hasFluidAndLoaded});</li>
 *   <li>the border bounds are {@code WorldBorder.getMinX()} etc. at partial tick 0, as the client has lerped them;</li>
 *   <li>{@link #collectEntityColliders} adds the box of every entity the player collides with
 *       ({@code Entity.canBeCollidedWith}: boats, live shulkers, happy ghasts by their client rules) intersecting the
 *       area, at the boxes the client draws them (interpolated), in the client's entity iteration order.</li>
 * </ul>
 */
public interface SimWorld {

    BlockState blockState(int x, int y, int z);

    FluidState fluidState(int x, int y, int z);

    boolean isChunkLoaded(int chunkX, int chunkZ);

    boolean sectionHasFluid(int chunkX, int sectionY, int chunkZ);

    /** {@code LevelHeightAccessor.getMinY()}. */
    int minY();

    /** {@code LevelHeightAccessor.getHeight()}. */
    int height();

    double borderMinX();

    double borderMinZ();

    double borderMaxX();

    double borderMaxZ();

    /**
     * Adds every solid entity box intersecting {@code area} to {@code out} (EntityGetter.getEntityCollisions,
     * EntityGetter.java:54-72). {@code area} is already inflated by 1e-7 as vanilla queries it; {@code out} is cleared by
     * the caller.
     */
    void collectEntityColliders(SimBox area, SimBoxList out);

    /** The dimension's {@code FAST_LAVA} environment attribute (lava current 0.007 instead of 0.0023333333333333335). */
    boolean fastLava();
}
