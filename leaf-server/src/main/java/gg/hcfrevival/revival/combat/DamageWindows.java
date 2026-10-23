package gg.hcfrevival.revival.combat;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.auth.Authority;
import gg.hcfrevival.revival.config.DamageWindowSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
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
 * <p>
 * A player's melee window also follows the clicks' clock around a server stall ({@code damage-windows.melee-wall-clock},
 * pvp-issues SV-2): see {@link #catchUp}.
 */
public final class DamageWindows {

    public static final int MELEE = 0;
    public static final int PROJECTILE = 1;
    public static final int MOB = 2;
    public static final int OTHER = 3;

    /** Returned by {@link #enter} when the fields already hold the right window. */
    public static final int NONE = -1;
    // Returned by enter for a legacy melee click on the clicks' clock: exit settles it
    private static final int CLOCKED = -2;

    private final float[] lastHurt = new float[4];
    private final long[] expiry = new long[4];
    private final int[] duration = new int[4];
    private int active = MELEE;
    private long now;

    // SV-2. The last fresh hit a tracked click dealt (a legacy melee click authority delivered): when its click reached
    // the server (0: none, or a write we didn't make owns the window since), the server tick it was dealt in and the
    // window it opened. The counter as the last tracked click left it, at this player's level-tick count then. The click
    // being dealt now: the counter it met, and when it reached the server
    private long hitClickNanos;
    private int hitTick;
    private int hitDuration;
    private int leftCounter;
    private long leftLevelTicks;
    private long levelTicks;
    private int metCounter;
    private long metClickNanos;

    /**
     * Swaps the window for this damage into the victim's fields; returns the channel {@link #exit} restores, {@link #NONE},
     * or a code telling {@link #exit} to settle the clicks' clock.
     */
    public static int enter(final LivingEntity victim, final DamageSource source) {
        final DamageWindowSettings settings = Revival.settings().legacyCombat(victim.level().getWorld().getName()).damageWindows();
        if (!settings.enabled()) {
            return clockIn(victim, source, settings);
        }
        final int channel = channel(source, settings);
        final DamageWindows windows = victim.revivalDamageWindows;
        final int current = windows == null ? MELEE : windows.active;
        if (channel == current) {
            return clockIn(victim, source, settings);
        }
        final DamageWindows slots = windows != null ? windows : (victim.revivalDamageWindows = new DamageWindows());
        slots.switchTo(victim, channel, slots.duration(victim, channel, settings), net.minecraft.server.MinecraftServer.currentTick);
        return current;
    }

    /** Puts back the window {@link #enter} swapped out, or settles the clicks' clock after a click on it. */
    public static void exit(final LivingEntity victim, final int previous) {
        if (previous == CLOCKED) {
            victim.revivalDamageWindows.settle(victim, net.minecraft.server.MinecraftServer.currentTick);
        } else if (previous != NONE) {
            final DamageWindows windows = victim.revivalDamageWindows;
            windows.switchTo(victim, previous, windows.duration[previous], windows.now);
        }
    }

    // A legacy melee click on a player that authority delivered, so it carries when it reached the server; every other
    // damage, and every click outside authority, counts vanilla's ticks (NONE)
    private static int clockIn(final LivingEntity victim, final DamageSource source, final DamageWindowSettings settings) {
        if (!settings.meleeWallClock() || !(victim instanceof ServerPlayer) || !(source.getEntity() instanceof final ServerPlayer attacker)) {
            return NONE;
        }
        final long click = attacker.revivalAuth.clickNanos();
        if (click == 0L || LegacyAttack.settings(source) == null) {
            return NONE;
        }
        final DamageWindows windows = victim.revivalDamageWindows != null ? victim.revivalDamageWindows : (victim.revivalDamageWindows = new DamageWindows());
        windows.catchUp(victim, click, victim.level().tickRateManager().nanosecondsPerTick(),
            windows.hitClickNanos != 0L && Authority.behindSince(windows.hitTick));
        return CLOCKED;
    }

    /**
     * SV-2: before a tracked click's damage, the melee window as the clicks' clock has it. The window counts the
     * victim's level ticks ({@code damageCooldownTime}, one off a tick), and a server stall parts those from the
     * players' time both ways: the clients click on in real time through the stall, and their windows, queued behind
     * it, meet a counter the stall held, so a window real time had opened turns the click away ("server lag eats
     * hits"); then Paper's catch-up ticks run back to back and count a window down in a few milliseconds (a hit dealt
     * in the stall's burst gets its next window early). So when the ticks parted from real time by a stall since the
     * window's hit ({@code behind}, {@code Authority.behindSince}), the counter is set to what is left of that window
     * between the two clicks' arrivals, below or above what the ticks counted. Only when ticks alone made the counter
     * what it is: a plugin's {@code setNoDamageTicks}, or a fresh window from another damage or an untracked hit,
     * stands, and the clock stays out of that window. On schedule nothing changes.
     */
    void catchUp(final LivingEntity victim, final long clickNanos, final long periodNanos, final boolean behind) {
        if (victim.damageCooldownTime != this.ticksAlone()) {
            this.hitClickNanos = 0L;
        }
        victim.damageCooldownTime = this.met(victim, clickNanos, periodNanos, behind);
        this.metCounter = victim.damageCooldownTime;
        this.metClickNanos = clickNanos;
    }

    // After the click's damage: a fresh hit (hurtServer reset the counter to the full window from one the rule let
    // through) starts the clock from its click. Ticks alone take the counter on from here
    void settle(final LivingEntity victim, final int serverTick) {
        final int counter = victim.damageCooldownTime;
        if (counter == victim.invulnerableDuration && !(this.metCounter > victim.invulnerableDuration / 2.0F)) {
            this.hitClickNanos = this.metClickNanos;
            this.hitTick = serverTick;
            this.hitDuration = counter;
        }
        this.leftCounter = counter;
        this.leftLevelTicks = this.levelTicks;
    }

    // The counter a tracked click meets: the clicks' clock's when a stall parted the ticks from real time and the window
    // is the tracked hit's, else the counter as it is
    private int met(final LivingEntity victim, final long clickNanos, final long periodNanos, final boolean behind) {
        if (!behind || this.hitClickNanos == 0L || victim.damageCooldownTime != this.ticksAlone()) {
            return victim.damageCooldownTime;
        }
        // Both arrivals carry the network's jitter and the clicks are whole client ticks apart, so the gap rounds to the
        // client's own spacing while the two together jitter less than half a tick
        final long ticks = Math.floorDiv(clickNanos - this.hitClickNanos + periodNanos / 2L, periodNanos);
        return (int) Math.max(0L, Math.min(this.hitDuration, this.hitDuration - ticks));
    }

    // What ticks alone leave the counter at since the last tracked click: one off a level tick, down to 0
    private int ticksAlone() {
        return (int) Math.max(0L, this.leftCounter - (this.levelTicks - this.leftLevelTicks));
    }

    /**
     * The melee counter a legacy click from a player under authority, reaching the server at {@code clickNanos}, would
     * meet: {@link #catchUp}'s value, changing nothing. The click buffer's landing pass reads it, so a held click lands
     * when its replay will find the window open.
     */
    public static int meleeCounter(final ServerPlayer victim, final long clickNanos) {
        final DamageWindows windows = victim.revivalDamageWindows;
        if (windows == null || windows.hitClickNanos == 0L || windows.active != MELEE
            || !Revival.settings().legacyCombat(victim.level().getWorld().getName()).damageWindows().meleeWallClock()) {
            return victim.damageCooldownTime;
        }
        return windows.met(victim, clickNanos, victim.level().tickRateManager().nanosecondsPerTick(), Authority.behindSince(windows.hitTick));
    }

    /**
     * {@code ServerPlayer.tick}, ahead of its {@code damageCooldownTime--} (from {@code LegacyHitRegistration.tick}): a
     * level tick, which is what takes one off the counter. A count of server ticks would not do: the counter goes down
     * in the level phase, and clicks run both before it (a tick's packets) and after it (the connection phase, between
     * ticks). Counted once the player has windows: the clock tracks nothing before.
     */
    static void levelTick(final ServerPlayer player) {
        final DamageWindows windows = player.revivalDamageWindows;
        if (windows != null) {
            windows.levelTicks++;
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
