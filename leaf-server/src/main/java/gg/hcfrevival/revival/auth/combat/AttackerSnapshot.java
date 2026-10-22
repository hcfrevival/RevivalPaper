package gg.hcfrevival.revival.auth.combat;

import net.minecraft.world.phys.AABB;

/**
 * The attacker as its client cast the attack ray at the start of client tick {@code T} (report 02 §2.2): position at the
 * end of tick {@code T-1}, rotation as tick {@code T}'s move packet reports it, interaction ranges from the client's
 * attributes. Built by the hook from the authoritative simulation and the client view.
 *
 * @param xo the attacker's {@code xo}: position when tick {@code T-1}'s {@code commonTick} ran (or a teleport's rewrite)
 * @param x the position after tick {@code T-1}'s movement; the eye is {@code Mth.lerp(1.0, xo, x)}, which can differ
 *          from {@code x} by one ulp
 * @param eyeHeight {@code getEyeHeight()} of the pose the attacker had then
 * @param yRot raw rotation float the client holds (not the packet's byte)
 * @param box the attacker's own {@code getBoundingBox()} then: the entity search box grows from it
 * @param blockRange {@code blockInteractionRange()}, {@code minecraft:block_interaction_range} as the client computes it
 * @param entityRange {@code entityInteractionRange()}, {@code minecraft:entity_interaction_range}
 * @param attackRangeItem the active item has {@code DataComponents.ATTACK_RANGE} (spears): the client picks with
 *                        {@code AttackRange.getClosesetHit} instead, which {@link PickRay} does not replay
 * @param riding the attacker rides something: same-vehicle targets follow different pick rules, not replayed
 */
public record AttackerSnapshot(double xo, double yo, double zo, double x, double y, double z, float eyeHeight, float yRot, float xRot,
                               AABB box, double blockRange, double entityRange, boolean attackRangeItem, boolean riding) {

    /** {@code Entity.getEyePosition(1.0F)} x (Entity.java:2057-2062): {@code Mth.lerp(1.0, xo, x) = xo + 1.0 * (x - xo)}. */
    public double eyeX() {
        return this.xo + 1.0 * (this.x - this.xo);
    }

    /** {@code Mth.lerp(1.0, yo, y) + getEyeHeight()}: the float eye height widened. */
    public double eyeY() {
        return this.yo + 1.0 * (this.y - this.yo) + this.eyeHeight;
    }

    public double eyeZ() {
        return this.zo + 1.0 * (this.z - this.zo);
    }
}
