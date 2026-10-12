package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.LegacyCombatSettings;

/** 1.7.10 melee damage formulas, kept free of server types so they can be tested on their own. */
public final class LegacyDamage {

    /** No effect of that type, matching {@code MobEffectInstance} amplifiers, where level I is 0. */
    public static final int NONE = -1;

    private LegacyDamage() {
    }

    /**
     * Attack damage before crits and enchantments. 1.7.10 adds Weakness ({@code -0.5 × level}, operation 0) to the
     * weapon's damage, then multiplies by Strength ({@code 1 + 1.3 × level}, operation 2), and clamps at zero
     * (Potion and PotionAttackDamage, applied by ModifiableAttributeInstance.computeValue).
     */
    public static float attackDamage(final float weaponDamage, final int strengthAmplifier, final int weaknessAmplifier, final LegacyCombatSettings settings) {
        double damage = weaponDamage;
        if (weaknessAmplifier > NONE) {
            damage -= settings.weaknessPerLevel() * (weaknessAmplifier + 1);
        }
        if (strengthAmplifier > NONE) {
            damage *= 1.0 + settings.strengthPerLevel() * (strengthAmplifier + 1);
        }
        return (float) Math.max(0.0, damage);
    }

    /** Sharpness damage: {@code 1.25 × level} in 1.7.10 (EnchantmentDamage.calcModifierLiving). */
    public static float sharpness(final int level, final LegacyCombatSettings settings) {
        return level > 0 ? (float) (settings.sharpnessPerLevel() * level) : 0.0F;
    }
}
