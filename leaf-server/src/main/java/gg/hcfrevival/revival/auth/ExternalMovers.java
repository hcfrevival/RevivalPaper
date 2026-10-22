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
 * Movers outside the player's own tick in the client's level (design §15.6, auth-sim §6): a tick near one is the port's
 * {@code EXTERNAL_MOVER} exemption (the claim through vanilla's checks, a re-seed after) instead of a correction.
 * <ul>
 *   <li>a piston's moving blocks or a shulker box's lid, from the {@code BlockEvent} the client applied, for
 *       {@code external-movers.block-window-ticks} within {@code external-movers.block-range} of it;</li>
 *   <li>solid entities the port has no collider for (boats, shulkers, adult happy ghasts) within
 *       {@code external-movers.entity-range} of the player's box;</li>
 *   <li>a potent sulfur geyser under a player in water, down to {@code external-movers.geyser-depth} blocks;</li>
 *   <li>a fishing rod pull ({@code EntityEvent 31}), on the tick the client applies it.</li>
 * </ul>
 * Entity pushes need no flag: the enforce precondition (the client's own team collision rule is {@code NEVER}) turns
 * every push of the local player off on the client ({@code EntitySelector.pushableBy}). Main thread, per player.
 */
final class ExternalMovers {

    private static final EntityTypeTest<Entity, Entity> ANY = EntityTypeTest.forClass(Entity.class);
    private static final Predicate<Entity> SOLID = entity -> entity instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat
        || entity instanceof net.minecraft.world.entity.monster.Shulker
        || entity instanceof final net.minecraft.world.entity.animal.happyghast.HappyGhast ghast && !ghast.isBaby();

    private final ArrayList<Entity> found = new ArrayList<>(1);
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int blockTick = Integer.MIN_VALUE / 2;
    private double blockX;
    private double blockY;
    private double blockZ;
    private int rodTick = Integer.MIN_VALUE / 2;

    /** A piston or shulker box {@code BlockEvent} the client applied before tick {@code tick}. */
    void blockEvent(final int tick, final BlockPos pos) {
        this.blockTick = tick;
        this.blockX = pos.getX() + 0.5;
        this.blockY = pos.getY() + 0.5;
        this.blockZ = pos.getZ() + 0.5;
    }

    /** The client applied a rod pull on itself before tick {@code tick}. */
    void rodPull(final int tick) {
        this.rodTick = tick;
    }

    void reset() {
        this.blockTick = Integer.MIN_VALUE / 2;
        this.rodTick = Integer.MIN_VALUE / 2;
    }

    /** Whether something may move the player at {@code (x, y, z)} with box {@code box} in client tick {@code tick}. */
    boolean near(final ServerPlayer player, final int tick, final double x, final double y, final double z, final AABB box, final WorldView world,
                 final AuthoritativeSettings settings) {
        if (tick == this.rodTick) {
            return true;
        }
        if (tick - this.blockTick <= settings.externalMoverBlockWindowTicks()) {
            final double dx = x - this.blockX;
            final double dy = y - this.blockY;
            final double dz = z - this.blockZ;
            final double range = settings.externalMoverBlockRange();
            if (dx * dx + dy * dy + dz * dz <= range * range) {
                return true;
            }
        }
        final double entityRange = settings.externalMoverEntityRange();
        if (entityRange > 0.0) {
            player.level().getEntities(ANY, box.inflate(entityRange), SOLID, this.found, 1);
            if (!this.found.isEmpty()) {
                this.found.clear();
                return true;
            }
        }
        return this.geyserBelow(box, world, settings.externalMoverGeyserDepth());
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
