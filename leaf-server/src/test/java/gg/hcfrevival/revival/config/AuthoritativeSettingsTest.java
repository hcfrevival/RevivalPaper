package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.auth.AuthMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** revival.yml {@code authoritative:} (design §12, §15). */
@RevivalTest
class AuthoritativeSettingsTest {

    @Test
    void defaults() {
        final AuthoritativeSettings settings = AuthoritativeSettings.read(new ConfigReader(new YamlConfiguration()));
        assertEquals(AuthMode.OFF, settings.mode(), "off until the port is proven on a server");
        assertEquals(0.0, settings.rebaseEpsilon(), "§15.5: the port is bit-exact");
        assertEquals(1.0E-9, settings.rebaseDriftCap());
        assertEquals(20, settings.rebaseDriftWindowTicks());
        assertEquals(1.0E-3, settings.correctionThreshold());
        assertEquals(10, settings.maxBankedTicks());
        assertEquals(120, settings.timerDriftMs());
        assertEquals(1000, settings.timerQueueCapMs());
        assertEquals(1500, settings.maxUnackedMs());
        assertEquals(1, settings.maxAttacksPerClientTick());
        assertEquals("deny", settings.vehicles());
        assertFalse(settings.honorDeniedPredictions());
        assertTrue(settings.heartbeatPing());
        assertEquals(2, settings.pathStepTicks());
    }

    @Test
    void badValuesFallBackAndAreReported() {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("authoritative.mode", "sometimes");
        yaml.set("authoritative.rebase-epsilon", 1.0E-3);
        yaml.set("authoritative.vehicles", "boats");
        final ConfigReader reader = new ConfigReader(yaml);
        final AuthoritativeSettings settings = AuthoritativeSettings.read(reader);
        assertEquals(AuthMode.OFF, settings.mode());
        assertEquals(1.0E-10, settings.rebaseEpsilon(), "capped at ulp scale");
        assertEquals("deny", settings.vehicles());
        assertEquals(2, reader.problems().size());
        yaml.set("authoritative.mode", "observe");
        assertEquals(AuthMode.OBSERVE, AuthoritativeSettings.read(new ConfigReader(yaml)).mode());
    }
}
