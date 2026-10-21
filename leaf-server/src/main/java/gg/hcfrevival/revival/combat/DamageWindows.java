package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.config.DamageWindowSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Separate damage windows for melee, projectile, mob and other damage (patch 1010), so a sword combo no longer
 * swallows fire ticks, poison, arrows or mob hits, and those don't swallow sword hits.
 * <p>
 * Melee keeps the entity's real {@code damageCooldownTime}, {@code lastHurt} and {@code invulnerableDuration}, so the
 * Bukkit no-damage-ticks API and plugins that edit it between hits keep working. Every other channel has its own
 * slot (lastHurt and the server tick its window ends), which {@link #enter} swaps into those fields for the length of
 * {@code LivingEntity.hurtServer} and {@link #exit} swaps back, so the vanilla window rule, Paper's invulnerability
 * reduction and the damage event run unchanged against the channel's own window.
 */
public final class DamageWindows {

    public static final int MELEE = 0;
    public static final int PROJECTILE = 1;
    public static final int MOB = 2;
    public static final int OTHER = 3;

    /** Returned by {@link #enter} when the fields already hold the right window. */
    public static final int NONE = -1;

    private final float[] lastHurt = new float[4];
    private final long[] expiry = new long[4];
    private final int[] duration = new int[4];
    private int active = MELEE;
    private long now;

    /** Swaps the window for this damage into the victim's fields; returns the channel {@link #exit} restores, or {@link #NONE}. */
    public static int enter(final LivingEntity victim, final DamageSource source) {
        final DamageWindowSettings settings = Revival.settings().legacyCombat(victim.level().getWorld().getName()).damageWindows();
        if (!settings.enabled()) {
            return NONE;
        }
        final int channel = channel(source, settings);
        final DamageWindows windows = victim.revivalDamageWindows;
        final int current = windows == null ? MELEE : windows.active;
        if (channel == current) {
            return NONE;
        }
        final DamageWindows slots = windows != null ? windows : (victim.revivalDamageWindows = new DamageWindows());
        slots.switchTo(victim, channel, slots.duration(victim, channel, settings), net.minecraft.server.MinecraftServer.currentTick);
        return current;
    }

    /** Puts back the window {@link #enter} swapped out. */
    public static void exit(final LivingEntity victim, final int previous) {
        if (previous != NONE) {
            final DamageWindows windows = victim.revivalDamageWindows;
            windows.switchTo(victim, previous, windows.duration[previous], windows.now);
        }
    }

    /** Whether this entity's damage windows are split; vanilla's "could this be hurt" shortcuts only see the melee window then. */
    public static boolean enabled(final LivingEntity entity) {
        return Revival.settings().legacyCombat(entity.level().getWorld().getName()).damageWindows().enabled();
    }

    /** The window this damage uses. */
    static int channel(final DamageSource source, final DamageWindowSettings settings) {
        final Entity direct = source.getDirectEntity();
        final Entity causing = source.getEntity();
        if (source.is(DamageTypes.PLAYER_ATTACK) && causing instanceof Player) {
            return MELEE;
        }
        if (direct instanceof Projectile && settings.meleeProjectiles().contains(BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()))) {
            return MELEE;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)) {
            return OTHER; // splash harming, evoker fangs, fireball and wither skull blasts
        }
        if (direct instanceof Projectile) {
            return PROJECTILE;
        }
        if (causing instanceof LivingEntity && !(causing instanceof Player) && direct == causing && !source.is(DamageTypes.THORNS)) {
            return MOB;
        }
        return OTHER;
    }

    // The window length to load: the channel's configured ticks, else the entity's own (melee) length
    private int duration(final LivingEntity victim, final int channel, final DamageWindowSettings settings) {
        final int melee = this.active == MELEE ? victim.invulnerableDuration : this.duration[MELEE];
        final int ticks = switch (channel) {
            case PROJECTILE -> settings.projectileTicks();
            case MOB -> settings.mobTicks();
            case OTHER -> settings.otherTicks();
            default -> DamageWindowSettings.INHERIT;
        };
        return ticks == DamageWindowSettings.INHERIT ? melee : ticks;
    }

    /** Saves the victim's fields into the active channel's slot and loads channel {@code to}'s. */
    void switchTo(final LivingEntity victim, final int to, final int duration, final long now) {
        final int from = this.active;
        this.lastHurt[from] = victim.lastHurt;
        this.expiry[from] = now + victim.damageCooldownTime;
        this.duration[from] = victim.invulnerableDuration;
        victim.lastHurt = this.lastHurt[to];
        victim.damageCooldownTime = (int) Math.max(0L, this.expiry[to] - now);
        victim.invulnerableDuration = duration;
        this.active = to;
        this.now = now;
    }
}
