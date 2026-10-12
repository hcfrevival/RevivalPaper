package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.KnockbackProfile;
import gg.hcfrevival.revival.config.LegacyCombatSettings;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Called from {@code LivingEntity.knockback} and {@code Player.causeExtraKnockback} (patch 1002). A legacy melee
 * hit gets its base and extra knock from {@link KnockbackEngine} in one knockback event; the second, extra-knockback
 * event 26.x sends is skipped. Every other knock stays 26.x.
 */
public final class LegacyKnockback {

    private LegacyKnockback() {
    }

    /** The knockback profile when this damage is a legacy melee hit by attacker, or null. */
    public static @Nullable KnockbackProfile profile(final @Nullable Entity attacker, final DamageSource source) {
        if (!(attacker instanceof final Player player) || source.getDirectEntity() != player || !source.is(DamageTypes.PLAYER_ATTACK)) {
            return null;
        }
        final LegacyCombatSettings settings = LegacyAttack.settings(player, player.getWeaponItem());
        return settings != null ? settings.knockback() : null;
    }

    /** The victim's motion after a legacy hit, or null when the knock stays 26.x. */
    public static @Nullable Vec3 targetMovement(final LivingEntity victim, final DamageSource source, final @Nullable Entity attacker, final Vec3 current, final double xd, final double zd) {
        final KnockbackProfile profile = profile(attacker, source);
        if (profile == null) {
            return null;
        }
        final Player player = (Player) attacker;
        // 1.7.10 checked the sprint flag at hit time; a sprint-start ledger replaces this in the sprint phase
        final double level = knockbackLevel(player, player.getWeaponItem()) + (player.isSprinting() ? 1 : 0);
        return KnockbackEngine.knockback(current, xd, zd, player.getYRot(), level, profile, victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), victim.getRandom());
    }

    /** The attacker's horizontal slowdown after an extra knock: the profile's for a legacy hit, 26.x's 0.6 otherwise. */
    public static double attackerSlowdown(final Player attacker, final DamageSource source) {
        final KnockbackProfile profile = profile(attacker, source);
        return profile != null ? profile.attackerSlowdown() : 0.6;
    }

    private static int knockbackLevel(final Player player, final ItemStack weapon) {
        if (weapon.isEmpty() || !(player.level() instanceof final ServerLevel level)) {
            return 0;
        }
        return EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK), weapon);
    }
}
