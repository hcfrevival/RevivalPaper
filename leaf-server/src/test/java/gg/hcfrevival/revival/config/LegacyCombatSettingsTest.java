package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

@RevivalTest
class LegacyCombatSettingsTest {

    private static ConfigReader reader(final String yaml) throws InvalidConfigurationException {
        final YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return new ConfigReader(config);
    }

    @Test
    void defaultsCoverEverySwordAndAreWrittenBack() throws InvalidConfigurationException {
        final ConfigReader reader = reader("");
        final RevivalSettings settings = RevivalConfig.read(reader);

        assertEquals(List.of(TagKey.create(Registries.ITEM, Identifier.withDefaultNamespace("swords"))), settings.legacyCombat().weaponTags());
        assertEquals(8.0F, settings.legacyCombat().weaponDamage().get(Identifier.withDefaultNamespace("diamond_sword")));
        reader.config().options().copyDefaults(true);
        final YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(reader.config().saveToString());
        assertEquals(List.of("#minecraft:swords"), saved.getStringList("world-settings.default.legacy-combat.weapons"));
        assertEquals(9.0, saved.getDouble("world-settings.default.legacy-combat.weapon-damage.netherite_sword"));
        assertEquals(1.25, saved.getDouble("world-settings.default.legacy-combat.sharpness-per-level"));
        assertTrue(reader.problems().isEmpty());
    }

    @Test
    void worldOverridesSingleKeysAndInheritsTheRest() throws InvalidConfigurationException {
        final RevivalSettings settings = RevivalConfig.read(reader("""
            world-settings:
              default:
                legacy-combat:
                  sharpness-per-level: 1.0
              arena:
                legacy-combat:
                  weapons: [minecraft:diamond_sword, minecraft:mace]
                  weapon-damage:
                    diamond_sword: 10
                    minecraft:mace: 6.5
            """));

        final LegacyCombatSettings arena = settings.legacyCombat("arena");
        assertEquals(Set.of(Identifier.withDefaultNamespace("diamond_sword"), Identifier.withDefaultNamespace("mace")), arena.weaponItems());
        assertTrue(arena.weaponTags().isEmpty());
        assertEquals(10.0F, arena.weaponDamage().get(Identifier.withDefaultNamespace("diamond_sword")));
        assertEquals(6.5F, arena.weaponDamage().get(Identifier.withDefaultNamespace("mace")));
        assertEquals(7.0F, arena.weaponDamage().get(Identifier.withDefaultNamespace("iron_sword")));
        assertEquals(1.0, arena.sharpnessPerLevel());
        assertEquals(1.0, settings.legacyCombat().sharpnessPerLevel());
        assertSame(settings.legacyCombat(), settings.legacyCombat("world_nether"));
    }

    @Test
    void defenceDefaultsToOneSevenTenAndCanBeTurnedOffPerWorld() throws InvalidConfigurationException {
        final RevivalSettings settings = RevivalConfig.read(reader("""
            world-settings:
              arena:
                legacy-combat:
                  legacy-armor: false
                  randomize-protection: false
            """));

        assertTrue(settings.legacyCombat().legacyArmor());
        assertTrue(settings.legacyCombat().legacyEnchantProtection());
        assertTrue(settings.legacyCombat().randomizeProtection());
        assertFalse(settings.legacyCombat("arena").legacyArmor());
        assertTrue(settings.legacyCombat("arena").legacyEnchantProtection());
        assertFalse(settings.legacyCombat("arena").randomizeProtection());
    }

    @Test
    void emptyWeaponListTurnsLegacyCombatOff() throws InvalidConfigurationException {
        final RevivalSettings settings = RevivalConfig.read(reader("""
            world-settings:
              hub:
                legacy-combat:
                  weapons: []
            """));

        assertTrue(settings.legacyCombat("hub").weaponItems().isEmpty());
        assertTrue(settings.legacyCombat("hub").weaponTags().isEmpty());
    }

    @Test
    void badEntriesAreReportedAndSkipped() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            world-settings:
              default:
                legacy-combat:
                  weapons: ['#minecraft:swords', 'Not An Item']
                  weapon-damage:
                    diamond_sword: lots
            """);
        final RevivalSettings settings = RevivalConfig.read(reader);

        assertEquals(2, reader.problems().size());
        assertTrue(settings.legacyCombat().weaponItems().isEmpty());
        assertEquals(8.0F, settings.legacyCombat().weaponDamage().get(Identifier.withDefaultNamespace("diamond_sword")));
    }
}
