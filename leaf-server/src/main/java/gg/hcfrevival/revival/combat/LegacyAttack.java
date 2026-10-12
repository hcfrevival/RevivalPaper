package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.LegacyCombatSettings;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.apache.commons.lang3.mutable.MutableFloat;
import org.jspecify.annotations.Nullable;

/**
 * Called from {@code Player.attack} (patch 1001). A player melee hit with a legacy weapon uses 1.7.10 damage:
 * the weapon's 1.7.10 damage with 1.7.10 Strength and Weakness, 1.7.10 Sharpness, the 1.7.10 crit rule, and
 * no sweep. Knockback is {@link LegacyKnockback}; sprint, hit cadence and damage immunity stay 26.x.
 */
public final class LegacyAttack {

    private LegacyAttack() {
    }

    /** The settings for this hit when it is a legacy hit, or null when it stays 26.x. */
    public static @Nullable LegacyCombatSettings settings(final Player attacker, final ItemStack weapon) {
        if (weapon.isEmpty() || attacker.isAutoSpinAttack()) {
            return null;
        }
        final LegacyCombatSettings settings = Revival.settings().legacyCombat(attacker.level().getWorld().getName());
        final Identifier id = BuiltInRegistries.ITEM.getKey(weapon.getItem());
        return settings.weaponDamage().containsKey(id) && isWeapon(weapon, id, settings) ? settings : null;
    }

    /** The weapon's 1.7.10 damage with Strength and Weakness applied. Other attack-damage modifiers are ignored, as in 1.7.10. */
    public static float attackDamage(final Player attacker, final ItemStack weapon, final LegacyCombatSettings settings) {
        return LegacyDamage.attackDamage(
            settings.weaponDamage().get(BuiltInRegistries.ITEM.getKey(weapon.getItem())),
            amplifier(attacker.getEffect(MobEffects.STRENGTH)),
            amplifier(attacker.getEffect(MobEffects.WEAKNESS)),
            settings
        );
    }

    /**
     * Enchantment damage for this hit: the 26.x bonus with Sharpness swapped for its 1.7.10 value. Smite and Bane
     * of Arthropods already match 1.7.10 (2.5 per level). As in 1.7.10, only living targets take it.
     */
    public static float enchantmentDamage(final Player attacker, final Entity target, final ItemStack weapon, final DamageSource source, final LegacyCombatSettings settings) {
        if (!(target instanceof LivingEntity) || !(attacker.level() instanceof final ServerLevel level)) {
            return 0.0F;
        }
        final float modern = EnchantmentHelper.modifyDamage(level, weapon, target, source, 0.0F);
        final Holder<Enchantment> sharpness = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        final int sharpnessLevel = EnchantmentHelper.getItemEnchantmentLevel(sharpness, weapon);
        if (sharpnessLevel <= 0) {
            return modern;
        }
        final MutableFloat modernSharpness = new MutableFloat(0.0F);
        sharpness.value().modifyDamage(level, sharpnessLevel, weapon, target, source, modernSharpness);
        return modern - modernSharpness.floatValue() + LegacyDamage.sharpness(sharpnessLevel, settings);
    }

    /** The 1.7.10 crit rule (EntityPlayer.attackTargetEntityWithCurrentItem): 26.x's, without the sprint and attack-cooldown conditions. */
    public static boolean canCriticalAttack(final Player attacker, final Entity target) {
        return attacker.fallDistance > 0.0
            && !attacker.onGround()
            && !attacker.onClimbable()
            && !attacker.isInWater()
            && !attacker.isMobilityRestricted()
            && !attacker.isPassenger()
            && target instanceof LivingEntity;
    }

    private static boolean isWeapon(final ItemStack weapon, final Identifier id, final LegacyCombatSettings settings) {
        if (settings.weaponItems().contains(id)) {
            return true;
        }
        final List<TagKey<Item>> tags = settings.weaponTags();
        for (int i = 0, size = tags.size(); i < size; i++) {
            if (weapon.is(tags.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static int amplifier(final @Nullable MobEffectInstance effect) {
        return effect != null ? effect.getAmplifier() : LegacyDamage.NONE;
    }
}
