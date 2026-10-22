package gg.hcfrevival.revival.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.hcfrevival.revival.RevivalTest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@RevivalTest
class RevivalConfigTest {

    private static final String BROKEN = "config-version: 1\nworld-settings: [\n";

    @TempDir
    Path dir;

    private File file;

    @BeforeEach
    void setUp() {
        this.file = this.dir.resolve("revival.yml").toFile();
    }

    @Test
    void createsMissingFileWithDefaults() throws Exception {
        RevivalConfig.init(this.file);

        assertTrue(this.file.isFile());
        final YamlConfiguration saved = YamlConfiguration.loadConfiguration(this.file);
        assertEquals(RevivalConfig.CURRENT_VERSION, saved.getInt("config-version"));
        assertTrue(saved.isConfigurationSection("world-settings.default"));
        assertTrue(Files.readString(this.file.toPath()).startsWith("# " + RevivalConfig.HEADER.getFirst()));
        assertEquals(RevivalConfig.CURRENT_VERSION, RevivalConfig.settings().configVersion());
    }

    @Test
    void keepsOwnerComments() throws Exception {
        Files.writeString(this.file.toPath(), """
            config-version: 1
            # arena overrides, tuned by staff
            world-settings:
              default: {}
            """);

        RevivalConfig.init(this.file);

        assertTrue(Files.readString(this.file.toPath()).contains("# arena overrides, tuned by staff"));
    }

    @Test
    void oldDefaultsMoveToTheNewOnesAndOwnerValuesStay() throws Exception {
        // Version 3: a file an older jar wrote holds every old default; those move, values the owner chose don't
        Files.writeString(this.file.toPath(), """
            config-version: 2
            authoritative:
              max-rewind-ms: 300
              rtt-ceiling-ms: 280
              external-movers:
                entity-range: 1.0
            """);

        RevivalConfig.init(this.file);

        final AuthoritativeSettings settings = RevivalConfig.settings().authoritative();
        assertEquals(AuthoritativeSettings.MAX_REWIND_MS, settings.maxRewindMs(), "300 was the old default: now a sanity bound of 600");
        assertEquals(280, settings.rttCeilingMs(), "an owner's own value stays");
        assertEquals(AuthoritativeSettings.EXTERNAL_MOVER_ENTITY_RANGE, settings.externalMoverEntityRange());
        final YamlConfiguration saved = YamlConfiguration.loadConfiguration(this.file);
        assertEquals(RevivalConfig.CURRENT_VERSION, saved.getInt("config-version"));
        assertEquals(AuthoritativeSettings.MAX_REWIND_MS, saved.getInt("authoritative.max-rewind-ms"));
    }

    @Test
    void malformedFileStopsStartupAndIsNotOverwritten() throws Exception {
        Files.writeString(this.file.toPath(), BROKEN);

        assertThrows(InvalidConfigurationException.class, () -> RevivalConfig.init(this.file));
        assertEquals(BROKEN, Files.readString(this.file.toPath()));
    }

    @Test
    void failedReloadKeepsLiveSettings() throws Exception {
        RevivalConfig.init(this.file);
        final RevivalSettings live = RevivalConfig.settings();
        Files.writeString(this.file.toPath(), BROKEN);

        final RevivalConfig.Reload result = RevivalConfig.reload();

        assertFalse(result.loaded());
        assertSame(live, RevivalConfig.settings());
        assertEquals(BROKEN, Files.readString(this.file.toPath()));
    }

    @Test
    void reloadReportsValuesWithTheWrongType() throws Exception {
        Files.writeString(this.file.toPath(), "config-version: one\n");
        RevivalConfig.init(this.file);

        final RevivalConfig.Reload result = RevivalConfig.reload();

        assertTrue(result.loaded());
        assertEquals(1, result.problems().size());
        assertEquals(RevivalConfig.CURRENT_VERSION, RevivalConfig.settings().configVersion());
    }

    @Test
    void newerConfigVersionIsNotDowngraded() throws Exception {
        Files.writeString(this.file.toPath(), "config-version: 99\n");

        RevivalConfig.init(this.file);

        assertEquals(99, YamlConfiguration.loadConfiguration(this.file).getInt("config-version"));
        assertEquals(99, RevivalConfig.settings().configVersion());
    }
}
