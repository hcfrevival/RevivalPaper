package gg.hcfrevival.revival.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.DamageWindowSettings;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import org.bukkit.support.RegistryHelper;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@RevivalTest
@RevivalRegistries
class DamageWindowsTest {

    private static final DamageWindowSettings SETTINGS = new DamageWindowSettings(true,
        Set.of(Identifier.withDefaultNamespace("fishing_bobber"), Identifier.withDefaultNamespace("snowball"), Identifier.withDefaultNamespace("egg")),
        DamageWindowSettings.INHERIT, DamageWindowSettings.INHERIT, DamageWindowSettings.INHERIT, true);

    private static DamageSource source(final ResourceKey<DamageType> type, final @Nullable Entity direct, final @Nullable Entity causing) {
        return new DamageSource(RegistryHelper.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type), direct, causing);
    }

    private static <T extends Entity> T entity(final Class<T> type, final EntityType<?> entityType) {
        final T entity = mock(type);
        when(entity.getType()).thenAnswer(invocation -> entityType);
        return entity;
    }

    private static int channel(final ResourceKey<DamageType> type, final @Nullable Entity direct, final @Nullable Entity causing) {
        return DamageWindows.channel(source(type, direct, causing), SETTINGS);
    }

    @Test
    void classifiesEachChannel() {
        final Player player = mock(Player.class);
        final Mob zombie = mock(Mob.class);
        final Arrow arrow = entity(Arrow.class, EntityTypes.ARROW);
        final Snowball snowball = entity(Snowball.class, EntityTypes.SNOWBALL);
        final FishingHook bobber = entity(FishingHook.class, EntityTypes.FISHING_BOBBER);

        assertEquals(DamageWindows.MELEE, channel(DamageTypes.PLAYER_ATTACK, player, player));
        assertEquals(DamageWindows.MELEE, channel(DamageTypes.THROWN, snowball, player)); // 1.7 snowball and rod combos
        assertEquals(DamageWindows.MELEE, channel(DamageTypes.GENERIC, bobber, player));

        assertEquals(DamageWindows.PROJECTILE, channel(DamageTypes.ARROW, arrow, player));
        assertEquals(DamageWindows.PROJECTILE, channel(DamageTypes.ARROW, arrow, zombie)); // a skeleton's arrow
        assertEquals(DamageWindows.PROJECTILE, channel(DamageTypes.ARROW, arrow, null));

        assertEquals(DamageWindows.MOB, channel(DamageTypes.MOB_ATTACK, zombie, zombie));
        assertEquals(DamageWindows.MOB, channel(DamageTypes.MOB_ATTACK_NO_AGGRO, zombie, zombie));
        assertEquals(DamageWindows.MOB, channel(DamageTypes.STING, zombie, zombie));

        assertEquals(DamageWindows.OTHER, channel(DamageTypes.THORNS, zombie, zombie));
        assertEquals(DamageWindows.OTHER, channel(DamageTypes.THORNS, player, player));
        assertEquals(DamageWindows.OTHER, channel(DamageTypes.EXPLOSION, zombie, zombie)); // a creeper
        assertEquals(DamageWindows.OTHER, channel(DamageTypes.INDIRECT_MAGIC, arrow, player)); // splash harming
        for (final ResourceKey<DamageType> type : java.util.List.of(DamageTypes.ON_FIRE, DamageTypes.IN_FIRE, DamageTypes.LAVA, DamageTypes.DROWN,
            DamageTypes.MAGIC, DamageTypes.WITHER, DamageTypes.FALL, DamageTypes.CACTUS, DamageTypes.STARVE, DamageTypes.IN_WALL)) {
            assertEquals(DamageWindows.OTHER, channel(type, null, null), type.identifier().toString());
        }
    }

    @Test
    void meleeProjectilesFollowTheList() {
        final DamageWindowSettings none = new DamageWindowSettings(true, Set.of(), DamageWindowSettings.INHERIT, DamageWindowSettings.INHERIT, DamageWindowSettings.INHERIT,
            true);
        final Snowball snowball = entity(Snowball.class, EntityTypes.SNOWBALL);
        assertEquals(DamageWindows.PROJECTILE, DamageWindows.channel(source(DamageTypes.THROWN, snowball, mock(Player.class)), none));
    }

    // SV-2: the melee window by the clicks' clock. A victim under the real counter rules: a level tick takes one off it, a
    // click meets it, and a click into an open window (1.7: hurtResistantTime 20, fresh again at 10 or less) is a fresh
    // hit, which LivingEntity.hurtServer resets to the full window. Times are click arrivals in milliseconds
    private static final long TICK = 50_000_000L;

    private static ServerPlayer victim() {
        final ServerPlayer victim = mock(ServerPlayer.class);
        victim.invulnerableDuration = 20;
        victim.damageCooldownTime = 0;
        victim.revivalDamageWindows = new DamageWindows();
        return victim;
    }

    // ServerPlayer.tick: LegacyHitRegistration.tick, then the counter's decrement
    private static void levelTicks(final ServerPlayer victim, final int n) {
        for (int i = 0; i < n; i++) {
            DamageWindows.levelTick(victim);
            if (victim.damageCooldownTime > 0) {
                victim.damageCooldownTime--;
            }
        }
    }

    // A tracked click through enter, hurtServer's window rule and exit; true when it was a fresh hit
    private static boolean click(final ServerPlayer victim, final long millis, final boolean behind, final int serverTick) {
        final DamageWindows windows = victim.revivalDamageWindows;
        windows.catchUp(victim, millis * 1_000_000L, TICK, behind);
        final boolean fresh = !(victim.damageCooldownTime > victim.invulnerableDuration / 2.0F);
        if (fresh) {
            victim.damageCooldownTime = victim.invulnerableDuration;
        }
        windows.settle(victim, serverTick);
        return fresh;
    }

    @Test
    void aStallDoesNotKeepTheWindowShut() {
        final ServerPlayer victim = victim();
        assertTrue(click(victim, 1_000L, false, 100));
        // The server stalls after 6 level ticks, and the clicks queued behind it run with the counter at 14. The one 9
        // client ticks after the hit still meets a shut window; the one 10 after meets the opening the counter would deny
        levelTicks(victim, 6);
        assertFalse(click(victim, 1_450L, true, 106));
        assertEquals(11, victim.damageCooldownTime, "what is left by the clicks' clock");
        assertTrue(click(victim, 1_500L, true, 106), "10 client ticks after the hit: fresh");
        assertEquals(20, victim.damageCooldownTime);
    }

    @Test
    void catchUpTicksDoNotOpenItEarly() {
        final ServerPlayer victim = victim();
        // A hit dealt in a stall's burst: its click came in at 1,000 ms, mid-stall. Paper's catch-up ticks then count 4
        // level ticks in a few milliseconds, and 6 more on schedule: 10 counted, the window open by the counter, while
        // the next clicks are 7 and 9 client ticks after the hit
        assertTrue(click(victim, 1_000L, true, 100));
        levelTicks(victim, 10);
        assertFalse(click(victim, 1_350L, true, 110));
        assertEquals(13, victim.damageCooldownTime, "shut by the clicks' clock");
        levelTicks(victim, 2);
        assertFalse(click(victim, 1_450L, true, 112), "the clock's own write is ticks' work: it still rules");
        assertEquals(11, victim.damageCooldownTime);
        levelTicks(victim, 1);
        assertTrue(click(victim, 1_500L, true, 113), "fresh 10 client ticks after the hit");
    }

    @Test
    void jitterRoundsToTheClientsSpacing() {
        final ServerPlayer victim = victim();
        assertTrue(click(victim, 1_000L, true, 100));
        levelTicks(victim, 6);
        // 10 client ticks after the hit, the second click's packets 20 ms quicker than the first's: still the opening
        assertTrue(click(victim, 1_480L, true, 106));
        final ServerPlayer late = victim();
        assertTrue(click(late, 1_000L, true, 100));
        levelTicks(late, 6);
        // 9 client ticks after it, 20 ms slower: still shut
        assertFalse(click(late, 1_470L, true, 106));
        assertEquals(11, late.damageCooldownTime);
    }

    @Test
    void aPluginsWriteWins() {
        // setNoDamageTicks between the hits: the counter isn't what ticks alone leave, so the clock stays out of the window
        final ServerPlayer shorter = victim();
        assertTrue(click(shorter, 1_000L, false, 100));
        levelTicks(shorter, 2);
        shorter.damageCooldownTime = 2;
        assertTrue(click(shorter, 1_100L, true, 102), "the plugin's open window: 2 client ticks after the hit");
        final ServerPlayer longer = victim();
        assertTrue(click(longer, 1_000L, false, 100));
        levelTicks(longer, 2);
        longer.damageCooldownTime = 40;
        levelTicks(longer, 10);
        assertFalse(click(longer, 1_600L, true, 112));
        assertEquals(30, longer.damageCooldownTime, "the plugin's window stands");
        levelTicks(longer, 18);
        assertFalse(click(longer, 2_500L, true, 130), "and the clock stays out of it");
        assertEquals(12, longer.damageCooldownTime);
    }

    @Test
    void anotherFreshWindowWins() {
        // With one window (damage-windows off), a fire tick 3 level ticks after the sword hit opened it again
        final ServerPlayer victim = victim();
        assertTrue(click(victim, 1_000L, false, 100));
        levelTicks(victim, 3);
        victim.damageCooldownTime = 20;
        levelTicks(victim, 7);
        assertFalse(click(victim, 1_500L, true, 110));
        assertEquals(13, victim.damageCooldownTime, "the fire's window, by ticks");
        // So does a fresh hit the clock didn't see (a click outside authority); the next tracked fresh hit starts it again
        levelTicks(victim, 3);
        victim.damageCooldownTime = 20;
        levelTicks(victim, 10);
        assertTrue(click(victim, 1_900L, true, 123));
        levelTicks(victim, 9);
        assertFalse(click(victim, 2_300L, true, 132), "8 client ticks after the tracked hit, 9 level ticks");
        assertEquals(12, victim.damageCooldownTime);
    }

    @Test
    void onlyAFreshHitRestartsTheClock() {
        // An overflow hit inside the window (a stronger weapon) doesn't restart it: the counter isn't reset
        final ServerPlayer victim = victim();
        assertTrue(click(victim, 1_000L, false, 100));
        levelTicks(victim, 4);
        assertFalse(click(victim, 1_200L, false, 104));
        levelTicks(victim, 2);
        assertTrue(click(victim, 1_500L, true, 106), "still measured from the fresh hit at 1,000 ms");
    }

    @Test
    void onScheduleNothingChanges() {
        // A long fight with the server on its schedule: clicks 1 or 2 client ticks apart, each arriving up to 30 ms either
        // side of its tick. The counter, and every hit's freshness, match vanilla's bit for bit
        final java.util.Random random = new java.util.Random(7);
        final ServerPlayer victim = victim();
        int vanilla = 0;
        int tick = 100;
        for (int i = 0; i < 2_000; i++) {
            final int gap = 1 + random.nextInt(2);
            levelTicks(victim, gap);
            vanilla = Math.max(0, vanilla - gap);
            tick += gap;
            final long arrival = tick * 50L + random.nextInt(61) - 30;
            final boolean fresh = click(victim, arrival, false, tick);
            final boolean vanillaFresh = vanilla <= 10;
            if (vanillaFresh) {
                vanilla = 20;
            }
            assertEquals(vanillaFresh, fresh, "click " + i);
            assertEquals(vanilla, victim.damageCooldownTime, "click " + i);
        }
        // And with the clock forced on, arrivals on the tick grid agree with the ticks
        final ServerPlayer gridded = victim();
        vanilla = 0;
        for (int i = 0; i < 2_000; i++) {
            final int gap = 1 + random.nextInt(2);
            levelTicks(gridded, gap);
            vanilla = Math.max(0, vanilla - gap);
            tick += gap;
            final boolean fresh = click(gridded, tick * 50L, true, tick);
            final boolean vanillaFresh = vanilla <= 10;
            if (vanillaFresh) {
                vanilla = 20;
            }
            assertEquals(vanillaFresh, fresh, "gridded click " + i);
            assertEquals(vanilla, gridded.damageCooldownTime, "gridded click " + i);
        }
    }

    @Test
    void channelsKeepTheirOwnWindows() {
        final LivingEntity victim = mock(LivingEntity.class);
        final DamageWindows windows = new DamageWindows();
        // A sword hit at tick 100 opened the real (melee) window
        victim.invulnerableDuration = 18;
        victim.damageCooldownTime = 18;
        victim.lastHurt = 7.0F;

        // A fire tick at 103 sees an empty window
        windows.switchTo(victim, DamageWindows.OTHER, 18, 103);
        assertEquals(0, victim.damageCooldownTime);
        assertEquals(0.0F, victim.lastHurt);
        victim.damageCooldownTime = 18; // hurtServer opens the fire window
        victim.lastHurt = 1.0F;
        windows.switchTo(victim, DamageWindows.MELEE, 18, 103);
        assertEquals(18, victim.damageCooldownTime); // the sword window is untouched
        assertEquals(7.0F, victim.lastHurt);

        // An arrow at 104 with a 10-tick projectile window
        windows.switchTo(victim, DamageWindows.PROJECTILE, 10, 104);
        assertEquals(10, victim.invulnerableDuration);
        assertEquals(0, victim.damageCooldownTime);
        victim.damageCooldownTime = 10;
        victim.lastHurt = 4.0F;
        windows.switchTo(victim, DamageWindows.MELEE, 18, 104);
        assertEquals(18, victim.invulnerableDuration);

        // Each slot runs down with server ticks, without a per-tick decrement
        windows.switchTo(victim, DamageWindows.OTHER, 18, 110);
        assertEquals(11, victim.damageCooldownTime);
        assertEquals(1.0F, victim.lastHurt);
        windows.switchTo(victim, DamageWindows.MELEE, 18, 110);
        windows.switchTo(victim, DamageWindows.PROJECTILE, 10, 120);
        assertEquals(0, victim.damageCooldownTime); // ended at 114
        assertEquals(4.0F, victim.lastHurt);
    }

    @Test
    void nestedDamageRestoresEachWindow() {
        final LivingEntity victim = mock(LivingEntity.class);
        final DamageWindows windows = new DamageWindows();
        victim.invulnerableDuration = 18;
        victim.damageCooldownTime = 12;
        victim.lastHurt = 7.0F;

        windows.switchTo(victim, DamageWindows.OTHER, 18, 50); // fire
        victim.damageCooldownTime = 18;
        victim.lastHurt = 1.0F;
        windows.switchTo(victim, DamageWindows.PROJECTILE, 18, 50); // a plugin damages with an arrow inside the fire event
        victim.damageCooldownTime = 18;
        victim.lastHurt = 5.0F;
        windows.switchTo(victim, DamageWindows.OTHER, 18, 50);
        assertEquals(1.0F, victim.lastHurt);
        windows.switchTo(victim, DamageWindows.MELEE, 18, 50);
        assertEquals(12, victim.damageCooldownTime);
        assertEquals(7.0F, victim.lastHurt);
    }
}
