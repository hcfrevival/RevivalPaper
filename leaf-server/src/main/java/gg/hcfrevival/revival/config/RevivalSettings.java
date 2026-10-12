package gg.hcfrevival.revival.config;

import java.util.Map;

/**
 * An immutable snapshot of revival.yml. A reload builds a new snapshot and swaps it in whole, so code
 * reading settings during a tick never sees half of an old file and half of a new one.
 *
 * @param configVersion the {@code config-version} the file was written with
 * @param legacyCombat {@code world-settings.default.legacy-combat}
 * @param worldLegacyCombat {@code legacy-combat} for each world that has its own section, by world name
 */
public record RevivalSettings(int configVersion, LegacyCombatSettings legacyCombat, Map<String, LegacyCombatSettings> worldLegacyCombat) {

    public static final RevivalSettings DEFAULTS = RevivalConfig.read(new ConfigReader(new org.bukkit.configuration.file.YamlConfiguration()));

    /** The legacy-combat settings for a world, by Bukkit world name. */
    public LegacyCombatSettings legacyCombat(final String world) {
        return this.worldLegacyCombat.getOrDefault(world, this.legacyCombat);
    }
}
