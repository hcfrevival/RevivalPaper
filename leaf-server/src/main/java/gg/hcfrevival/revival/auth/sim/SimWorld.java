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
 *   <li>{@link #blockState} and {@link #fluidState} return air / empty for chunks the client does not have and outside
 *       {@link #minY()}..{@code minY + height - 1}: ClientLevel reads a missing chunk through
 *       {@code ClientChunkCache.getChunk(x, z, FULL, true)}, its {@code EmptyLevelChunk} ({@code VOID_AIR}, no fluid,
 *       ClientChunkCache.java:68-77, EmptyLevelChunk.java:26-39), and movement reads void air like air;</li>
 *   <li>{@link #isChunkLoaded} is {@code ClientChunkCache.getChunk(x, z, FULL, false) != null}: such chunks add no
 *       block colliders ({@code BlockCollisions}) and no fluid ({@code EntityFluidInteraction.hasFluidAndLoaded}). Nothing
 *       else on the client's movement path asks: {@code Level.hasChunk} is always true on a {@code ClientLevel}
 *       (ClientLevel.java:519-522), so {@code travelInAir} keeps normal gravity over a missing chunk;</li>
 *   <li>{@link #sectionHasFluid} is {@code LevelChunkSection.hasFluid()} of a loaded chunk's section, false outside the
 *       section range ({@code EntityFluidInteraction.hasFluidAndLoaded});</li>
 *   <li>the border bounds are {@code WorldBorder.getMinX()} etc. at partial tick 0, as the client has lerped them;</li>
 *   <li>{@link #collectEntityColliders} adds the entities the player may collide with, see there.</li>
 * </ul>
 */
public interface SimWorld {

    /**
     * {@link #collectEntityColliders}: an entity whose {@code canBeCollidedWith(player)} is true whatever the player:
     * any boat ({@code AbstractBoat}: boats, chest boats, rafts), a shulker that is alive on the client (health over 0),
     * an adult happy ghast that is alive and on its still timeout ({@code STAYS_STILL} set).
     */
    int ENTITY_SOLID = 0;

    /**
     * {@link #collectEntityColliders}: an adult happy ghast, alive, not on its still timeout. On a client it collides
     * only with a player standing at or above its top ({@code player.getY() >= box.maxY}, HappyGhast.canBeCollidedWith,
     * HappyGhast.java:578-589), tested by the port at each query with the player's position then.
     */
    int ENTITY_SOLID_FROM_ABOVE = 1;

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
     * Adds the entities the player may collide with ({@code EntityGetter.getEntityCollisions}, EntityGetter.java:54-72)
     * to {@code out}, with {@link SimBoxList#add(double, double, double, double, double, double, double, double, double, int)}:
     * each one's bounding box and position as the client holds them when its player ticks, and its rule
     * ({@link #ENTITY_SOLID} or {@link #ENTITY_SOLID_FROM_ABOVE}). {@code out} is cleared by the caller.
     * <ul>
     *   <li>Which: boats, shulkers and happy ghasts by the rules of the two constants; never a baby ghast, a dead shulker
     *       or ghast, or an entity riding the player (directly or not: {@code isPassengerOfSameVehicle}). No other entity
     *       type collides (only these override {@code canBeCollidedWith}).</li>
     *   <li>Where: at least every one whose box intersects {@code area}; more is fine. The port filters each query
     *       itself (box intersection, ClientLevel's section lookup, the ghast's rule) and asks again for a larger area
     *       only when a query leaves the last one, so one call usually serves a whole tick.</li>
     *   <li>Order: the client's ({@code EntitySectionStorage.getEntities}): by entity section (section x ascending, then
     *       the section key's z and y bits as unsigned numbers, ascending), then by when the entity entered that section
     *       on the client. It only shows at the 1e-7 scale of {@code Shapes.collide} and in auto-jump's first obstacle.</li>
     * </ul>
     * The port asks afresh at every {@code tick}, {@code collide} and {@code velocityAfterMove}; within one only for an
     * area the last answer did not cover, and the answers must agree (nothing moves the entities during the player's
     * tick). docs/revival/auth-sim-requests.md §2 says which box at which point of the client tick, and how the client
     * moves each type (boats interpolate over 3 steps, shulkers snap and open their lids per tick).
     */
    void collectEntityColliders(SimBox area, SimBoxList out);

    /** The dimension's {@code FAST_LAVA} environment attribute (lava current 0.007 instead of 0.0023333333333333335). */
    boolean fastLava();
}
