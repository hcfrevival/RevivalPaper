package gg.hcfrevival.revival.config;

import gg.hcfrevival.revival.command.RevivalCommand;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Loads revival.yml, the RevivalPaper counterpart of purpur.yml.
 * <p>
 * The server loads it once at startup ({@link #init}); {@code /revival reload} and {@code /reload}
 * re-read it ({@link #reload}). Every load produces a fresh {@link RevivalSettings} snapshot, which
 * replaces the live one only when the whole file loaded.
 */
public final class RevivalConfig {

    public static final int CURRENT_VERSION = 1;

    static final List<String> HEADER = List.of(
        "This is the main configuration file for RevivalPaper.",
        "It controls the 1.7.10 combat layer: legacy weapons, knockback profiles, hit cadence and sprint.",
        "",
        "Values under world-settings.default apply to every world. To change one world, add",
        "world-settings.<world name> with only the keys that differ.",
        "",
        "Knockback presets (legacy-1.7, legacy-1.8, kohi, minehq, badlion, mmc, lunar, velt, hcfr) are built in.",
        "Pick one with legacy-combat.knockback-profile. Under knockback-profiles, a profile named after a preset",
        "overrides only the keys it sets; any other name starts from legacy-1.7. Keys: friction, friction-vertical,",
        "horizontal, vertical, vertical-cap, extra-horizontal, extra-vertical, attacker-slowdown, velocity-clamp,",
        "resistance-mode (none, roll or scale), motion-model (sportbukkit, vanilla-1.7 or none),",
        "ground-prediction-distance (0 = off) and ground-prediction-ping-offset. Preset numbers come from the",
        "server-config archives curated by Mental (github.com/owengregson/Mental, MIT, (c) 2024 Owen Gregson);",
        "their authenticity can't be verified.",
        "",
        "Apply changes with /revival reload. If the file has a mistake, the reload is refused and the",
        "previous settings stay live; the console says what is wrong.",
        "",
        "Docs: https://github.com/hcfrevival/RevivalPaper/tree/ver/26.3/docs/revival"
    );

    private static final Logger LOGGER = LogManager.getLogger("RevivalPaper");

    private static @Nullable File configFile;
    private static volatile @Nullable RevivalSettings settings;

    private RevivalConfig() {
    }

    /** The live settings. Cheap to call on hot paths; hold the result for the length of one action. */
    public static RevivalSettings settings() {
        final RevivalSettings live = settings;
        return live != null ? live : RevivalSettings.DEFAULTS;
    }

    /**
     * Loads revival.yml at startup, creating it if it is missing. A file that cannot be parsed stops the
     * server instead of letting it start with combat settings nobody asked for.
     */
    public static void init(final File file) throws IOException, InvalidConfigurationException {
        configFile = file;
        settings = load(file).settings();
    }

    /** Re-reads revival.yml. When the file cannot be parsed, the live settings stay in place. */
    public static Reload reload() {
        final File file = configFile;
        if (file == null) {
            LOGGER.warn("revival.yml was never loaded, so there is nothing to reload");
            return new Reload(false, List.of());
        }
        try {
            final Loaded loaded = load(file);
            settings = loaded.settings();
            return new Reload(true, loaded.problems());
        } catch (final InvalidConfigurationException e) {
            // The YAML error names the line and column; a stack trace adds nothing for the person fixing the file
            LOGGER.error("Could not reload {}; the previous settings are still live. {}", file, e.getMessage());
            return new Reload(false, List.of());
        } catch (final IOException e) {
            LOGGER.error("Could not reload {}; the previous settings are still live", file, e);
            return new Reload(false, List.of());
        }
    }

    public static void registerCommands() {
        RevivalCommand.registerPermissions();
        MinecraftServer.getServer().server.getCommandMap().register(RevivalCommand.LABEL, "RevivalPaper", new RevivalCommand());
    }

    static Loaded load(final File file) throws IOException, InvalidConfigurationException {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) {
            yaml.load(file); // a parse error throws before anything is saved, so a broken file is never overwritten
        }
        final ConfigReader reader = new ConfigReader(yaml);
        final RevivalSettings loaded = read(reader);
        for (final String problem : reader.problems()) {
            LOGGER.warn(problem);
        }
        yaml.options().setHeader(HEADER);
        yaml.options().copyDefaults(true);
        yaml.save(file);
        return new Loaded(loaded, reader.problems());
    }

    static RevivalSettings read(final ConfigReader reader) {
        final YamlConfiguration yaml = reader.config();
        final int version = reader.getInt("config-version", CURRENT_VERSION);
        if (version > CURRENT_VERSION) {
            LOGGER.warn("revival.yml has config-version {}, but this build only knows up to {}; newer keys are ignored", version, CURRENT_VERSION);
        } else if (version < CURRENT_VERSION) {
            // Migrations from older versions go here, oldest first
            reader.set("config-version", CURRENT_VERSION);
        }
        if (!yaml.isConfigurationSection(ConfigReader.WORLD_SETTINGS + "." + ConfigReader.DEFAULT_WORLD)) {
            yaml.createSection(ConfigReader.WORLD_SETTINGS + "." + ConfigReader.DEFAULT_WORLD);
        }
        final Map<String, KnockbackProfile> profiles = KnockbackProfile.readAll(reader);
        final Map<String, LegacyCombatSettings> worlds = new HashMap<>();
        for (final String world : yaml.getConfigurationSection(ConfigReader.WORLD_SETTINGS).getKeys(false)) {
            if (!ConfigReader.DEFAULT_WORLD.equals(world)) {
                worlds.put(world, LegacyCombatSettings.read(reader, world, profiles));
            }
        }
        return new RevivalSettings(Math.max(version, CURRENT_VERSION), LegacyCombatSettings.read(reader, ConfigReader.DEFAULT_WORLD, profiles), Map.copyOf(worlds));
    }

    /** The outcome of {@link #reload()}: whether the new settings went live, and any values that fell back to defaults. */
    public record Reload(boolean loaded, List<String> problems) {
    }

    record Loaded(RevivalSettings settings, List<String> problems) {
    }
}
