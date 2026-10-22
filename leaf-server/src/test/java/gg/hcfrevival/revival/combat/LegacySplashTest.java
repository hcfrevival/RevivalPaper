package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.hcfrevival.revival.RevivalTest;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * Splash strength goldens (docs/revival/projectiles.md §4). 1.7.10 EntityPotion.onImpact:
 * {@code d1 = 1 - sqrt(getDistanceSqToEntity) / 4} under 16, 1 for the entity hit; Potion.affectEntity heals
 * {@code (int) (d1 × (4 << amplifier) + 0.5)}. 26.3 ThrownSplashPotion.onHitAsPotion:
 * {@code 1 - sqrt(potionAabb.distanceToSqr(entityBox.inflate(margin))) / 4}.
 */
@RevivalTest
class LegacySplashTest {

    // Instant Health II (amplifier 1) heals per 1.7 distance 0, 0.5, ..., 4 from the potion to the feet
    private static final int[] LEGACY_HEAL = {8, 7, 6, 5, 4, 3, 2, 1, 0};

    @Test
    void legacyFalloffIsOneMinusTheQuarterDistanceToTheFeet() {
        for (int i = 0; i < LEGACY_HEAL.length; i++) {
            final double d = i * 0.5;
            final double scale = LegacySplash.legacyScale(d, 64.0, 0.0, 0.0, 64.0, 0.0, false);
            assertEquals(Math.max(0.0, 1.0 - d / 4.0), scale, 1.0E-12, "d " + d);
            assertEquals(LEGACY_HEAL[i], LegacySplash.heal(scale, 1), "d " + d);
        }
        // Measured to the feet, not the box: a potion bursting at head height 1.6 above the feet is 1.6 away
        assertEquals(0.6, LegacySplash.legacyScale(0.0, 65.6, 0.0, 0.0, 64.0, 0.0, false), 1.0E-12);
        // The entity the potion hit gets full strength wherever the burst is
        assertEquals(1.0, LegacySplash.legacyScale(0.0, 66.0, 3.0, 0.0, 64.0, 0.0, true), 0.0);
        // Out of range (d² not under 16): nothing, even for the entity hit
        assertEquals(0.0, LegacySplash.legacyScale(4.0, 64.0, 0.0, 0.0, 64.0, 0.0, true), 0.0);
    }

    /**
     * 26.3, a potion bursting on the ground at a horizontal offset from a standing player, in its third tick (margin
     * (3 - 2) / 20 = 0.05): the gap is box edge to box edge, so it heals 8 up to 0.475 + 0.05 away, where 1.7 heals 7.
     */
    @Test
    void vanillaFalloffIsBoxToBox() {
        final double margin = 0.05;
        final AABB player = new AABB(-0.3, 64.0, -0.3, 0.3, 65.8, 0.3).inflate(margin);
        final double[] offsets = {0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0};
        final int[] heals = {8, 8, 7, 6, 5, 4, 3, 2, 1};
        for (int i = 0; i < offsets.length; i++) {
            final double r = offsets[i];
            final AABB potion = new AABB(r - 0.125, 64.0, -0.125, r + 0.125, 64.25, 0.125);
            final double scale = LegacySplash.vanillaScale(potion.distanceToSqr(player));
            assertEquals(heals[i], LegacySplash.heal(scale, 1), "offset " + r);
        }
    }

    @Test
    void harmingUsesTheSameScaleOnSixPerLevel() {
        // InstantenousMobEffect harm (1.7 Potion.harm, 26.3 HealOrHarmMobEffect): (int) (scale × (6 << amplifier) + 0.5)
        final double scale = LegacySplash.legacyScale(1.0, 64.0, 0.0, 0.0, 64.0, 0.0, false);
        assertEquals(9, (int) (scale * (6 << 1) + 0.5));
    }
}
