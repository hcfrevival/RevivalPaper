package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.KnockbackProfile.ResistanceMode;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

@RevivalTest
class KnockbackProfileTest {

    private static ConfigReader reader(final String yaml) throws InvalidConfigurationException {
        final YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return new ConfigReader(config);
    }

    @Test
    void defaultIsLegacy17AndPresetsStayInCode() throws InvalidConfigurationException {
        final ConfigReader reader = reader("");
        final RevivalSettings settings = RevivalConfig.read(reader);

        assertSame(KnockbackProfile.LEGACY_1_7, settings.legacyCombat().knockback());
        reader.config().options().copyDefaults(true);
        final YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(reader.config().saveToString());
        assertEquals("legacy-1.7", saved.getString("world-settings.default.legacy-combat.knockback-profile"));
        assertTrue(saved.getConfigurationSection("knockback-profiles").getKeys(false).isEmpty());
    }

    @Test
    void emptyProfileEqualsLegacy17() throws InvalidConfigurationException {
        final RevivalSettings settings = RevivalConfig.read(reader("""
            knockback-profiles:
              custom: {}
            world-settings:
              default:
                legacy-combat:
                  knockback-profile: custom
            """));

        assertEquals(KnockbackProfile.LEGACY_1_7, settings.legacyCombat().knockback());
    }

    @Test
    void presetOverrideKeepsTheKeysItDoesNotSet() throws InvalidConfigurationException {
        final RevivalSettings settings = RevivalConfig.read(reader("""
            knockback-profiles:
              kohi:
                horizontal: 0.4
              floaty:
                friction: 3.0
                resistance-mode: scale
            world-settings:
              arena:
                legacy-combat:
                  knockback-profile: kohi
              hub:
                legacy-combat:
                  knockback-profile: floaty
            """));

        final KnockbackProfile kohi = settings.legacyCombat("arena").knockback();
        assertEquals(0.4, kohi.horizontal());
        assertEquals(0.425, kohi.extraHorizontal());
        final KnockbackProfile floaty = settings.legacyCombat("hub").knockback();
        assertEquals(3.0, floaty.friction());
        assertEquals(3.0, floaty.frictionVertical()); // follows friction unless set
        assertEquals(ResistanceMode.SCALE, floaty.resistanceMode());
        assertSame(KnockbackProfile.LEGACY_1_7, settings.legacyCombat().knockback());
    }

    @Test
    void unknownProfileAndBadValuesAreReported() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            knockback-profiles:
              broken:
                horizontal: far
                resistance-mode: sometimes
            world-settings:
              default:
                legacy-combat:
                  knockback-profile: nope
            """);
        final RevivalSettings settings = RevivalConfig.read(reader);

        assertEquals(3, reader.problems().size());
        assertEquals("legacy-1.7", settings.legacyCombat().knockbackProfileName());
        assertSame(KnockbackProfile.LEGACY_1_7, settings.legacyCombat().knockback());
    }
}
