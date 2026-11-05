package gg.hcfrevival.revival.obfuscator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.hcfrevival.revival.RevivalRegistries;
import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.RevivalSettings;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

@RevivalTest
@RevivalRegistries
class PlayerObfuscatorTest {

    @Test
    void intervalFollowsDistanceAndStaysWithinTheLead() {
        assertEquals(2, PlayerObfuscator.interval(10.0 * 10.0, true));
        assertEquals(3, PlayerObfuscator.interval(20.0 * 20.0, true));
        assertEquals(4, PlayerObfuscator.interval(50.0 * 50.0, true));
        assertEquals(8, PlayerObfuscator.interval(100.0 * 100.0, true));
        assertEquals(4, PlayerObfuscator.interval(10.0 * 10.0, false), "clear pairs walk the whole ray, so at most every 4 ticks");
        assertEquals(8, PlayerObfuscator.interval(100.0 * 100.0, false));
        final int lead = RevivalSettings.DEFAULTS.playerObfuscator().leadTicks();
        for (double distance = 1.0; distance <= 64.0; distance += 1.0) {
            assertTrue(PlayerObfuscator.interval(distance * distance, true) <= lead, "an occluded pair within 64 blocks is re-tested inside the lead at " + distance);
        }
    }

    @Test
    void candidatesAreSneakingOrInvisibleAndSeeable() {
        final Player player = mock(Player.class);
        assertFalse(PlayerObfuscator.candidate(player), "standing and visible");
        when(player.isCrouching()).thenReturn(true);
        assertTrue(PlayerObfuscator.candidate(player), "sneaking");
        when(player.isCrouching()).thenReturn(false);
        when(player.isInvisible()).thenReturn(true);
        assertTrue(PlayerObfuscator.candidate(player), "invisible");
        when(player.isSpectator()).thenReturn(true);
        assertFalse(PlayerObfuscator.candidate(player), "spectators are invisible but never sent to survival players anyway");
        when(player.isSpectator()).thenReturn(false);
        when(player.isCurrentlyGlowing()).thenReturn(true);
        assertFalse(PlayerObfuscator.candidate(player), "glowing players are outlined through walls in vanilla");
        when(player.isCurrentlyGlowing()).thenReturn(false);
        when(player.isPassenger()).thenReturn(true);
        assertFalse(PlayerObfuscator.candidate(player), "a withheld rider would leave a riderless mount");
        when(player.isPassenger()).thenReturn(false);
        when(player.isVehicle()).thenReturn(true);
        assertFalse(PlayerObfuscator.candidate(player));
    }

    @Test
    void creativeSpectatorAndPermissionSeeEveryone() {
        assertFalse(PlayerObfuscator.seesEveryone(GameType.SURVIVAL, false));
        assertFalse(PlayerObfuscator.seesEveryone(GameType.ADVENTURE, false));
        assertTrue(PlayerObfuscator.seesEveryone(GameType.CREATIVE, false));
        assertTrue(PlayerObfuscator.seesEveryone(GameType.SPECTATOR, false));
        assertTrue(PlayerObfuscator.seesEveryone(GameType.SURVIVAL, true), "revival.obfuscator.bypass");
    }
}
