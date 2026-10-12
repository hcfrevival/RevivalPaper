package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

@RevivalTest
class ConfigReaderTest {

    private static ConfigReader reader(final String yaml) throws InvalidConfigurationException {
        final YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return new ConfigReader(config);
    }

    private static YamlConfiguration saved(final ConfigReader reader) throws InvalidConfigurationException {
        reader.config().options().copyDefaults(true);
        final YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(reader.config().saveToString());
        return saved;
    }

    @Test
    void missingKeyReturnsDefaultAndIsWrittenBack() throws InvalidConfigurationException {
        final ConfigReader reader = reader("");

        assertEquals(1.5, reader.getDouble("combat.damage.critical-multiplier", 1.5));
        assertEquals(1.5, saved(reader).getDouble("combat.damage.critical-multiplier"));
        assertTrue(reader.problems().isEmpty());
    }

    @Test
    void ownerValueWins() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            combat:
              enabled: false
              window: 18
              profile: kohi
            """);

        assertFalse(reader.getBoolean("combat.enabled", true));
        assertEquals(18, reader.getInt("combat.window", 20));
        assertEquals("kohi", reader.getString("combat.profile", "legacy-1.7"));
        assertTrue(reader.problems().isEmpty());
    }

    @Test
    void wrongTypeFallsBackToDefaultAndIsReported() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            combat:
              enabled: maybe
              window: 1.5
              multiplier: fast
            """);

        assertTrue(reader.getBoolean("combat.enabled", true));
        assertEquals(20, reader.getInt("combat.window", 20));
        assertEquals(1.5, reader.getDouble("combat.multiplier", 1.5));
        assertEquals(3, reader.problems().size());
        assertTrue(reader.problems().get(0).contains("combat.enabled"), reader.problems().get(0));
        // The owner's value stays in the file so they can see and fix it
        assertEquals("maybe", saved(reader).getString("combat.enabled"));
    }

    @Test
    void wholeNumberIsAcceptedWhereADecimalIsExpected() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            knockback:
              horizontal: 1
            """);

        assertEquals(1.0, reader.getDouble("knockback.horizontal", 0.4));
        assertTrue(reader.problems().isEmpty());
    }

    @Test
    void numbersAndBooleansReadAsText() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            knockback:
              default-profile: 1.7
            """);

        assertEquals("1.7", reader.getString("knockback.default-profile", "legacy-1.7"));
        assertTrue(reader.problems().isEmpty());
    }

    @Test
    void stringList() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            weapons:
              items:
                - minecraft:stick
                - minecraft:blaze_rod
              exclude: minecraft:wooden_sword
            """);

        assertEquals(List.of("minecraft:stick", "minecraft:blaze_rod"), reader.getStringList("weapons.items", List.of()));
        assertEquals(List.of(), reader.getStringList("weapons.exclude", List.of()));
        assertEquals(List.of("minecraft:swords"), reader.getStringList("weapons.item-tags", List.of("minecraft:swords")));
        assertEquals(1, reader.problems().size());
    }

    @Test
    void worldValueFallsBackToDefaultSectionThenDefault() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            world-settings:
              default:
                combat:
                  enabled: true
              arena:
                combat:
                  enabled: false
            """);

        assertFalse(reader.getWorldBoolean("arena", "combat.enabled", true));
        assertTrue(reader.getWorldBoolean("factions", "combat.enabled", false));
        assertEquals(18, reader.getWorldInt("factions", "cadence.window", 18));
        assertEquals(0.4, reader.getWorldDouble("arena", "knockback.horizontal", 0.4));
        assertEquals("legacy-1.7", reader.getWorldString("arena", "knockback.profile", "legacy-1.7"));

        final YamlConfiguration saved = saved(reader);
        assertEquals(18, saved.getInt("world-settings.default.cadence.window"));
        assertFalse(saved.contains("world-settings.factions"));
        assertFalse(saved.contains("world-settings.arena.cadence"));
    }

    @Test
    void worldValueWithWrongTypeFallsBackToDefaultSection() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            world-settings:
              default:
                cadence:
                  window: 18
              arena:
                cadence:
                  window: soon
            """);

        assertEquals(18, reader.getWorldInt("arena", "cadence.window", 20));
        assertEquals(1, reader.problems().size());
    }
}
