package gg.hcfrevival.revival.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.combat.HitValidator;
import gg.hcfrevival.revival.auth.combat.SentTrail;
import gg.hcfrevival.revival.config.AuthoritativeSettings;
import gg.hcfrevival.revival.config.RevivalSettings;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * The non-player block clip's candidates (design §7.1, 2026-10-24): where the attacker's client may have drawn a mob,
 * from its server box now, a tick ago and its tracker's sends over the view's lag.
 */
@RevivalTest
@RevivalRegistries
class NonPlayerSweepTest {

    private static final AuthoritativeSettings SETTINGS = RevivalSettings.DEFAULTS.authoritative();

    // A villager at (x, 64, z) now, at (xo, 64, z) a tick ago
    private static LivingEntity villager(final double x, final double xo, final double z) {
        final LivingEntity villager = mock(Villager.class);
        when(villager.getX()).thenReturn(x);
        when(villager.getY()).thenReturn(64.0);
        when(villager.getZ()).thenReturn(z);
        when(villager.getBoundingBox()).thenReturn(new AABB(x - 0.3, 64.0, z - 0.3, x + 0.3, 65.95, z + 0.3));
        villager.xo = xo;
        villager.yo = 64.0;
        villager.zo = z;
        return villager;
    }

    @Test
    void theBoxNowAndATickAgoAreCandidates() {
        final AABB region = CombatWiring.sweep(villager(0.5, 1.6, 3.3), 1000, 12, 0.1, new HitValidator.Region()).box();
        assertEquals(0.5 - 0.3 - 0.1, region.minX, 1.0E-9);
        assertEquals(1.6 + 0.3 + 0.1, region.maxX, 1.0E-9);
        assertEquals(3.3 - 0.3 - 0.1, region.minZ, 1.0E-9);
        assertEquals(64.0 - 0.1, region.minY, 1.0E-9);
    }

    @Test
    void theTrailCoversTheViewsLag() {
        // It walked +z from z 1.0 and stopped at 3.3 two sends ago: a client up to 12 ticks behind may still show it at 1.0
        final LivingEntity villager = villager(0.5, 0.5, 3.3);
        final SentTrail trail = new SentTrail();
        trail.record(0.5, 64.0, 1.0, 990);
        trail.record(0.5, 64.0, 2.2, 993);
        trail.record(0.5, 64.0, 3.3, 996);
        villager.revivalSentTrail = trail;
        assertEquals(1.0 - 0.3 - 0.1, CombatWiring.sweep(villager, 1000, 12, 0.1, new HitValidator.Region()).box().minZ, 1.0E-9);
        assertEquals(2.2 - 0.3 - 0.1, CombatWiring.sweep(villager, 1000, 5, 0.1, new HitValidator.Region()).box().minZ, 1.0E-9,
            "5 ticks back: the sends at 996 and the one before it, 993");
    }

    @Test
    void theSpanIsTheRoundTripTheMobsOwnDisplayAndTheWaitsAtMostMaxRewind() {
        // 112 ms (the NAS playtest), p90 122: 112 + 10 jitter + 300 (two 3-tick sends) + 150 slack = 572 ms, 12 ticks
        assertEquals(12, CombatWiring.nonPlayerSpanTicks(112, 122, 3, SETTINGS));
        // 20 ms: 20 + 5 + 300 + 150 = 475 ms, 10 ticks
        assertEquals(10, CombatWiring.nonPlayerSpanTicks(20, 25, 3, SETTINGS));
        // 300 ms: the ceiling's 250 + 30 + 300 + 150 = 730, bounded by max-rewind-ms (600): 12 ticks
        assertEquals(12, CombatWiring.nonPlayerSpanTicks(300, 330, 3, SETTINGS));
        assertEquals(12, CombatWiring.nonPlayerSpanTicks(20, 25, Integer.MAX_VALUE, SETTINGS), "an unsynced type: the bound");
    }

    @Test
    void mannequinsKeepVanillasChecks() {
        assertTrue(CombatWiring.clipped(mock(Villager.class)));
        assertFalse(CombatWiring.clipped(mock(Mannequin.class)), "a mannequin stands in for a player (REDLINE's dummies)");
    }
}
