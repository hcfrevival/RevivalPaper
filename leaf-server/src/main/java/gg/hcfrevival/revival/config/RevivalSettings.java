package gg.hcfrevival.revival.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An immutable snapshot of revival.yml. A reload builds a new snapshot and swaps it in whole, so code
 * reading settings during a tick never sees half of an old file and half of a new one. {@code /revival kb} edits
 * build a new snapshot the same way.
 *
 * @param configVersion the {@code config-version} the file was written with
 * @param legacyCombat {@code world-settings.default.legacy-combat}
 * @param worldLegacyCombat {@code legacy-combat} for each world that has its own section, by world name
 * @param knockbackProfiles every knockback profile by name: the presets, overridden or joined by {@code knockback-profiles}
 * @param knockbackAutosave whether {@code /revival kb} edits are written to revival.yml at once
 * @param swordBlocking the global {@code sword-blocking} section
 * @param authoritative the global {@code authoritative} section (server-authoritative movement and hits)
 * @param combatRules every combat rule set by name: the presets ({@code classic}, {@code neue}), overridden or joined by {@code combat-rules}
 */
public record RevivalSettings(
    int configVersion,
    LegacyCombatSettings legacyCombat,
    Map<String, LegacyCombatSettings> worldLegacyCombat,
    Map<String, KnockbackProfile> knockbackProfiles,
    boolean knockbackAutosave,
    SwordBlockingSettings swordBlocking,
    AuthoritativeSettings authoritative,
    Map<String, CombatRules> combatRules
) {

    public static final RevivalSettings DEFAULTS = RevivalConfig.read(new ConfigReader(new org.bukkit.configuration.file.YamlConfiguration()));

    /** The legacy-combat settings for a world, by Bukkit world name. */
    public LegacyCombatSettings legacyCombat(final String world) {
        return this.worldLegacyCombat.getOrDefault(world, this.legacyCombat);
    }

    public @Nullable KnockbackProfile knockbackProfile(final String name) {
        return this.knockbackProfiles.get(name);
    }

    public @Nullable CombatRules combatRules(final String name) {
        return this.combatRules.get(name);
    }

    /** A copy where the named profile is added, replaced, or removed (null), and every world using it follows. */
    public RevivalSettings withProfile(final String name, final @Nullable KnockbackProfile profile) {
        final Map<String, KnockbackProfile> profiles = new LinkedHashMap<>(this.knockbackProfiles);
        if (profile == null) {
            profiles.remove(name);
        } else {
            profiles.put(name, profile);
        }
        final Map<String, LegacyCombatSettings> worlds = new HashMap<>();
        this.worldLegacyCombat.forEach((world, settings) -> worlds.put(world, follow(settings, name, profile)));
        return new RevivalSettings(this.configVersion, follow(this.legacyCombat, name, profile), Map.copyOf(worlds), java.util.Collections.unmodifiableMap(profiles), this.knockbackAutosave, this.swordBlocking, this.authoritative, this.combatRules);
    }

    /** A copy where a world, or {@code default}, uses the named profile, which must exist. */
    public RevivalSettings withWorldProfile(final String world, final String name) {
        final KnockbackProfile profile = this.knockbackProfiles.get(name);
        if (profile == null) {
            throw new IllegalArgumentException("no knockback profile named " + name);
        }
        if (ConfigReader.DEFAULT_WORLD.equals(world)) {
            return new RevivalSettings(this.configVersion, this.legacyCombat.withKnockback(name, profile), this.worldLegacyCombat, this.knockbackProfiles, this.knockbackAutosave, this.swordBlocking, this.authoritative, this.combatRules);
        }
        final Map<String, LegacyCombatSettings> worlds = new HashMap<>(this.worldLegacyCombat);
        worlds.put(world, this.legacyCombat(world).withKnockback(name, profile));
        return new RevivalSettings(this.configVersion, this.legacyCombat, Map.copyOf(worlds), this.knockbackProfiles, this.knockbackAutosave, this.swordBlocking, this.authoritative, this.combatRules);
    }

    private static LegacyCombatSettings follow(final LegacyCombatSettings settings, final String name, final @Nullable KnockbackProfile profile) {
        return profile != null && settings.knockbackProfileName().equals(name) ? settings.withKnockback(name, profile) : settings;
    }
}
