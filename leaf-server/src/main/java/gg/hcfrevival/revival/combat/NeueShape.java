package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.CombatRules;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Neue's touches on a fresh legacy knock (docs/revival/neue-combat.md §6-7), ported from Combo's {@code Combat.Shape}
 * and {@code Combat.Heading}: the tipper (a hit from the edge of reach knocks further, a point-blank one less) and DI
 * (the victim's held direction bends the knock). Horizontal only, so combos keep 1.7's arcs. Pure: no entities, no
 * allocation beyond the result.
 */
public final class NeueShape {

    /** Between the bands, or no distance at all. */
    public static final int PLAIN = 0;
    /** From {@code tipper-from} (eye to box) or further: the edge of reach. */
    public static final int TIPPER = 1;
    /** Closer than {@code sour-under}, the eye inside the box (0) included. */
    public static final int SOUR = 2;

    private NeueShape() {
    }

    /**
     * Where on its reach a hit landed. Combo's {@code Combat.Shape} reads 0 as "no distance" (its rod); here 0 is the
     * eye inside the target's box (players overlapping), so it is sour (§6.2). NaN, a hit with no distance, is plain.
     */
    public static int spot(final double distance, final CombatRules rules) {
        if (distance >= rules.tipperFrom()) {
            return TIPPER;
        }
        return distance >= 0.0 && distance < rules.sourUnder() ? SOUR : PLAIN;
    }

    /** The horizontal knock scale of a spot. */
    public static double scale(final int spot, final CombatRules rules) {
        return switch (spot) {
            case TIPPER -> rules.tipperKnock();
            case SOUR -> rules.sourKnock();
            default -> 1.0;
        };
    }

    /** Spot names for {@code /revival kb debug} and stats; plain is the empty string. */
    public static String name(final int spot) {
        return switch (spot) {
            case TIPPER -> "tipper";
            case SOUR -> "sour";
            default -> "";
        };
    }

    /**
     * The knock shaped: x and z scaled, then turned toward the held direction by up to {@code influenceDegrees}, then
     * clamped again to the profile's velocity clamp (a tipper can pass 3.9). y is never touched. Returns {@code knock}
     * itself when nothing changes it, so classic stays the engine's own vector.
     *
     * @param scale {@link #scale} of the hit's spot
     * @param forward the victim's {@code W − S}: 1, 0 or -1
     * @param left the victim's {@code A − D}: 1, 0 or -1
     * @param yaw the victim's yaw in degrees (0 faces +Z) when those keys were held
     * @param clamp the knockback profile's {@code velocity-clamp}
     */
    public static Vec3 shape(final Vec3 knock, final double scale, final double influenceDegrees, final int forward, final int left, final float yaw, final double clamp) {
        final boolean steers = influenceDegrees > 0.0 && (forward != 0 || left != 0);
        if (scale == 1.0 && !steers) {
            return knock;
        }
        double x = knock.x * scale;
        double z = knock.z * scale;
        if (steers) {
            // Combat.Heading: the way the keys point in the world, as 26.3's Entity.getInputVector turns (left, 0, forward)
            final double sin = Mth.sin(yaw * Mth.DEG_TO_RAD);
            final double cos = Mth.cos(yaw * Mth.DEG_TO_RAD);
            final double hx = left * cos - forward * sin;
            final double hz = forward * cos + left * sin;
            final double held = Math.sqrt(hx * hx + hz * hz);
            final double length = Math.sqrt(x * x + z * z);
            if (length > 1.0E-9 && held > 1.0E-9) {
                // The held way's side of the knock (the 2D cross product of the two directions): the sine between them.
                // Held across the knock bends it the whole way; along it, or straight against it, not at all
                final double across = (x * hz - z * hx) / (length * held);
                final double turn = influenceDegrees * Math.PI / 180 * across;
                final double turnSin = Math.sin(turn);
                final double turnCos = Math.cos(turn);
                final double turnedX = x * turnCos - z * turnSin;
                z = x * turnSin + z * turnCos;
                x = turnedX;
            }
        }
        return new Vec3(Mth.clamp(x, -clamp, clamp), Mth.clamp(knock.y, -clamp, clamp), Mth.clamp(z, -clamp, clamp));
    }

    /** {@link #shape} with the spot's scale and the rules' DI: the whole of Neue's knock touch for one fresh hit. */
    public static Vec3 shape(final Vec3 knock, final int spot, final CombatRules rules, final int forward, final int left, final float yaw, final double clamp) {
        return shape(knock, scale(spot, rules), rules.influenceDegrees(), forward, left, yaw, clamp);
    }

    /** Held direction x for {@code (forward, left)} at {@code yaw}, before normalizing; {@link #shape} inlines it. */
    static double headingX(final int forward, final int left, final float yaw) {
        return left * (double) Mth.cos(yaw * Mth.DEG_TO_RAD) - forward * (double) Mth.sin(yaw * Mth.DEG_TO_RAD);
    }

    /** Held direction z, see {@link #headingX}. */
    static double headingZ(final int forward, final int left, final float yaw) {
        return forward * (double) Mth.cos(yaw * Mth.DEG_TO_RAD) + left * (double) Mth.sin(yaw * Mth.DEG_TO_RAD);
    }
}
