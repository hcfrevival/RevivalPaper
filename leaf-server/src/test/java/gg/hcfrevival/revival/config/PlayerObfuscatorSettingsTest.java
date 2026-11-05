package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

@RevivalTest
class PlayerObfuscatorSettingsTest {

    private static ConfigReader reader(final String yaml) throws InvalidConfigurationException {
        final YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return new ConfigReader(config);
    }

    @Test
    void defaultsAreOffAndWrittenBack() throws InvalidConfigurationException {
        final ConfigReader reader = reader("");
        final PlayerObfuscatorSettings settings = PlayerObfuscatorSettings.read(reader);
        assertEquals(new PlayerObfuscatorSettings(false, 10, 6, 4.0), settings);
        assertTrue(reader.problems().isEmpty());
        reader.config().options().copyDefaults(true);
        final YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(reader.config().saveToString());
        assertFalse(saved.getBoolean("player-obfuscator.enabled"));
        assertEquals(10, saved.getInt("player-obfuscator.hide-delay-ticks"));
        assertEquals(6, saved.getInt("player-obfuscator.lead-ticks"));
        assertEquals(4.0, saved.getDouble("player-obfuscator.camera-distance"));
    }

    @Test
    void badValuesFallBackAndAreReported() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            player-obfuscator:
              enabled: true
              hide-delay-ticks: -3
              lead-ticks: 0
              camera-distance: -1
            """);
        assertEquals(new PlayerObfuscatorSettings(true, 10, 0, 0.0), PlayerObfuscatorSettings.read(reader));
        assertEquals(1, reader.problems().size());
        assertTrue(reader.problems().getFirst().contains("player-obfuscator.hide-delay-ticks"));
    }
}
