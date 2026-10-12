package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.LegacyCombatSettings;
import gg.hcfrevival.revival.config.RevivalSettings;
import org.junit.jupiter.api.Test;

/**
 * Golden values from the 1.7.10 server jar: ItemSword sets 4 + ToolMaterial damage, EntityPlayer's attack damage
 * base is 1, Potion registers Strength as 3.0/operation 2 and Weakness as 2.0/operation 0, PotionAttackDamage turns
 * those into 1.3 × level and -0.5 × level, and EnchantmentDamage gives Sharpness 1.25 × level.
 */
@RevivalTest
class LegacyDamageTest {

    private static final LegacyCombatSettings DEFAULTS = RevivalSettings.DEFAULTS.legacyCombat();
    private static final float DIAMOND = 8.0F;
    private static final float IRON = 7.0F;
    private static final float WOOD = 5.0F;

    @Test
    void weaponDamageWithoutEffects() {
        assertEquals(8.0F, LegacyDamage.attackDamage(DIAMOND, LegacyDamage.NONE, LegacyDamage.NONE, DEFAULTS));
    }

    @Test
    void strengthMultipliesByOnePointThreePerLevel() {
        assertEquals(18.4F, LegacyDamage.attackDamage(DIAMOND, 0, LegacyDamage.NONE, DEFAULTS), 1.0E-5F); // 8 × 2.3
        assertEquals(28.8F, LegacyDamage.attackDamage(DIAMOND, 1, LegacyDamage.NONE, DEFAULTS), 1.0E-5F); // 8 × 3.6
    }

    @Test
    void weaknessSubtractsHalfPerLevel() {
        assertEquals(6.5F, LegacyDamage.attackDamage(IRON, LegacyDamage.NONE, 0, DEFAULTS));
        assertEquals(6.0F, LegacyDamage.attackDamage(IRON, LegacyDamage.NONE, 1, DEFAULTS));
    }

    @Test
    void weaknessAppliesBeforeStrength() {
        assertEquals(10.35F, LegacyDamage.attackDamage(WOOD, 0, 0, DEFAULTS), 1.0E-5F); // (5 - 0.5) × 2.3
    }

    @Test
    void damageNeverGoesBelowZero() {
        assertEquals(0.0F, LegacyDamage.attackDamage(WOOD, LegacyDamage.NONE, 20, DEFAULTS));
    }

    @Test
    void sharpnessIsOnePointTwoFivePerLevel() {
        assertEquals(0.0F, LegacyDamage.sharpness(0, DEFAULTS));
        assertEquals(1.25F, LegacyDamage.sharpness(1, DEFAULTS));
        assertEquals(6.25F, LegacyDamage.sharpness(5, DEFAULTS));
    }

    @Test
    void defaultsMatchOneSevenTen() {
        assertEquals(1.5, DEFAULTS.criticalMultiplier()); // EntityPlayer: f *= 1.5F on a falling hit
        assertEquals(false, DEFAULTS.sweepAttack());
    }
}
