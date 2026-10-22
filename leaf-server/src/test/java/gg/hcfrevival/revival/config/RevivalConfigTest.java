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
    void version3MirrorHistoryMovesToSixteenTicks() throws Exception {
        // Version 4 (review PT-16): 64 ticks of box history was the old default; an owner's own value stays
        Files.writeString(this.file.toPath(), """
            config-version: 3
            authoritative:
              mirror-history-ticks: 64
            """);
        RevivalConfig.init(this.file);
        assertEquals(AuthoritativeSettings.MIRROR_HISTORY_TICKS, RevivalConfig.settings().authoritative().mirrorHistoryTicks());
        assertEquals(16, AuthoritativeSettings.MIRROR_HISTORY_TICKS);
        final YamlConfiguration saved = YamlConfiguration.loadConfiguration(this.file);
        assertEquals(RevivalConfig.CURRENT_VERSION, saved.getInt("config-version"));
        assertEquals(16, saved.getInt("authoritative.mirror-history-ticks"));

        Files.writeString(this.file.toPath(), """
            config-version: 3
            authoritative:
              mirror-history-ticks: 40
            """);
        RevivalConfig.init(this.file);
        assertEquals(40, RevivalConfig.settings().authoritative().mirrorHistoryTicks(), "an owner's own value stays");
    }

    @Test
    void version4TimerQueueCapMovesTo400() throws Exception {
        // Version 5 (torture F4): the tick clock drains a backlog now, so 1000 ms of queue was the old default
        Files.writeString(this.file.toPath(), """
            config-version: 4
            authoritative:
              timer-queue-cap-ms: 1000
            """);
        RevivalConfig.init(this.file);
        assertEquals(AuthoritativeSettings.TIMER_QUEUE_CAP_MS, RevivalConfig.settings().authoritative().timerQueueCapMs());
        assertEquals(400, AuthoritativeSettings.TIMER_QUEUE_CAP_MS);
        Files.writeString(this.file.toPath(), """
            config-version: 4
            authoritative:
              timer-queue-cap-ms: 700
            """);
        RevivalConfig.init(this.file);
        assertEquals(700, RevivalConfig.settings().authoritative().timerQueueCapMs(), "an owner's own value stays");
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
