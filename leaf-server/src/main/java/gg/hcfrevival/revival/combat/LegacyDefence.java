package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.LegacyCombatSettings;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * 1.7.10 armor and Protection for legacy hits, called from {@code LivingEntity.handleEntityDamage} and
 * {@code hurtServer} (patch 1005). They run inside CraftBukkit's damage modifier functions, so
 * {@code EntityDamageEvent} keeps a correct ARMOR and MAGIC breakdown and {@code setDamage} keeps working.
 * Resistance (20% per level) and absorption already match 1.7.10 and stay 26.x.
 */
public final class LegacyDefence {

    /** Returned by {@link #rollProtection} when the hit uses 26.x enchantment protection. */
    public static final int MODERN = -1;

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private LegacyDefence() {
    }

    /**
     * The hit's Protection EPF, rolled once per hit so the damage event can recalculate its modifiers without
     * rerolling, or {@link #MODERN} when the hit isn't legacy or legacy enchant protection is off.
     */
    public static int rollProtection(final LivingEntity victim, final @Nullable LegacyCombatSettings settings) {
        if (settings == null || !settings.legacyEnchantProtection() || !(victim.level() instanceof final ServerLevel level)) {
            return MODERN;
        }
        final Holder<Enchantment> protection = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        int points = 0;
        for (final EquipmentSlot slot : ARMOR) {
            points += protectionPoints(EnchantmentHelper.getItemEnchantmentLevel(protection, victim.getItemBySlot(slot)));
        }
        return epf(points, settings.randomizeProtection(), victim.getRandom());
    }

    /**
     * {@code lastHurt} for the damage window: 1.7.10 compared raw damage, before blocking took its share. Called on both
     * landing branches (fresh and overflow), so a legacy hit landing here also uses up the attacker's Neue riposte.
     */
    public static float lastHurt(final DamageSource source, final float damage, final EntityDamageEvent event) {
        if (LegacyAttack.settings(source) == null) {
            return damage;
        }
        NeueCombat.landed(source);
        return damage - (float) event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING);
    }

    /** 1.7.10 EntityLivingBase.applyArmorCalculations: 4% per armor point, no toughness. */
    public static float afterArmor(final float damage, final int armor) {
        return Math.max(0.0F, damage * (25 - armor) / 25.0F);
    }

    /** 1.7.10 EnchantmentProtection.calcModifierDamage for Protection against a melee hit: floor((6 + level²) / 3 × 0.75). */
    static int protectionPoints(final int level) {
        return level > 0 ? (int) Math.floor((6 + level * level) / 3.0F * 0.75F) : 0;
    }

    /**
     * 1.7.10 EnchantmentHelper.getEnchantmentModifierDamage: the armor's points, capped at 25, then rolled between
     * half and all of them; applyPotionDamageCalculations caps the result at 20. Unrandomized, the hit uses all of them.
     */
    static int epf(int points, final boolean randomize, final RandomSource random) {
        points = Math.min(points, 25);
        final int rolled = randomize ? ((points + 1) >> 1) + random.nextInt((points >> 1) + 1) : points;
        return Math.min(rolled, 20);
    }

    /** 1.7.10 applyPotionDamageCalculations: 4% per EPF point. */
    public static float afterProtection(final float damage, final int epf) {
        return epf > 0 ? damage * (25 - epf) / 25.0F : damage;
    }
}
