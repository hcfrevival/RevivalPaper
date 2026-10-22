package gg.hcfrevival.revival.auth.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The client's {@code CollisionContext.of(player)}: vanilla {@code EntityCollisionContext} semantics
 * (EntityCollisionContext.java:23-104) as a snapshot taken when the query starts, which is what vanilla does
 * ({@code entityBottom = getY()}, {@code descending = isShiftKeyDown()}, {@code heldItem = getMainHandItem()}).
 * <p>
 * Why not the server's class: Gale makes {@code EntityCollisionContext} read the live entity and ignore
 * {@code entityBottom} (report 07 §1.6), which changes scaffolding and powder snow shapes.
 * <p>
 * It is not an {@code EntityCollisionContext}, so blocks that look for the entity behind the context
 * ({@code PowderSnowBlock}, {@code LiquidBlock}) see none; the simulation evaluates powder snow itself
 * ({@link SimCollision}), and liquids are never solid for players ({@code LivingEntity.canStandOnFluid} is false).
 */
final class SimCollisionContext implements CollisionContext {

    boolean descending;
    double entityBottom;
    ItemStack heldItem = ItemStack.EMPTY;

    @Override
    public boolean isDescending() {
        return this.descending;
    }

    /** EntityCollisionContext.isAbove (EntityCollisionContext.java:79-81). */
    @Override
    public boolean isAbove(final VoxelShape shape, final BlockPos pos, final boolean defaultValue) {
        return this.entityBottom > pos.getY() + shape.max(Direction.Axis.Y) - 9.999999747378752E-6;
    }

    /** {@code heldItem.is(item)} (ItemStack.is(Item) is {@code getItem() == item}). */
    @Override
    public boolean isHoldingItem(final Item item) {
        return this.heldItem.getItem() == item;
    }

    @Override
    public boolean alwaysCollideWithFluid() {
        return false;
    }

    /** Players never stand on fluid (LivingEntity.canStandOnFluid returns false). */
    @Override
    public boolean canStandOnFluid(final FluidState fluidStateAbove, final FluidState fluid) {
        return false;
    }

    @Override
    public VoxelShape getCollisionShape(final BlockState state, final CollisionGetter collisionGetter, final BlockPos pos) {
        return state.getCollisionShape(collisionGetter, pos, this);
    }

    @Override
    public boolean isPlacement() {
        return false;
    }
}
