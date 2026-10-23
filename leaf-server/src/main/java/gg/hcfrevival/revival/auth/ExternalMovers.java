package gg.hcfrevival.revival.auth;

import gg.hcfrevival.revival.auth.view.WorldView;
import gg.hcfrevival.revival.combat.ProjectileBlocks;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import java.util.ArrayList;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * Movers outside the player's own tick in the client's level (design §15.6, auth-sim §6):
 * <ul>
 *   <li>{@link #PUSH}: a piston's moving blocks or a shulker box's lid, from the {@code BlockEvent} the client applied,
 *       for {@code external-movers.block-window-ticks}, while the truth's box (0.51 deep along the push, one tick of
 *       piston travel) is in what it moves: the piston's lane (its head and the 12 blocks a push can move, one high
 *       more for what stands on them), the box's own block and the block its lid opens into; and a shulker's lid opening
 *       into the player's box ({@code Shulker.onPeekAmountChange} moves it with {@code Entity.move(SHULKER)} in the
 *       shulker's client tick, which the client's copy of it reports, {@link #lidPush}). The truth is still simulated; of
 *       a claim within {@code external-movers.band} of it only the part along the push is adopted, with its sign, at most
 *       0.51 a tick and the push's travel in all (a piston 1.02, a lid 0.6), and up to {@code external-movers.drift-cap}
 *       per drift window (review E2: an exemption handed the tick to vanilla; classic audit #4: any 0.6 claim near any
 *       piston or shulker box was adopted, a self-made claim-wall climb);</li>
 *   <li>{@link #SOLID}: a boat, shulker or happy ghast touching the player's box whose client copy the port does not
 *       collide with exactly (a placeholder until a logged spawn, a copy that went inexact, a vehicle this client drove,
 *       a passenger), and a launching potent sulfur geyser under a player in its water: the port's
 *       {@code EXTERNAL_MOVER} exemption, vanilla taking the claim only within {@code external-movers.max-move} of where
 *       the server holds the player.
 *       Exact copies are the port's own colliders ({@code WorldView.collectEntityColliders}), no exemption.</li>
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
    private int lidTick = Integer.MIN_VALUE / 2;

    // The last EVENTS block events (a ring): when, what they move (an AABB), along which axis and sign, and how far in all
    private static final int EVENTS = 4;
    /** One tick of piston travel ({@code PistonMovingBlockEntity}: progress 0.5 a tick, and a push of up to 0.51). */
    static final double TICK_TRAVEL = 0.51;
    private final int[] eventTick = new int[EVENTS];
    private final double[] eventBox = new double[EVENTS * 6];
    private final int[] eventAxis = new int[EVENTS];
    private final int[] eventSign = new int[EVENTS];
    private final double[] eventRoom = new double[EVENTS];
    private int nextEvent;
    // The event near() matched, -1 for a shulker's lid (any axis), -2 for none
    private int pushing = -2;

    ExternalMovers() {
        this.reset();
    }

    /** {@link #near} results. */
    static final int NONE = 0;
    static final int PUSH = 1;
    static final int SOLID = 2;

    /**
     * A piston or shulker box {@code BlockEvent} the client applied before tick {@code tick}: {@code b0}, {@code b1} as
     * {@code PistonBaseBlock.triggerEvent} reads them (0 extend, 1 retract, 2 drop; the facing's 3D data value), and
     * {@code state}, the box as the client's world holds it (its facing); {@code range}, {@code external-movers.block-range}:
     * how far along its facing a piston's lane reaches (13 at most: the head and the 12 blocks it can push).
     */
    void blockEvent(final int tick, final BlockPos pos, final net.minecraft.world.level.block.Block block, final int b0, final int b1,
                    final net.minecraft.world.level.block.state.BlockState state, final double range) {
        final net.minecraft.core.Direction facing;
        final double travel;
        final int sign;
        int near = 1;
        int far;
        if (block instanceof net.minecraft.world.level.block.piston.PistonBaseBlock) {
            if (b0 != 0 && b0 != 1) {
                return; // a drop moves nothing
            }
            facing = net.minecraft.core.Direction.from3DDataValue(b1);
            travel = 1.02; // PistonMovingBlockEntity.moveCollidedEntities: each tick's progress plus 0.01, two ticks
            sign = b0 == 0 ? 1 : -1; // a sticky retract pulls back toward the base
            far = (int) Math.max(1.0, Math.min(net.minecraft.world.level.block.piston.PistonStructureResolver.MAX_PUSH_DEPTH + 1, Math.floor(range)));
        } else if (state.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            facing = state.getValue(net.minecraft.world.level.block.ShulkerBoxBlock.FACING);
            travel = 0.6; // ShulkerBoxBlockEntity.moveCollidedEntities: the lid's 0.5 plus 0.01 in each of its 10 ticks
            sign = 1;
            near = 0;
            far = 1;
        } else {
            return;
        }
        final int e = this.nextEvent;
        this.nextEvent = (e + 1) % EVENTS;
        this.eventTick[e] = tick;
        final int ax = facing.getAxis().ordinal();
        this.eventAxis[e] = ax;
        this.eventSign[e] = sign * facing.getAxisDirection().getStep();
        this.eventRoom[e] = travel + 1.0E-6;
        final BlockPos a = pos.relative(facing, near);
        final BlockPos b = pos.relative(facing, far);
        final int o = e * 6;
        this.eventBox[o] = Math.min(a.getX(), b.getX());
        this.eventBox[o + 1] = Math.min(a.getY(), b.getY());
        this.eventBox[o + 2] = Math.min(a.getZ(), b.getZ());
        this.eventBox[o + 3] = Math.max(a.getX(), b.getX()) + 1.0;
        this.eventBox[o + 4] = Math.max(a.getY(), b.getY()) + (facing.getAxis() == net.minecraft.core.Direction.Axis.Y ? 1.0 : 2.0); // what stands on it
        this.eventBox[o + 5] = Math.max(a.getZ(), b.getZ()) + 1.0;
    }

    /** A shulker's lid opened into the player's box in the client's entity ticks of tick {@code tick}. */
    void lidPush(final int tick) {
        this.lidTick = tick;
    }

    void reset() {
        this.lidTick = Integer.MIN_VALUE / 2;
        java.util.Arrays.fill(this.eventTick, Integer.MIN_VALUE / 2);
        this.pushing = -2;
    }

    /**
     * Of {@code (ox, oy, oz)}, a claim's offset from the truth in a {@link #PUSH} tick, the part the push explains into
     * {@code out}: the component along the matched push with its sign, at most {@link #TICK_TRAVEL} and what is left of
     * the push's travel; zero on the other axes. A shulker's lid ({@link #lidPush}) explains the whole offset. Returns
     * whether there is any.
     */
    boolean adoptable(final double ox, final double oy, final double oz, final double[] out) {
        out[0] = 0.0;
        out[1] = 0.0;
        out[2] = 0.0;
        if (this.pushing == -1) {
            out[0] = ox;
            out[1] = oy;
            out[2] = oz;
            return ox != 0.0 || oy != 0.0 || oz != 0.0;
        }
        if (this.pushing < 0) {
            return false;
        }
        final int ax = this.eventAxis[this.pushing];
        final double along = ax == 0 ? ox : ax == 1 ? oy : oz;
        if (along * this.eventSign[this.pushing] <= 0.0 || Math.abs(along) > Math.min(TICK_TRAVEL + 1.0E-6, this.eventRoom[this.pushing])) {
            return false;
        }
        out[ax] = along;
        return true;
    }

    /** The {@link #adoptable} part was adopted: it uses up the push's travel. */
    void adopted(final double[] part) {
        if (this.pushing >= 0) {
            this.eventRoom[this.pushing] -= Math.abs(part[this.eventAxis[this.pushing]]);
        }
    }

    /**
     * What may move the player at {@code (x, y, z)} with box {@code box} in client tick {@code tick}: {@link #NONE},
     * {@link #PUSH} or {@link #SOLID}.
     */
    int near(final ServerPlayer player, final int tick, final double x, final double y, final double z, final AABB box, final WorldView world,
             final AuthoritativeSettings settings) {
        final double reach = settings.externalMoverEntityRange();
        if (reach > 0.0 && world.solids.anyUntrusted()) {
            if (world.solids.untrustedWithin(box.minX - reach, box.minY - reach, box.minZ - reach, box.maxX + reach, box.maxY + reach, box.maxZ + reach)) {
                return SOLID;
            }
            // A placeholder's box is unknown: the server's is the best guess
            player.level().getEntities(ANY, box.inflate(reach), SOLID_ENTITY, this.found, 8);
            for (int i = 0, n = this.found.size(); i < n; i++) {
                final int id = this.found.get(i).getId();
                if (!world.solids.trusted(id) && !world.solids.ridesSelf(id)) {
                    this.found.clear();
                    return SOLID;
                }
            }
            this.found.clear();
        }
        if (geyserBelow(box, world, settings.externalMoverGeyserDepth())) {
            return SOLID;
        }
        this.pushing = -2;
        if (tick - this.lidTick >= 0 && tick - this.lidTick <= settings.externalMoverBlockWindowTicks()) {
            this.pushing = -1;
            return PUSH;
        }
        return this.lane(tick, box, settings.externalMoverBlockWindowTicks()) ? PUSH : NONE;
    }

    /** Whether {@code box} is in what a recent block event moves (the most recent such event becomes the push). */
    boolean lane(final int tick, final AABB box, final int windowTicks) {
        int best = -1;
        for (int e = 0; e < EVENTS; e++) {
            if (tick - this.eventTick[e] > windowTicks || tick < this.eventTick[e] || this.eventRoom[e] <= 0.0) {
                continue;
            }
            final int o = e * 6;
            final int ax = this.eventAxis[e];
            final double reach = TICK_TRAVEL;
            if (box.maxX + (ax == 0 ? reach : 0.0) > this.eventBox[o] && box.minX - (ax == 0 ? reach : 0.0) < this.eventBox[o + 3]
                && box.maxY + (ax == 1 ? reach : 0.0) > this.eventBox[o + 1] && box.minY - (ax == 1 ? reach : 0.0) < this.eventBox[o + 4]
                && box.maxZ + (ax == 2 ? reach : 0.0) > this.eventBox[o + 2] && box.minZ - (ax == 2 ? reach : 0.0) < this.eventBox[o + 5]
                && (best < 0 || this.eventTick[e] > this.eventTick[best])) {
                best = e;
            }
        }
        if (best >= 0) {
            this.pushing = best;
            return true;
        }
        return false;
    }

    /**
     * Whether the feet are in a launching geyser's water column. {@code PotentSulfurBlockEntity.LAUNCH_ENTITY_TICKER} runs
     * only for an ERUPTING or CONTINUOUS sulfur ({@code PotentSulfurBlock.getTicker}) and lifts what is in its column only
     * while that column is unbroken water reaching air within {@code ALLOWED_WATER_BLOCKS_ABOVE} blocks
     * ({@code findNoxiousGasSourceBlock}). Review 3 #6: any pool over any sulfur up to {@code depth} below was exempt, and
     * potent sulfur has a recipe: a base could bury one under its pool for a client-trusted zone.
     */
    static boolean geyserBelow(final AABB box, final ProjectileBlocks world, final int depth) {
        final int feetY = Mth.floor(box.minY);
        final int bottom = feetY - Math.min(depth, PotentSulfurBlock.ALLOWED_WATER_BLOCKS_ABOVE);
        for (int bx = Mth.floor(box.minX); bx <= Mth.floor(box.maxX); bx++) {
            for (int bz = Mth.floor(box.minZ); bz <= Mth.floor(box.maxZ); bz++) {
                int y = feetY;
                while (y >= bottom && geyserWater(world, bx, y, bz)) {
                    y--;
                }
                if (y == feetY || y < bottom) {
                    continue; // no water at the feet, or deeper than a launch reaches
                }
                final BlockState sulfur = world.blockState(bx, y, bz);
                final PotentSulfurState state = sulfur.is(Blocks.POTENT_SULFUR) ? sulfur.getValue(PotentSulfurBlock.STATE) : PotentSulfurState.DRY;
                if (state != PotentSulfurState.ERUPTING && state != PotentSulfurState.CONTINUOUS) {
                    continue;
                }
                final int last = y + PotentSulfurBlock.ALLOWED_WATER_BLOCKS_ABOVE + 1;
                int top = feetY + 1;
                while (top <= last && geyserWater(world, bx, top, bz)) {
                    top++;
                }
                if (top <= last && geyserPassable(world, world.blockState(bx, top, bz), bx, top, bz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // findNoxiousGasSourceBlock: a column block is a water source that is water or has no collision
    private static boolean geyserWater(final ProjectileBlocks world, final int x, final int y, final int z) {
        final BlockState state = world.blockState(x, y, z);
        return state.getFluidState().isSourceOfType(Fluids.WATER) && geyserPassable(world, state, x, y, z);
    }

    // PotentSulfurBlockEntity.isGeyserPassableBlock
    private static boolean geyserPassable(final ProjectileBlocks world, final BlockState state, final int x, final int y, final int z) {
        return state.isAir() || state.is(Blocks.WATER) || world.collisionShape(state, new BlockPos(x, y, z)).isEmpty();
    }
}
