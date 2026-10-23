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
 *                        {@code AttackRange.getClosesetHit} first ({@link PickRay#castAttackRange}, which needs {@code reach})
 * @param riding the attacker rides something: same-vehicle targets follow different pick rules, not replayed
 * @param reach what that pick reads besides the eye, or null (an attack-range item then reads as unsupported)
 */
public record AttackerSnapshot(double xo, double yo, double zo, double x, double y, double z, float eyeHeight, float yRot, float xRot,
                               AABB box, double blockRange, double entityRange, boolean attackRangeItem, boolean riding,
                               @org.jspecify.annotations.Nullable Reach reach) {

    /** Without the attack-range pick's inputs. */
    public AttackerSnapshot(final double xo, final double yo, final double zo, final double x, final double y, final double z, final float eyeHeight,
                            final float yRot, final float xRot, final AABB box, final double blockRange, final double entityRange,
                            final boolean attackRangeItem, final boolean riding) {
        this(xo, yo, zo, x, y, z, eyeHeight, yRot, xRot, box, blockRange, entityRange, attackRangeItem, riding, null);
    }

    /**
     * What {@code AttackRange.getClosesetHit} reads besides the eye (ProjectileUtil.java:38-47).
     *
     * @param headYRot {@code getYHeadRot()} at the pick: {@code Player.aiStep} sets it to the yaw after the move
     *                 (Player.java:454), so at the keybinds it is the last tick's yaw while the pitch is this tick's
     * @param knownX {@code getKnownMovement()}: a local player's own delta movement after the last tick
     * @param min {@code effectiveMinRange}: the item's reach for the client's game mode (the tab list's, as
     *            {@code isCreative()} reads it)
     * @param max {@code effectiveMaxRange}
     * @param margin {@code hitboxMargin}
     */
    public record Reach(float headYRot, double knownX, double knownY, double knownZ, float min, float max, float margin) {
    }

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
