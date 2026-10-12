package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Golden values from the 1.7.10 server jar: EntityLivingBase.applyArmorCalculations ({@code damage × (25 - armor)
 * / 25}), EnchantmentProtection.calcModifierDamage ({@code floor((6 + level²) / 3 × 0.75)} for Protection),
 * EnchantmentHelper.getEnchantmentModifierDamage (cap 25, then {@code (n + 1) >> 1} plus a roll up to {@code n >> 1})
 * and applyPotionDamageCalculations (cap 20, {@code damage × (25 - epf) / 25}).
 */
@RevivalTest
class LegacyDefenceTest {

    @Test
    void armorIsFourPercentPerPoint() {
        assertEquals(1.8F, LegacyDefence.afterArmor(9.0F, 20), 1.0E-6F); // full diamond vs a 9 hit: 80%
        assertEquals(3.6F, LegacyDefence.afterArmor(9.0F, 15), 1.0E-6F); // full iron
        assertEquals(9.0F, LegacyDefence.afterArmor(9.0F, 0));
        assertEquals(0.0F, LegacyDefence.afterArmor(9.0F, 30)); // more armor than 1.7 allowed never heals
    }

    @Test
    void protectionPointsPerPiece() {
        assertEquals(0, LegacyDefence.protectionPoints(0));
        assertEquals(1, LegacyDefence.protectionPoints(1));
        assertEquals(2, LegacyDefence.protectionPoints(2));
        assertEquals(3, LegacyDefence.protectionPoints(3));
        assertEquals(5, LegacyDefence.protectionPoints(4));
    }

    @Test
    void protectionTwoTimesFourRollsBetween16And32Percent() {
        assertEquals(Set.of(4, 5, 6, 7, 8), rolls(4 * LegacyDefence.protectionPoints(2)));
        assertEquals(10.0F * 21 / 25, LegacyDefence.afterProtection(10.0F, 4), 1.0E-6F);
        assertEquals(10.0F * 17 / 25, LegacyDefence.afterProtection(10.0F, 8), 1.0E-6F);
    }

    @Test
    void protectionFourTimesFourRollsBetween40And80Percent() {
        assertEquals(Set.of(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20), rolls(4 * LegacyDefence.protectionPoints(4)));
    }

    @Test
    void pointsCapAt25AndTheRollAt20() {
        final Set<Integer> rolls = rolls(40);
        assertEquals(13, rolls.stream().mapToInt(Integer::intValue).min().orElseThrow()); // (25 + 1) >> 1
        assertEquals(20, rolls.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test
    void unrandomizedUsesAllPoints() {
        assertEquals(8, LegacyDefence.epf(8, false, RandomSource.create(0L)));
        assertEquals(20, LegacyDefence.epf(40, false, RandomSource.create(0L)));
        assertEquals(0, LegacyDefence.epf(0, true, RandomSource.create(0L)));
    }

    @Test
    void noProtectionLeavesDamageAlone() {
        assertEquals(7.0F, LegacyDefence.afterProtection(7.0F, 0));
    }

    private static Set<Integer> rolls(final int points) {
        final RandomSource random = RandomSource.create(42L);
        final Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            final int epf = LegacyDefence.epf(points, true, random);
            assertTrue(epf >= 0 && epf <= 20);
            seen.add(epf);
        }
        return seen;
    }
}
