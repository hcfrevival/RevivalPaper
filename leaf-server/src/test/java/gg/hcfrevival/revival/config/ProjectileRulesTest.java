package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** The {@code legacy-combat.projectiles} keys of docs/revival/projectiles.md §8. */
@RevivalTest
class ProjectileRulesTest {

    private static ConfigReader reader(final String yaml) throws InvalidConfigurationException {
        final YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return new ConfigReader(config);
    }

    @Test
    void defaultsAre17WithSafePearlsAndAreWrittenBack() throws InvalidConfigurationException {
        final ConfigReader reader = reader("");
        final ProjectileRules rules = RevivalConfig.read(reader).legacyCombat().projectiles().rules();
        assertTrue(rules.legacyThrow());
        assertTrue(rules.legacyFlight());
        assertTrue(rules.legacyHits());
        assertEquals(0.3, rules.hitMargin());
        assertEquals(5, rules.ownerImmuneTicks());
        assertEquals(1.62, rules.throwEyeHeight());
        assertEquals(0.1, rules.throwDrop());
        assertEquals(0.16, rules.throwSide());
        assertEquals(0.0075, rules.throwSpread());
        assertEquals(Set.of(Identifier.withDefaultNamespace("fishing_bobber"), Identifier.withDefaultNamespace("snowball"), Identifier.withDefaultNamespace("egg")),
            rules.lagCompensated());
        assertTrue(rules.pearls().enabled());
        assertTrue(rules.pearls().clientWalls());
        assertTrue(rules.pearls().safeLanding());
        assertEquals(1.5, rules.pearls().maxBacktrack());
        assertEquals(0.0625, rules.pearls().backtrackStep());
        assertFalse(rules.pearls().hasPassThrough());
        assertTrue(rules.pearls().refund());
        assertTrue(rules.splash().enabled());
        assertTrue(rules.splash().legacyFalloff());
        assertTrue(reader.problems().isEmpty());

        reader.config().options().copyDefaults(true);
        final YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(reader.config().saveToString());
        final String path = "world-settings.default.legacy-combat.projectiles.";
        assertEquals("legacy-1.7", saved.getString(path + "throw"));
        assertEquals("legacy-1.7", saved.getString(path + "flight"));
        assertEquals("legacy-1.7", saved.getString(path + "hit-detection"));
        assertEquals(List.of("minecraft:fishing_bobber", "minecraft:snowball", "minecraft:egg"), saved.getStringList(path + "lag-compensation.projectiles"));
        assertEquals("refund", saved.getString(path + "ender-pearl.no-landing"));
        assertEquals("legacy-1.7", saved.getString(path + "splash-potion.falloff"));
    }

    @Test
    void worldsOverrideKeysAndBadValuesFallBack() throws InvalidConfigurationException {
        final ConfigReader reader = reader("""
            world-settings:
              arena:
                legacy-combat:
                  projectiles:
                    throw: vanilla
                    flight: vanilla
                    hit-detection: sideways
                    lag-compensation:
                      projectiles: [minecraft:arrow, "not an id"]
                    ender-pearl:
                      safe-landing: false
                      pass-through: [minecraft:oak_fence_gate, "#minecraft:fence_gates"]
                      no-landing: cancel
                      backtrack-step: 0
                    splash-potion:
                      falloff: vanilla
            """);
        final RevivalSettings settings = RevivalConfig.read(reader);
        final ProjectileRules arena = settings.legacyCombat("arena").projectiles().rules();
        assertFalse(arena.legacyThrow());
        assertFalse(arena.legacyFlight());
        assertTrue(arena.legacyHits()); // "sideways" is reported and falls back
        assertEquals(Set.of(Identifier.withDefaultNamespace("arrow")), arena.lagCompensated());
        assertFalse(arena.pearls().safeLanding());
        assertEquals(Set.of(Identifier.withDefaultNamespace("oak_fence_gate")), arena.pearls().passThroughBlocks());
        assertEquals(List.of(TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("fence_gates"))), arena.pearls().passThroughTags());
        assertFalse(arena.pearls().refund());
        assertEquals(0.0625, arena.pearls().backtrackStep());
        assertFalse(arena.splash().legacyFalloff());
        assertEquals(3, reader.problems().size()); // hit-detection, the bad id, the step
        assertTrue(settings.legacyCombat("lobby").projectiles().rules().legacyThrow());
    }
}
