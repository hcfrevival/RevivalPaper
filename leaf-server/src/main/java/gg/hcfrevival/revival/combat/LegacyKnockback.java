package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.api.event.RevivalKnockbackEvent;
import gg.hcfrevival.revival.api.event.SprintArmEvent;
import gg.hcfrevival.revival.api.event.SprintConsumeEvent;
import gg.hcfrevival.revival.config.KnockbackProfile;
import gg.hcfrevival.revival.config.LegacyCombatSettings;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
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

    /** Players who asked for a line about every legacy knock they deal or take ({@code /revival kb debug}). */
    public static final Set<UUID> DEBUG = ConcurrentHashMap.newKeySet();

    /**
     * The knockback profile when this damage is a legacy melee hit by attacker, or null: the attacker's own profile
     * if one was set ({@code /revival kb use <profile> player <name>} or the API), otherwise their world's.
     */
    public static @Nullable KnockbackProfile profile(final @Nullable Entity attacker, final DamageSource source) {
        return profile(attacker, source.getDirectEntity() == attacker ? LegacyAttack.settings(source) : null);
    }

    private static @Nullable KnockbackProfile profile(final @Nullable Entity attacker, final @Nullable LegacyCombatSettings settings) {
        if (settings == null) {
            return null;
        }
        final KnockbackProfile own = attacker instanceof final ServerPlayer player && player.revivalKnockbackProfile != null
            ? Revival.settings().knockbackProfile(player.revivalKnockbackProfile)
            : null;
        return own != null ? own : settings.knockback();
    }

    /** The name of the profile {@link #profile} picks for this player, given their world's settings. */
    public static String profileName(final ServerPlayer player, final LegacyCombatSettings settings) {
        final String own = player.revivalKnockbackProfile;
        return own != null && Revival.settings().knockbackProfile(own) != null ? own : settings.knockbackProfileName();
    }

    /**
     * After a legacy knock went through Paper's knockback event: fires {@link RevivalKnockbackEvent} with the
     * victim's new motion and prints {@code /revival kb debug} lines.
     */
    public static void afterKnock(final LivingEntity victim, final DamageSource source, final @Nullable Entity attacker, final boolean comesFromEffect) {
        final boolean listening = RevivalKnockbackEvent.getHandlerList().getRegisteredListeners().length > 0;
        if (!listening && DEBUG.isEmpty()) {
            return;
        }
        final LegacyCombatSettings settings = source.getDirectEntity() == attacker ? LegacyAttack.settings(source) : null;
        if (settings == null || !(attacker instanceof final ServerPlayer player)) {
            return;
        }
        final String profile = profileName(player, settings);
        final Vec3 motion = victim.getDeltaMovement();
        if (listening) {
            new RevivalKnockbackEvent((org.bukkit.entity.LivingEntity) victim.getBukkitEntity(), player.getBukkitEntity(), profile, CraftVector.toBukkit(motion), comesFromEffect).callEvent();
        }
        if (!DEBUG.isEmpty() && (DEBUG.contains(player.getUUID()) || DEBUG.contains(victim.getUUID()))) {
            final String spot = comesFromEffect || !settings.rules().shapesKnock() ? "" : NeueShape.name(NeueCombat.state(player).spot());
            final Component line = Component.text(String.format(Locale.ROOT, "[kb] %s -> %s %s%s%s: (%.4f, %.4f, %.4f)",
                player.getScoreboardName(), victim.getScoreboardName(), profile, comesFromEffect ? " extra only" : "", spot.isEmpty() ? "" : " " + spot,
                motion.x, motion.y, motion.z), NamedTextColor.GRAY);
            if (DEBUG.contains(player.getUUID())) {
                player.getBukkitEntity().sendMessage(line);
            }
            if (victim instanceof final ServerPlayer target && target != player && DEBUG.contains(target.getUUID())) {
                target.getBukkitEntity().sendMessage(line);
            }
        }
    }

    /**
     * START_SPRINTING: arms the sprint ledger and fires {@link SprintArmEvent}. Under authority the simulation arms it
     * instead ({@link #simulatedSprint}), so sprint packets alone can't re-arm a sprint knock.
     */
    public static void sprintStart(final ServerPlayer player) {
        if (player.revivalAuth.ownsClientState()) {
            return;
        }
        arm(player, player.onGround());
    }

    /**
     * The simulation saw the client start or stop sprinting (enforce, design §7.2): the ledger follows the client's real
     * sprint. {@code onGround} is the client's ground state when it decided, before that tick's move.
     */
    public static void simulatedSprint(final ServerPlayer player, final boolean sprinting, final boolean onGround) {
        if (sprinting) {
            arm(player, onGround);
        } else {
            player.revivalSprintLedger.stop();
        }
    }

    private static void arm(final ServerPlayer player, final boolean onGround) {
        if (player.revivalSprintLedger.start(onGround) && SprintArmEvent.getHandlerList().getRegisteredListeners().length > 0) {
            new SprintArmEvent(player.getBukkitEntity()).callEvent();
        }
    }

    /**
     * The victim's motion after a legacy hit, or null when the knock stays 26.x. The base knock (from the damage)
     * carries the extra knock too; the extra-knock call ({@code comesFromEffect}) only reaches here when the hit
     * was an overflow inside the damage window, which 1.7.10 still pushed by the extra knock alone. For a player
     * that push only changed their server-side motion: 1.7.10 set velocityChanged on fresh hits only, so it was
     * never sent. Sending it would set the victim's velocity from their 1.7 server motion, which falls while they
     * are in the air, and pull them into the floor.
     */
    public static @Nullable Vec3 targetMovement(final LivingEntity victim, final DamageSource source, final @Nullable Entity attacker, final Vec3 current, final double xd, final double zd, final boolean comesFromEffect) {
        final LegacyCombatSettings settings = source.getDirectEntity() == attacker ? LegacyAttack.settings(source) : null;
        final KnockbackProfile profile = profile(attacker, settings);
        if (profile == null) {
            return comesFromEffect ? null : LegacyProjectiles.targetMovement(victim, source, current, xd, zd);
        }
        final ServerPlayer player = (ServerPlayer) attacker;
        final double level = knockbackLevel(player, player.getWeaponItem()) + sprintLevel(player, profile);
        if (comesFromEffect) {
            final Vec3 pushed = KnockbackEngine.extraKnock(motion(victim, current, profile), player.getYRot(), level, profile);
            if (victim instanceof final ServerPlayer target && profile.motionModel() != KnockbackProfile.MotionModel.NONE) {
                target.revivalMotionShadow.set(pushed);
            }
            return pushed;
        }
        player.revivalBaseKnockTarget = victim;
        final Vec3 knock = KnockbackEngine.knockback(motion(victim, current, profile), xd, zd, player.getYRot(), level, profile, victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), victim.getRandom());
        // Neue's tipper and DI shape the whole fresh knock (docs/revival/neue-combat.md §6.2); classic returns it untouched.
        // The overflow extra knock above is never shaped, as Combo's ExtraKnock isn't
        return settings.rules().shapesKnock() ? NeueCombat.shapeMelee(knock, victim, player, settings.rules(), profile) : knock;
    }

    /**
     * Whether {@code Player.causeExtraKnockback} should knock the target itself: always for a 26.x hit, and for a
     * legacy hit only when the damage didn't already knock the target with base and extra knock together.
     */
    public static boolean needsExtraKnock(final Player attacker, final Entity target, final DamageSource source) {
        if (profile(attacker, source) == null) {
            return true;
        }
        final ServerPlayer player = (ServerPlayer) attacker;
        final boolean knocked = player.revivalBaseKnockTarget == target;
        player.revivalBaseKnockTarget = null;
        return !knocked;
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

    // The motion a victim is knocked from: a player's 1.7 server motion, the entity's own for mobs and model none, or the
    // client's own (simulated) motion for model authoritative while the simulation owns the victim
    static Vec3 motion(final LivingEntity victim, final Vec3 current, final KnockbackProfile profile) {
        if (!(victim instanceof final ServerPlayer player) || profile.motionModel() == KnockbackProfile.MotionModel.NONE) {
            return current;
        }
        if (profile.motionModel() == KnockbackProfile.MotionModel.AUTHORITATIVE && player.revivalAuth.ownsClientState()) {
            return current; // the server's motion is the simulation's (§15.5)
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
        if (!(attacker instanceof final ServerPlayer player)) {
            return 0.0;
        }
        // Under authority, the sprint the client attacked with (the simulation's), not the last sprint packet
        final boolean sprinting = player.revivalAuth.ownsClientState() ? player.revivalAuth.combatSprinting() : player.isSprinting();
        return player.revivalSprintLedger.level(sprinting, profile);
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
            if (player.revivalSprintLedger.consume() && SprintConsumeEvent.getHandlerList().getRegisteredListeners().length > 0) {
                new SprintConsumeEvent(player.getBukkitEntity()).callEvent();
            }
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
