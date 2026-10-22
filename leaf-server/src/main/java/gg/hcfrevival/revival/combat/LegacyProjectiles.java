package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.KnockbackProfile;
import gg.hcfrevival.revival.config.LegacyProjectileSettings;
import gg.hcfrevival.revival.config.ProjectileRules;
import gg.hcfrevival.revival.config.RevivalSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 1.7.10 rod, bow, snowball and egg hits (patch 1008). Every hit still goes through the real damage path, so damage
 * events, PvP and team checks and the damage window keep working; this only changes what happens inside it.
 */
public final class LegacyProjectiles {

    private LegacyProjectiles() {
    }

    /** The projectile settings when projectile hitting victim is a legacy hit, or null. */
    static @Nullable LegacyProjectileSettings settings(final Entity victim, final Projectile projectile) {
        final LegacyProjectileSettings settings = Revival.settings().legacyCombat(victim.level().getWorld().getName()).projectiles();
        if (!settings.enabled() || !legacyType(projectile, settings)) {
            return null;
        }
        if (settings.playerShootersOnly() && !(projectile.getOwner() instanceof Player)) {
            return null;
        }
        if (settings.playerVictimsOnly() && !(victim instanceof Player)) {
            return null;
        }
        return settings;
    }

    static @Nullable LegacyProjectileSettings settings(final Entity victim, final DamageSource source) {
        return source.getDirectEntity() instanceof final Projectile projectile ? settings(victim, projectile) : null;
    }

    static boolean legacyType(final Projectile projectile, final LegacyProjectileSettings settings) {
        return switch (projectile) {
            case final Snowball ignored -> settings.snowballs();
            case final ThrownEgg ignored -> settings.eggs();
            case final FishingHook ignored -> settings.rods();
            case final ThrownEnderpearl ignored -> settings.rules().pearls().enabled(); // 1.7: a 0-damage thrown hit, like a snowball
            case final ThrownTrident ignored -> false;
            case final AbstractArrow arrow -> settings.arrows() && launchedBy(arrow, settings);
            default -> false;
        };
    }

    private static boolean launchedBy(final AbstractArrow arrow, final LegacyProjectileSettings settings) {
        final ItemStack weapon = arrow.getWeaponItem();
        return weapon != null && !weapon.isEmpty() && settings.arrowLaunchers().contains(BuiltInRegistries.ITEM.getKey(weapon.getItem()));
    }

    /** Whether a 0-damage hit on a player still lands, as on 1.7 CraftBukkit: knock, flash and a damage window with lastHurt 0. */
    public static boolean zeroDamageHit(final LivingEntity victim, final DamageSource source) {
        final LegacyProjectileSettings settings = settings(victim, source);
        return settings != null && settings.zeroDamageHits();
    }

    /**
     * The victim's motion after a legacy projectile hit, or null when it stays 26.x: the knockback engine with the
     * projectile's profile, away from the shooter's position (1.7.10's EntityLivingBase.attackEntityFrom used the
     * damage source's entity), plus Punch along an arrow's flight, all in one knockback event.
     */
    static @Nullable Vec3 targetMovement(final LivingEntity victim, final DamageSource source, final Vec3 current, double xd, double zd) {
        if (!(source.getDirectEntity() instanceof final Projectile projectile)) {
            return null;
        }
        final LegacyProjectileSettings settings = settings(victim, projectile);
        if (settings == null) {
            return null;
        }
        final Entity shooter = projectile.getOwner();
        final KnockbackProfile profile = profile(victim, shooter, settings);
        if (settings.knockbackFromShooter() && shooter != null && shooter.level() == victim.level()) {
            xd = shooter.getX() - victim.getX();
            zd = shooter.getZ() - victim.getZ();
        }
        Vec3 knocked = KnockbackEngine.knockback(LegacyKnockback.motion(victim, current, profile), xd, zd, 0.0F, 0.0, profile, victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), victim.getRandom());
        if (projectile instanceof final AbstractArrow arrow && arrow.getWeaponItem() != null && victim.level() instanceof final ServerLevel level) {
            final int punch = (int) EnchantmentHelper.modifyKnockback(level, arrow.getWeaponItem(), victim, source, 0.0F);
            final Vec3 flight = arrow.getDeltaMovement();
            knocked = KnockbackEngine.punch(knocked, flight.x, flight.z, punch, settings.punchPerLevel(), settings.punchVertical(), profile);
        }
        knocked = NeueCombat.influenceProjectile(knocked, victim, projectile, profile); // Neue's DI on melee-window projectiles; classic: unchanged
        if (victim instanceof final ServerPlayer player && profile.motionModel() == KnockbackProfile.MotionModel.VANILLA_1_7) {
            player.revivalMotionShadow.set(knocked);
        }
        return knocked;
    }

    /** Whether a legacy arrow's Punch is already in its knock, so 26.x's separate Punch push is skipped. */
    public static boolean punchIncluded(final LivingEntity victim, final DamageSource source) {
        return settings(victim, source) != null;
    }

    // The shooter's melee profile (their own, or their world's), or the named one
    private static KnockbackProfile profile(final LivingEntity victim, final @Nullable Entity shooter, final LegacyProjectileSettings settings) {
        final RevivalSettings live = Revival.settings();
        if (!LegacyProjectileSettings.INHERIT.equals(settings.knockbackProfile())) {
            final KnockbackProfile named = live.knockbackProfile(settings.knockbackProfile());
            if (named != null) {
                return named;
            }
        }
        if (shooter instanceof final ServerPlayer player) {
            final KnockbackProfile own = player.revivalKnockbackProfile != null ? live.knockbackProfile(player.revivalKnockbackProfile) : null;
            return own != null ? own : live.legacyCombat(player.level().getWorld().getName()).knockback();
        }
        return live.legacyCombat(victim.level().getWorld().getName()).knockback();
    }

    /**
     * A legacy hook landing on an entity: 1.7.10's EntityFishHook dealt it a 0-damage hit and only hooked it when the
     * hit landed. Returns whether to hook it.
     */
    public static boolean rodHits(final FishingHook hook, final Entity target) {
        final LegacyProjectileSettings settings = settings(target, hook);
        if (settings == null || !settings.rodHitOnImpact()) {
            return true;
        }
        final boolean landed = target.hurtOrSimulate(hook.damageSources().thrown(hook, hook.getOwner()), 0.0F);
        return landed || !settings.rodHookOnlyIfHit();
    }

    /**
     * Reeling in a hooked entity with a legacy rod: the 1.7.10 pull, on a player's server motion only (no visible
     * pull, as 1.7 never sent it), or on a mob's motion. Returns the rod durability to use, or -1 to reel as 26.x.
     */
    public static int reel(final FishingHook hook, final @Nullable Entity hooked) {
        final Entity owner = hook.getOwner();
        if (hooked == null || owner == null || hooked instanceof ItemEntity) {
            return -1;
        }
        final LegacyProjectileSettings settings = settings(hooked, hook);
        if (settings == null) {
            return -1;
        }
        final Vec3 pull = reelPull(owner.getX() - hook.getX(), owner.getY() - hook.getY(), owner.getZ() - hook.getZ());
        if (hooked instanceof final ServerPlayer player) {
            if (!settings.rodReelPullsPlayers()) {
                return -1;
            }
            player.revivalMotionShadow.set(player.revivalMotionShadow.motion().add(pull));
        } else {
            if (!settings.rodReelLegacyOther()) {
                return -1;
            }
            hooked.setDeltaMovement(hooked.getDeltaMovement().add(pull));
            hooked.needsSync = true;
        }
        return settings.rodReelDurability();
    }

    /** 1.7.10 EntityFishHook.func_146034_e: {@code d × 0.1}, plus {@code √distance × 0.08} up. */
    static Vec3 reelPull(final double dx, final double dy, final double dz) {
        final double distance = Mth.sqrt((float) (dx * dx + dy * dy + dz * dz));
        return new Vec3(dx * 0.1, dy * 0.1 + Mth.sqrt((float) distance) * 0.08, dz * 0.1);
    }

    /**
     * A legacy rod cast: 1.7.10's EntityFishHook launched along the player's look at {@code cast-speed} (1.5) with a
     * little Gaussian spread, under {@code gravity} (0.04) per tick. With {@code throw: legacy-1.7} it starts where 1.7's
     * did (feet + 1.62 - 0.1, 0.16 to the side; 26.x starts 0.3 ahead at the pose's eye); with {@code flight: legacy-1.7}
     * it moves 1.7's path ({@link LegacyThrow}: launch {@code v0 + 0.92 g ŷ}, gravity {@code 0.92 g}).
     */
    public static void cast(final FishingHook hook, final Player player) {
        final LegacyProjectileSettings settings = Revival.settings().legacyCombat(player.level().getWorld().getName()).projectiles();
        if (!settings.enabled() || !settings.rods()) {
            return;
        }
        final ProjectileRules rules = settings.rules();
        final float yaw = player.getYRot();
        final float pitch = player.getXRot();
        final Vec3 heading = castMotion(yaw, pitch, settings.rodCastSpeed(), rules.throwSpread(), hook.getRandom().nextGaussian(), hook.getRandom().nextGaussian(),
            hook.getRandom().nextGaussian());
        if (rules.legacyThrow()) {
            // EntityFishHook: posY + 1.62D (a double here, unlike EntityThrowable's float eye height), then - 0.1F
            hook.snapTo(LegacyThrow.originX(player.getX(), yaw, rules.throwSide()), player.getY() + rules.throwEyeHeight() - (double) (float) rules.throwDrop(),
                LegacyThrow.originZ(player.getZ(), yaw, rules.throwSide()), hook.getYRot(), hook.getXRot());
        }
        if (rules.legacyFlight()) {
            hook.setDeltaMovement(LegacyThrow.rodLaunch(heading, settings.rodGravity()));
            hook.revivalGravity = LegacyThrow.rodGravity(settings.rodGravity());
        } else {
            hook.setDeltaMovement(heading);
            hook.revivalGravity = settings.rodGravity();
        }
        hook.setYRot((float) (Mth.atan2(heading.x, heading.z) * 57.2957763671875));
        hook.setXRot((float) (Mth.atan2(heading.y, heading.horizontalDistance()) * 57.2957763671875));
        hook.yRotO = hook.getYRot();
        hook.xRotO = hook.getXRot();
    }

    /** EntityFishHook's constructor and {@code func_146035_c(motion, 1.5F, 1.0F)} (its setThrowableHeading), {@link LegacyThrow#heading}. */
    static Vec3 castMotion(final float yaw, final float pitch, final double speed, final double spread, final double spreadX, final double spreadY,
                           final double spreadZ) {
        return LegacyThrow.heading(yaw, pitch, 0.0F, LegacyThrow.THROWABLE_LENGTH, speed, spread, spreadX, spreadY, spreadZ);
    }
}
