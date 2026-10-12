package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.config.KnockbackProfile;
import gg.hcfrevival.revival.config.LegacyCombatSettings;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.util.CraftVector;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.jspecify.annotations.Nullable;

/**
 * Called from {@code LivingEntity.knockback} and {@code Player.causeExtraKnockback} (patches 1002 and 1003). A
 * legacy melee hit gets its base and extra knock from {@link KnockbackEngine} in one knockback event; the second,
 * extra-knockback event 26.x sends is skipped. A player victim is knocked from their {@link MotionShadow} and gets
 * one velocity packet. Every other knock stays 26.x.
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
        final double level = knockbackLevel(player, player.getWeaponItem()) + sprintLevel(player, profile);
        return KnockbackEngine.knockback(motion(victim, current, profile), xd, zd, player.getYRot(), level, profile, victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), victim.getRandom());
    }

    /**
     * Sends a player victim their legacy knock at once, then restores their server motion: SportBukkit 1.7.10's
     * 0066, which 26.x already does for every hit. For a legacy hit this also builds {@code PlayerVelocityEvent}
     * from the knock instead of the pre-hit motion, and clears {@code syncVelocity} even when the event is
     * cancelled, so neither tracker path sends a second packet. Returns false when the hit isn't legacy.
     */
    public static boolean sendVelocity(final Player attacker, final Entity victim, final Vec3 oldMovement, final DamageSource source) {
        if (!(victim instanceof final ServerPlayer player) || !player.syncVelocity) {
            return false;
        }
        final KnockbackProfile profile = profile(attacker, source);
        if (profile == null) {
            return false;
        }
        player.syncVelocity = false;
        final PlayerVelocityEvent event = new PlayerVelocityEvent(player.getBukkitEntity(), CraftVector.toBukkit(player.getDeltaMovement()));
        if (event.callEvent()) {
            final Vec3 knock = CraftVector.toVec3(event.getVelocity());
            player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), knock));
            if (profile.motionModel() == KnockbackProfile.MotionModel.VANILLA_1_7) {
                player.revivalMotionShadow.set(knock);
            }
        }
        player.setDeltaMovement(oldMovement);
        return true;
    }

    // The motion a victim is knocked from: a player's 1.7 server motion, or the entity's own for mobs and model none
    private static Vec3 motion(final LivingEntity victim, final Vec3 current, final KnockbackProfile profile) {
        if (!(victim instanceof final ServerPlayer player) || profile.motionModel() == KnockbackProfile.MotionModel.NONE) {
            return current;
        }
        final Vec3 shadow = player.revivalMotionShadow.motion();
        if (profile.groundPredictionDistance() > 0.0 && airborne(player)) {
            final double gravity = player.getAttributeValue(Attributes.GRAVITY);
            final long ping = player.connection.latency() - Math.round(profile.groundPredictionPingOffset());
            if (MotionShadow.landedForClient(ping, player.getKnownMovement().y, distanceToGround(player, profile.groundPredictionDistance()), gravity, profile.groundPredictionDistance())) {
                return MotionShadow.settled(shadow, gravity);
            }
        }
        return shadow;
    }

    // Core's ClientGround.airborne: falling freely, so the landing estimate holds
    private static boolean airborne(final ServerPlayer player) {
        return !player.onGround()
            && !player.isFallFlying()
            && !player.getAbilities().flying
            && !player.onClimbable()
            && !player.isPassenger()
            && !player.isInWater()
            && !player.isInLava()
            && !player.hasEffect(MobEffects.LEVITATION)
            && !player.hasEffect(MobEffects.SLOW_FALLING)
            && !player.getInBlockState().is(Blocks.COBWEB)
            && !player.getInBlockState().is(Blocks.SCAFFOLDING);
    }

    // Shortest drop from the four bottom corners of the player's box to a block, up to max; max + 1 when none is in reach
    private static double distanceToGround(final ServerPlayer player, final double max) {
        final AABB box = player.getBoundingBox();
        final double y = player.getY();
        final double inset = 0.01;
        double nearest = max + 1.0;
        for (int corner = 0; corner < 4; corner++) {
            final double x = (corner & 1) == 0 ? box.minX + inset : box.maxX - inset;
            final double z = (corner & 2) == 0 ? box.minZ + inset : box.maxZ - inset;
            final Vec3 from = new Vec3(x, y, z);
            final BlockHitResult hit = player.level().clip(new ClipContext(from, new Vec3(x, y - max - 1.0, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                nearest = Math.min(nearest, y - hit.getLocation().y);
            }
        }
        return nearest;
    }

    /** The sprint part of a legacy hit's extra-knockback level, from the attacker's {@link SprintLedger}. */
    public static double sprintLevel(final Player attacker, final KnockbackProfile profile) {
        return attacker instanceof final ServerPlayer player ? player.revivalSprintLedger.level(player.isSprinting(), profile) : 0.0;
    }

    /**
     * A hit with extra knockback landed. For a legacy hit this consumes the attacker's sprint arm and slows their
     * {@link MotionShadow} too. Returns the attacker's horizontal slowdown: the profile's for a legacy hit, 26.x's
     * 0.6 otherwise.
     */
    public static double onExtraKnock(final Player attacker, final DamageSource source) {
        final KnockbackProfile profile = profile(attacker, source);
        if (profile == null) {
            return 0.6;
        }
        if (attacker instanceof final ServerPlayer player) {
            player.revivalSprintLedger.consume();
            player.revivalMotionShadow.scaleHorizontal(profile.attackerSlowdown());
        }
        return profile.attackerSlowdown();
    }

    private static int knockbackLevel(final Player player, final ItemStack weapon) {
        if (weapon.isEmpty() || !(player.level() instanceof final ServerLevel level)) {
            return 0;
        }
        return EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK), weapon);
    }
}
