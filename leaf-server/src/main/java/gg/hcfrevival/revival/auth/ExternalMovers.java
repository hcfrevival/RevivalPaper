package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.auth.view.WorldView;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import java.util.ArrayList;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * Movers outside the player's own tick in the client's level (design §15.6, auth-sim §6):
 * <ul>
 *   <li>{@link #PUSH}: a piston's moving blocks or a shulker box's lid, from the {@code BlockEvent} the client applied,
 *       for {@code external-movers.block-window-ticks} within {@code external-movers.block-range} of it. The truth is
 *       still simulated; a claim within {@code external-movers.band} of it is adopted, up to
 *       {@code external-movers.drift-cap} per drift window (review E2: an exemption handed the tick to vanilla);</li>
 *   <li>{@link #SOLID}: solid entities the port has no collider for (boats, shulkers, adult happy ghasts) touching the
 *       player's box, and a potent sulfur geyser under a player in water: the port's {@code EXTERNAL_MOVER} exemption,
 *       vanilla taking the claim only within {@code external-movers.max-move} of where the server holds the player.</li>
 * </ul>
 * A fishing rod pull is no mover: the client adds {@code (owner - hook) * 0.1} to its own velocity in the drain
 * ({@code FishingHook.pullEntity}), which the truth does too.
 * Entity pushes need no flag: the enforce precondition (the client's own team collision rule is {@code NEVER}) turns
 * every push of the local player off on the client ({@code EntitySelector.pushableBy}). Main thread, per player.
 */
final class ExternalMovers {

    private static final EntityTypeTest<Entity, Entity> ANY = EntityTypeTest.forClass(Entity.class);
    private static final Predicate<Entity> SOLID_ENTITY = entity -> entity instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat
        || entity instanceof net.minecraft.world.entity.monster.Shulker
        || entity instanceof final net.minecraft.world.entity.animal.happyghast.HappyGhast ghast && !ghast.isBaby();

    private final ArrayList<Entity> found = new ArrayList<>(1);
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int blockTick = Integer.MIN_VALUE / 2;
    private double blockX;
    private double blockY;
    private double blockZ;

    /** {@link #near} results. */
    static final int NONE = 0;
    static final int PUSH = 1;
    static final int SOLID = 2;

    /** A piston or shulker box {@code BlockEvent} the client applied before tick {@code tick}. */
    void blockEvent(final int tick, final BlockPos pos) {
        this.blockTick = tick;
        this.blockX = pos.getX() + 0.5;
        this.blockY = pos.getY() + 0.5;
        this.blockZ = pos.getZ() + 0.5;
    }

    void reset() {
        this.blockTick = Integer.MIN_VALUE / 2;
    }

    /**
     * What may move the player at {@code (x, y, z)} with box {@code box} in client tick {@code tick}: {@link #NONE},
     * {@link #PUSH} or {@link #SOLID}.
     */
    int near(final ServerPlayer player, final int tick, final double x, final double y, final double z, final AABB box, final WorldView world,
             final AuthoritativeSettings settings) {
        final double entityRange = settings.externalMoverEntityRange();
        if (entityRange > 0.0) {
            player.level().getEntities(ANY, box.inflate(entityRange), SOLID_ENTITY, this.found, 1);
            if (!this.found.isEmpty()) {
                this.found.clear();
                return SOLID;
            }
        }
        if (this.geyserBelow(box, world, settings.externalMoverGeyserDepth())) {
            return SOLID;
        }
        if (tick - this.blockTick <= settings.externalMoverBlockWindowTicks()) {
            final double dx = x - this.blockX;
            final double dy = y - this.blockY;
            final double dz = z - this.blockZ;
            final double range = settings.externalMoverBlockRange();
            if (dx * dx + dy * dy + dz * dz <= range * range) {
                return PUSH;
            }
        }
        return NONE;
    }

    // LAUNCH_ENTITY_TICKER launches entities in the water column above potent sulfur (PotentSulfurBlockEntity)
    private boolean geyserBelow(final AABB box, final WorldView world, final int depth) {
        if (depth <= 0) {
            return false;
        }
        final int feetY = Mth.floor(box.minY);
        final int minX = Mth.floor(box.minX);
        final int maxX = Mth.floor(box.maxX);
        final int minZ = Mth.floor(box.minZ);
        final int maxZ = Mth.floor(box.maxZ);
        if (!world.fluidState(Mth.floor((box.minX + box.maxX) * 0.5), feetY, Mth.floor((box.minZ + box.maxZ) * 0.5)).is(FluidTags.WATER)) {
            return false;
        }
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = feetY; by >= feetY - depth; by--) {
                    if (world.blockState(bx, by, bz).is(Blocks.POTENT_SULFUR)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
