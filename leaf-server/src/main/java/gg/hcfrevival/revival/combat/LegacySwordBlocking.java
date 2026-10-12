package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.RevivalSettings;
import gg.hcfrevival.revival.config.SwordBlockingSettings;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.UseEffects;

/**
 * 1.7.10 sword blocking (patch 1009). The server never gives swords a blocking component, so Paper's shield code
 * (skipping the knockback packet, failing the hit, pulling the blocker, axe disables) never runs. Instead legacy
 * weapons are usable on the server, a blocked hit takes {@code (damage + 1) × 0.5} through Paper's BLOCKING damage
 * modifier, and clients get a copy of the item with a blocking component so they show and predict the block.
 */
public final class LegacySwordBlocking {

    /** What clients see on a legacy weapon: blocks at once, can't be disabled, no item damage and no shield sounds. */
    static final BlocksAttacks DECORATION = new BlocksAttacks(0.0F, 0.0F, List.of(), BlocksAttacks.ItemDamageFunction.DEFAULT, Optional.empty(), Optional.empty(), Optional.empty());

    private LegacySwordBlocking() {
    }

    /** Whether the stack blocks: sword blocking is on and it is a legacy weapon (by the default world's list, since this is global). */
    public static boolean isBlockingWeapon(final ItemStack stack) {
        final RevivalSettings settings = Revival.settings();
        return settings.swordBlocking().enabled() && LegacyAttack.isLegacyWeapon(stack, settings.legacyCombat());
    }

    /** Right click with a legacy weapon: whether to start blocking. Re-arms the sprint knock when configured. */
    public static boolean startBlocking(final Player player, final ItemStack stack) {
        if (!isBlockingWeapon(stack)) {
            return false;
        }
        if (Revival.settings().swordBlocking().rearmSprintOnBlock() && player instanceof final ServerPlayer serverPlayer) {
            serverPlayer.revivalSprintLedger.rearm(serverPlayer.onGround());
        }
        return true;
    }

    /** Whether the entity is blocking with a legacy weapon right now; 1.7.10 had no block delay. */
    public static boolean isSwordBlocking(final LivingEntity entity) {
        return entity.isUsingItem() && isBlockingWeapon(entity.getUseItem());
    }

    /**
     * How much of a hit a sword block takes off, for Paper's BLOCKING modifier, or 0 when the victim isn't
     * sword-blocking. 1.7.10 EntityPlayer.damageEntity: {@code if (!unblockable && isBlocking() && damage > 0)
     * damage = (1 + damage) × 0.5}, before armor, from any direction.
     */
    public static float blocked(final LivingEntity victim, final DamageSource source, final float damage) {
        if (damage <= 0.0F || !isSwordBlocking(victim)) {
            return 0.0F;
        }
        final SwordBlockingSettings settings = Revival.settings().swordBlocking();
        if (settings.ignoreArmorBypassingDamage() && source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            return 0.0F;
        }
        return blockedAmount(damage, settings);
    }

    static float blockedAmount(final float damage, final SwordBlockingSettings settings) {
        return damage - (float) ((damage + settings.damageOffset()) * settings.damageFactor());
    }

    /** The component patch clients get for a stack: a legacy weapon gains the blocking decoration. */
    public static DataComponentPatch toClient(final ItemStack stack, final DataComponentPatch patch) {
        final SwordBlockingSettings settings = Revival.settings().swordBlocking();
        if (!settings.clientDecoration() || stack.has(DataComponents.BLOCKS_ATTACKS) || !isBlockingWeapon(stack)) {
            return patch;
        }
        final DataComponentPatch.Builder builder = DataComponentPatch.builder();
        builder.copy(patch);
        builder.set(DataComponents.BLOCKS_ATTACKS, DECORATION);
        final UseEffects useEffects = useEffects(settings);
        if (useEffects != null) {
            builder.set(DataComponents.USE_EFFECTS, useEffects);
        }
        return builder.build();
    }

    /** A patch a client sent back (creative mode): the decoration added by {@link #toClient} comes off again. */
    public static DataComponentPatch fromClient(final DataComponentPatch patch) {
        if (patch.isEmpty() || !DECORATION.equals(patch.get(DataComponentMap.EMPTY, DataComponents.BLOCKS_ATTACKS))) {
            return patch;
        }
        final UseEffects useEffects = useEffects(Revival.settings().swordBlocking());
        final UseEffects sent = patch.get(DataComponentMap.EMPTY, DataComponents.USE_EFFECTS);
        return patch.forget(type -> type == DataComponents.BLOCKS_ATTACKS || type == DataComponents.USE_EFFECTS && useEffects != null && useEffects.equals(sent));
    }

    // Only sent when it differs from 26.x's default (0.2, the same as 1.7's blocking slowdown)
    private static @org.jspecify.annotations.Nullable UseEffects useEffects(final SwordBlockingSettings settings) {
        final UseEffects effects = new UseEffects(UseEffects.DEFAULT.canSprint(), UseEffects.DEFAULT.interactVibrations(), (float) settings.useSpeedMultiplier());
        return effects.equals(UseEffects.DEFAULT) ? null : effects;
    }
}
