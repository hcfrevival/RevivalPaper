package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.KnockbackProfile;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * 1.7.10 knockback with a profile's constants, kept free of entities so it can be tested on its own. Base knock
 * from EntityLivingBase.knockBack, then the extra knock from EntityPlayer.attackTargetEntityWithCurrentItem, as one
 * vector so plugins see one knockback event per hit.
 */
public final class KnockbackEngine {

    private KnockbackEngine() {
    }

    /**
     * The victim's motion after a legacy hit.
     *
     * @param current the victim's motion before the hit
     * @param xd attacker x minus victim x
     * @param zd attacker z minus victim z
     * @param attackerYaw the attacker's yaw in degrees, for the extra knock
     * @param level Knockback enchant level, plus 1 when the attacker is sprinting
     * @param resistance the victim's knockback-resistance attribute
     */
    public static Vec3 knockback(final Vec3 current, double xd, double zd, final float attackerYaw, final double level, final KnockbackProfile profile, final double resistance, final RandomSource random) {
        final double scale = switch (profile.resistanceMode()) {
            case NONE -> 1.0;
            case ROLL -> random.nextDouble() < resistance ? 0.0 : 1.0;
            case SCALE -> Math.max(0.0, 1.0 - resistance);
        };
        if (scale == 0.0) {
            return current; // 1.7.10 skips the whole knock on a successful roll
        }

        // 1.7.10 only jitters when attacker and victim stand on the same spot
        while (xd * xd + zd * zd < 1.0E-4) {
            xd = (random.nextDouble() - random.nextDouble()) * 0.01;
            zd = (random.nextDouble() - random.nextDouble()) * 0.01;
        }
        final double distance = Math.sqrt(xd * xd + zd * zd);
        final double horizontal = profile.horizontal() * scale;
        double x = current.x / profile.friction() - xd / distance * horizontal;
        double y = Math.min(current.y / profile.frictionVertical() + profile.vertical() * scale, profile.verticalCap());
        double z = current.z / profile.friction() - zd / distance * horizontal;

        if (level > 0.0) {
            final double extra = level * profile.extraHorizontal() * scale;
            x += -Mth.sin(attackerYaw * Mth.DEG_TO_RAD) * extra;
            y += profile.extraVertical() * scale;
            z += Mth.cos(attackerYaw * Mth.DEG_TO_RAD) * extra;
        }

        final double clamp = profile.velocityClamp();
        return new Vec3(Mth.clamp(x, -clamp, clamp), Mth.clamp(y, -clamp, clamp), Mth.clamp(z, -clamp, clamp));
    }
}
