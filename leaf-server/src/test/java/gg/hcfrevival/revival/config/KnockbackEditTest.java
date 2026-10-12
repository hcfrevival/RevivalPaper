package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import gg.hcfrevival.revival.config.KnockbackProfile.MotionModel;
import gg.hcfrevival.revival.config.KnockbackProfile.ResistanceMode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /revival kb} edits: profile keys from text, snapshot edits, and saving them to revival.yml. */
@RevivalTest
class KnockbackEditTest {

    @TempDir
    Path dir;

    private static RevivalSettings read(final String yaml) throws InvalidConfigurationException {
        final YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return RevivalConfig.read(new ConfigReader(config));
    }

    @Test
    void everyKeyCanBeSetFromText() {
        final KnockbackProfile base = KnockbackProfile.LEGACY_1_7;
        assertEquals(List.copyOf(base.values().keySet()), KnockbackProfile.KEYS);
        for (final String key : KnockbackProfile.KEYS) {
            assertEquals(base, base.with(key, String.valueOf(base.values().get(key))), key);
        }
        assertEquals(0.35, base.with("horizontal", "0.35").horizontal());
        assertEquals(ResistanceMode.SCALE, base.with("resistance-mode", "SCALE").resistanceMode());
        assertEquals(MotionModel.VANILLA_1_7, base.with("motion-model", "vanilla-1.7").motionModel());
        assertTrue(base.with("sprint-arm-requires-ground", "true").sprintArmRequiresGround());
    }

    @Test
    void badEditsAreRefused() {
        final KnockbackProfile base = KnockbackProfile.LEGACY_1_7;
        assertThrows(IllegalArgumentException.class, () -> base.with("bounce", "1"));
        assertThrows(IllegalArgumentException.class, () -> base.with("horizontal", "far"));
        assertThrows(IllegalArgumentException.class, () -> base.with("horizontal", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> base.with("sprint-arm-requires-ground", "yes"));
        assertThrows(IllegalArgumentException.class, () -> base.with("motion-model", "rubber-band"));
    }

    @Test
    void editingAProfileReachesEveryWorldUsingIt() throws InvalidConfigurationException {
        final RevivalSettings settings = read("""
            world-settings:
              default:
                legacy-combat:
                  knockback-profile: kohi
              arena:
                legacy-combat:
                  knockback-profile: kohi
              hub:
                legacy-combat:
                  knockback-profile: velt
            """);
        final KnockbackProfile edited = settings.knockbackProfile("kohi").with("horizontal", "0.5");

        final RevivalSettings updated = settings.withProfile("kohi", edited);

        assertSame(edited, updated.legacyCombat().knockback());
        assertSame(edited, updated.legacyCombat("arena").knockback());
        assertSame(KnockbackProfile.PRESETS.get("velt"), updated.legacyCombat("hub").knockback());
        assertSame(KnockbackProfile.PRESETS.get("kohi"), settings.legacyCombat().knockback()); // the old snapshot is untouched
    }

    @Test
    void worldProfilesAndDeletes() throws InvalidConfigurationException {
        final RevivalSettings settings = read("").withProfile("arena-test", KnockbackProfile.LEGACY_1_7.with("friction", "1.5"));

        final RevivalSettings arena = settings.withWorldProfile("arena", "arena-test");
        assertEquals("arena-test", arena.legacyCombat("arena").knockbackProfileName());
        assertEquals(1.5, arena.legacyCombat("arena").knockback().friction());
        assertEquals("legacy-1.7", arena.legacyCombat().knockbackProfileName());
        assertThrows(IllegalArgumentException.class, () -> settings.withWorldProfile("arena", "nope"));

        assertNull(settings.withProfile("arena-test", null).knockbackProfile("arena-test"));
    }

    @Test
    void saveWritesOnlyWhatDiffersAndReadsBackTheSame() throws Exception {
        final RevivalSettings live = read("")
            .withProfile("kohi", KnockbackProfile.PRESETS.get("kohi").with("horizontal", "0.37"))
            .withProfile("arena-test", KnockbackProfile.LEGACY_1_7.with("friction", "1.8"))
            .withWorldProfile("arena", "arena-test");
        final YamlConfiguration yaml = new YamlConfiguration();

        RevivalConfig.writeKnockback(yaml, live);

        assertEquals(java.util.Set.of("kohi", "arena-test"), yaml.getConfigurationSection("knockback-profiles").getKeys(false));
        assertEquals("arena-test", yaml.getString("world-settings.arena.legacy-combat.knockback-profile"));
        final RevivalSettings reread = read(yaml.saveToString());
        assertEquals(live.knockbackProfiles(), reread.knockbackProfiles());
        assertEquals("arena-test", reread.legacyCombat("arena").knockbackProfileName());
    }

    @Test
    void editsStayInMemoryUntilSaved() throws Exception {
        final File file = this.dir.resolve("revival.yml").toFile();
        RevivalConfig.init(file);

        RevivalConfig.update(settings -> settings.withProfile("arena-test", KnockbackProfile.LEGACY_1_7));
        assertTrue(RevivalConfig.settings().knockbackProfiles().containsKey("arena-test"));
        assertFalse(Files.readString(file.toPath()).contains("arena-test"));

        RevivalConfig.saveKnockback();
        assertTrue(Files.readString(file.toPath()).contains("arena-test"));
        assertTrue(RevivalConfig.reload().loaded());
        assertTrue(RevivalConfig.settings().knockbackProfiles().containsKey("arena-test"));
    }

    @Test
    void autosaveWritesEveryEdit() throws Exception {
        final File file = this.dir.resolve("revival.yml").toFile();
        Files.writeString(file.toPath(), "knockback-autosave: true\n");
        RevivalConfig.init(file);

        RevivalConfig.update(settings -> settings.withWorldProfile("default", "kohi"));

        assertEquals("kohi", YamlConfiguration.loadConfiguration(file).getString("world-settings.default.legacy-combat.knockback-profile"));
    }
}
