package gg.hcfrevival.revival.config;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * The {@code legacy-combat.projectiles} section of one world: which projectiles hit and knock like 1.7.10.
 *
 * @param enabled every projectile toggle at once
 * @param knockbackProfile {@code inherit} for the shooter's melee profile, or a profile name
 * @param knockbackFromShooter knock away from the shooter's position (1.7.10) instead of along the projectile's flight (26.x)
 * @param playerShootersOnly only projectiles a player shot are legacy
 * @param playerVictimsOnly only hits on players are legacy
 * @param zeroDamageHits 0-damage hits (rod, snowball, egg) land on players: knock, flash and open the damage window
 * @param arrows arrows from {@link #arrowLaunchers} are legacy
 * @param arrowLaunchers the weapons whose arrows are legacy, by item id
 * @param punchPerLevel horizontal push per Punch level, along the arrow's flight
 * @param punchVertical upward push from Punch
 * @param snowballs snowballs are legacy
 * @param eggs eggs are legacy
 * @param rods fishing rods are legacy
 * @param rodHitOnImpact the hook deals a 0-damage hit to what it lands on
 * @param rodHookOnlyIfHit the hook only attaches when that hit landed
 * @param rodReelPullsPlayers reeling in a hooked player changes their 1.7 server motion only (no visible pull), instead of 26.x's pull
 * @param rodReelLegacyOther reeling in a hooked mob uses the 1.7.10 pull instead of 26.x's
 * @param rodReelDurability rod durability used by reeling in a hooked entity
 * @param rodCastSpeed the hook's launch speed
 * @param rodGravity the hook's gravity per tick
 */
public record LegacyProjectileSettings(
    boolean enabled,
    String knockbackProfile,
    boolean knockbackFromShooter,
    boolean playerShootersOnly,
    boolean playerVictimsOnly,
    boolean zeroDamageHits,
    boolean arrows,
    Set<Identifier> arrowLaunchers,
    double punchPerLevel,
    double punchVertical,
    boolean snowballs,
    boolean eggs,
    boolean rods,
    boolean rodHitOnImpact,
    boolean rodHookOnlyIfHit,
    boolean rodReelPullsPlayers,
    boolean rodReelLegacyOther,
    int rodReelDurability,
    double rodCastSpeed,
    double rodGravity
) {

    public static final String INHERIT = "inherit";

    static final String PATH = LegacyCombatSettings.PATH + "projectiles.";

    static LegacyProjectileSettings read(final ConfigReader reader, final String world) {
        final Set<Identifier> launchers = new HashSet<>();
        for (final String launcher : reader.getWorldStringList(world, PATH + "arrow.launchers", List.of("minecraft:bow"))) {
            final Identifier id = Identifier.tryParse(launcher);
            if (id == null) {
                reader.problem(ConfigReader.worldPath(world, PATH + "arrow.launchers"), launcher, "an item id such as minecraft:bow", "skipping it");
            } else {
                launchers.add(id);
            }
        }
        return new LegacyProjectileSettings(
            reader.getWorldBoolean(world, PATH + "enabled", true),
            reader.getWorldString(world, PATH + "knockback-profile", INHERIT),
            choice(reader, world, "knockback-origin", "shooter", "projectile"),
            !choice(reader, world, "shooters", "all", "players"),
            !choice(reader, world, "victims", "all", "players"),
            reader.getWorldBoolean(world, PATH + "zero-damage-hits", true),
            reader.getWorldBoolean(world, PATH + "arrow.enabled", true),
            Set.copyOf(launchers),
            reader.getWorldDouble(world, PATH + "arrow.punch-per-level", 0.6),
            reader.getWorldDouble(world, PATH + "arrow.punch-vertical", 0.1),
            reader.getWorldBoolean(world, PATH + "snowball.enabled", true),
            reader.getWorldBoolean(world, PATH + "egg.enabled", true),
            reader.getWorldBoolean(world, PATH + "fishing-rod.enabled", true),
            reader.getWorldBoolean(world, PATH + "fishing-rod.hit-on-impact", true),
            reader.getWorldBoolean(world, PATH + "fishing-rod.hook-only-if-hit", true),
            choice(reader, world, "fishing-rod.reel-pull-players", "shadow", "vanilla"),
            choice(reader, world, "fishing-rod.reel-pull-other", "legacy", "vanilla"),
            reader.getWorldInt(world, PATH + "fishing-rod.reel-durability", 3),
            reader.getWorldDouble(world, PATH + "fishing-rod.cast-speed", 1.5),
            reader.getWorldDouble(world, PATH + "fishing-rod.gravity", 0.04)
        );
    }

    // A two-way option: true for the first (default) value, false for the second
    private static boolean choice(final ConfigReader reader, final String world, final String key, final String first, final String second) {
        final String value = reader.getWorldString(world, PATH + key, first).toLowerCase(Locale.ROOT);
        if (value.equals(first) || value.equals(second)) {
            return value.equals(first);
        }
        reader.problem(ConfigReader.worldPath(world, PATH + key), value, first + " or " + second, first);
        return true;
    }
}
