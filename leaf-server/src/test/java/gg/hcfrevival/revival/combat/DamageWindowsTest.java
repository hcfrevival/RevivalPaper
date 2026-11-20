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
        DamageWindowSettings.INHERIT, DamageWindowSettings.INHERIT, DamageWindowSettings.INHERIT, DamageWindowSettings.EnvironmentVelocity.LEGACY);

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
        final DamageWindowSettings none = new DamageWindowSettings(true, Set.of(), DamageWindowSettings.INHERIT, DamageWindowSettings.INHERIT, DamageWindowSettings.INHERIT, DamageWindowSettings.EnvironmentVelocity.LEGACY);
        final Snowball snowball = entity(Snowball.class, EntityTypes.SNOWBALL);
        assertEquals(DamageWindows.PROJECTILE, DamageWindows.channel(source(DamageTypes.THROWN, snowball, mock(Player.class)), none));
    }

    @Test
    void channelsKeepTheirOwnWindows() {
        final LivingEntity victim = mock(LivingEntity.class);
        final DamageWindows windows = new DamageWindows();
        // A sword hit at tick 100 opened the real (melee) window
        victim.invulnerableDuration = 18;
        victim.invulnerableTime = 18;
        victim.lastHurt = 7.0F;

        // A fire tick at 103 sees an empty window
        windows.switchTo(victim, DamageWindows.OTHER, 18, 103);
        assertEquals(0, victim.invulnerableTime);
        assertEquals(0.0F, victim.lastHurt);
        victim.invulnerableTime = 18; // hurtServer opens the fire window
        victim.lastHurt = 1.0F;
        windows.switchTo(victim, DamageWindows.MELEE, 18, 103);
        assertEquals(18, victim.invulnerableTime); // the sword window is untouched
        assertEquals(7.0F, victim.lastHurt);

        // An arrow at 104 with a 10-tick projectile window
        windows.switchTo(victim, DamageWindows.PROJECTILE, 10, 104);
        assertEquals(10, victim.invulnerableDuration);
        assertEquals(0, victim.invulnerableTime);
        victim.invulnerableTime = 10;
        victim.lastHurt = 4.0F;
        windows.switchTo(victim, DamageWindows.MELEE, 18, 104);
        assertEquals(18, victim.invulnerableDuration);

        // Each slot runs down with server ticks, without a per-tick decrement
        windows.switchTo(victim, DamageWindows.OTHER, 18, 110);
        assertEquals(11, victim.invulnerableTime);
        assertEquals(1.0F, victim.lastHurt);
        windows.switchTo(victim, DamageWindows.MELEE, 18, 110);
        windows.switchTo(victim, DamageWindows.PROJECTILE, 10, 120);
        assertEquals(0, victim.invulnerableTime); // ended at 114
        assertEquals(4.0F, victim.lastHurt);
    }

    @Test
    void environmentDamageStaysSilentWhileAnotherWindowIsInItsFirstHalf() {
        final LivingEntity victim = mock(LivingEntity.class);
        final DamageWindows windows = new DamageWindows();
        // A sword hit at tick 100 opened a 20-tick window; 1.7.10 dropped lesser damage until tick 110
        victim.invulnerableDuration = 20;
        victim.invulnerableTime = 17;
        victim.lastHurt = 7.0F;

        windows.switchTo(victim, DamageWindows.OTHER, 20, 103); // a poison tick 3 ticks into the knock
        assertFalse(windows.sendsVelocity(DamageWindowSettings.EnvironmentVelocity.LEGACY));
        assertTrue(windows.sendsVelocity(DamageWindowSettings.EnvironmentVelocity.ALWAYS));
        assertFalse(windows.sendsVelocity(DamageWindowSettings.EnvironmentVelocity.NEVER));
        windows.switchTo(victim, DamageWindows.MELEE, 20, 103);

        victim.invulnerableTime = 11; // the entity ticks its real (melee) window down itself
        windows.switchTo(victim, DamageWindows.OTHER, 20, 109); // 11 ticks left: still the first half
        assertFalse(windows.sendsVelocity(DamageWindowSettings.EnvironmentVelocity.LEGACY));
        windows.switchTo(victim, DamageWindows.MELEE, 20, 109);

        victim.invulnerableTime = 10;
        windows.switchTo(victim, DamageWindows.OTHER, 20, 110); // 10 left: 1.7.10 took this as a fresh hit and sent it
        assertTrue(windows.sendsVelocity(DamageWindowSettings.EnvironmentVelocity.LEGACY));
        assertFalse(windows.sendsVelocity(DamageWindowSettings.EnvironmentVelocity.NEVER));
        windows.switchTo(victim, DamageWindows.MELEE, 20, 110);

        // An arrow's window holds it back too: 1.7.10 had one window for everything
        victim.invulnerableTime = 0;
        windows.switchTo(victim, DamageWindows.PROJECTILE, 20, 130);
        victim.invulnerableTime = 20;
        windows.switchTo(victim, DamageWindows.MELEE, 20, 130);
        windows.switchTo(victim, DamageWindows.OTHER, 20, 132);
        assertFalse(windows.sendsVelocity(DamageWindowSettings.EnvironmentVelocity.LEGACY));
    }

    @Test
    void nestedDamageRestoresEachWindow() {
        final LivingEntity victim = mock(LivingEntity.class);
        final DamageWindows windows = new DamageWindows();
        victim.invulnerableDuration = 18;
        victim.invulnerableTime = 12;
        victim.lastHurt = 7.0F;

        windows.switchTo(victim, DamageWindows.OTHER, 18, 50); // fire
        victim.invulnerableTime = 18;
        victim.lastHurt = 1.0F;
        windows.switchTo(victim, DamageWindows.PROJECTILE, 18, 50); // a plugin damages with an arrow inside the fire event
        victim.invulnerableTime = 18;
        victim.lastHurt = 5.0F;
        windows.switchTo(victim, DamageWindows.OTHER, 18, 50);
        assertEquals(1.0F, victim.lastHurt);
        windows.switchTo(victim, DamageWindows.MELEE, 18, 50);
        assertEquals(12, victim.invulnerableTime);
        assertEquals(7.0F, victim.lastHurt);
    }
}
