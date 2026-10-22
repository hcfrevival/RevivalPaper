package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.LegacyProjectileSettings;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Splash potion strength (docs/revival/projectiles.md §4, pvp-issues PR-4).
 * <p>
 * 1.7.10's {@code EntityPotion.onImpact} ran before the impact tick's move, at the potion's position from the start of
 * that tick. It took every living entity in the potion's box grown by (4, 2, 4), and gave each one at
 * {@code d = getDistanceSqToEntity} (position to position: the potion's box bottom centre to the entity's feet) under 4
 * blocks the strength {@code 1 - √d / 4}, or 1 to the entity the potion hit. Instant effects heal or harm
 * {@code (int) (strength × (4 << amplifier) + 0.5)}; others last {@code (int) (strength × duration + 0.5)} ticks if that is
 * over 20.
 * <p>
 * 26.3's {@code ThrownSplashPotion.onHitAsPotion} measures from the potion's box at the hit point to the entity's box
 * grown by the projectile margin, box to box, so any box touching the potion's gets full strength and the falloff starts
 * at the box's edge rather than at its feet. A potion thrown straight down by a standing player heals 8 there and 7 on
 * 1.7 (it bursts 0.48 above the ground and 0.16 to the side of the feet, {@code 1 - 0.501 / 4 = 0.875}).
 * <p>
 * Under {@code falloff: legacy-1.7} the effect box is the potion's box at its start-of-tick position
 * ({@code oldPosition()}, which with {@code flight: legacy-1.7} is exactly 1.7's pre-move position) and the strength is
 * 1.7's. The instant and duration formulas are the same in both versions and stay vanilla's.
 */
public final class LegacySplash {

    private LegacySplash() {
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * {@code ThrownSplashPotion.onHitAsPotion}, at {@code AABB potionAabb = ...}: the potion's box the effect box grows from,
     * at its start-of-tick position under the legacy falloff, else {@code vanilla}.
     */
    public static AABB box(final ThrownSplashPotion potion, final @Nullable HitResult hit, final AABB vanilla) {
        if (hit == null || !legacy(potion)) {
            return vanilla;
        }
        final Vec3 old = potion.oldPosition();
        return potion.getBoundingBox().move(old.x - potion.getX(), old.y - potion.getY(), old.z - potion.getZ());
    }

    /**
     * {@code ThrownSplashPotion.onHitAsPotion}, in place of {@code if (dist < 16.0) { double scale = 1.0 - Math.sqrt(dist) /
     * 4.0;}}: the strength for {@code entity}, or 0 when it is out of range. {@code vanillaDistanceSqr} is 26.x's
     * box-to-box distance squared, used when the legacy falloff is off.
     */
    public static double scale(final ThrownSplashPotion potion, final LivingEntity entity, final @Nullable HitResult hit, final double vanillaDistanceSqr) {
        if (hit == null || !legacy(potion)) {
            return vanillaScale(vanillaDistanceSqr);
        }
        final Vec3 old = potion.oldPosition();
        final boolean direct = hit instanceof final EntityHitResult entityHit && entityHit.getEntity() == entity;
        return legacyScale(old.x, old.y, old.z, entity.getX(), entity.getY(), entity.getZ(), direct);
    }

    private static boolean legacy(final ThrownSplashPotion potion) {
        final LegacyProjectileSettings settings = Revival.settings().legacyCombat(potion.level().getWorld().getName()).projectiles();
        if (!settings.enabled() || !settings.rules().splash().enabled() || !settings.rules().splash().legacyFalloff()) {
            return false;
        }
        return !settings.playerShootersOnly() || potion.getOwner() instanceof Player;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Formulas
    // ------------------------------------------------------------------------------------------------------------------

    /** 26.3: {@code dist < 16 ? 1 - √dist / 4 : 0}, dist being the box-to-box distance squared. */
    static double vanillaScale(final double distanceSqr) {
        return distanceSqr < 16.0 ? 1.0 - Math.sqrt(distanceSqr) / 4.0 : 0.0;
    }

    /**
     * 1.7.10 EntityPotion.onImpact: 1 for the entity hit, else {@code 1 - √d / 4} for {@code d}, the squared distance from the
     * potion's position to the entity's, under 16; else 0.
     */
    static double legacyScale(final double potionX, final double potionY, final double potionZ, final double entityX, final double entityY,
                              final double entityZ, final boolean direct) {
        final double dx = potionX - entityX;
        final double dy = potionY - entityY;
        final double dz = potionZ - entityZ;
        final double distanceSqr = dx * dx + dy * dy + dz * dz;
        if (!(distanceSqr < 16.0)) {
            return 0.0;
        }
        return direct ? 1.0 : 1.0 - Math.sqrt(distanceSqr) / 4.0;
    }

    /** {@code HealOrHarmMobEffect} (26.3) and {@code Potion.affectEntity} (1.7.10): {@code (int) (scale × (4 << amplifier) + 0.5)}. */
    static int heal(final double scale, final int amplifier) {
        return (int) (scale * (4 << amplifier) + 0.5);
    }
}
