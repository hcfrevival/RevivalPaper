package gg.hcfrevival.revival.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * The {@code legacy-combat.damage-windows} section of one world: whether melee, projectile, mob and other damage each
 * get their own damage window, so a sword combo no longer swallows fire ticks, arrows or mob hits (and the reverse).
 *
 * @param enabled split the damage window into channels; off keeps 26.x's single window
 * @param meleeProjectiles projectiles, by entity type id, whose hits share the melee window (1.7 rod and snowball combos)
 * @param projectileTicks the projectile window's length, or {@link #INHERIT} for the entity's own (maximum no-damage ticks)
 * @param mobTicks the mob window's length, or {@link #INHERIT}
 * @param otherTicks the window for every other damage (fire, poison, drowning, fall, explosions...), or {@link #INHERIT}
 */
public record DamageWindowSettings(
    boolean enabled,
    Set<Identifier> meleeProjectiles,
    int projectileTicks,
    int mobTicks,
    int otherTicks
) {

    public static final int INHERIT = -1;

    static final String PATH = LegacyCombatSettings.PATH + "damage-windows.";

    static final List<String> DEFAULT_MELEE_PROJECTILES = List.of("minecraft:fishing_bobber", "minecraft:snowball", "minecraft:egg");

    static DamageWindowSettings read(final ConfigReader reader, final String world) {
        final Set<Identifier> melee = new HashSet<>();
        for (final String type : reader.getWorldStringList(world, PATH + "melee-projectiles", DEFAULT_MELEE_PROJECTILES)) {
            final Identifier id = Identifier.tryParse(type);
            if (id == null) {
                reader.problem(ConfigReader.worldPath(world, PATH + "melee-projectiles"), type, "an entity id such as minecraft:snowball", "skipping it");
            } else {
                melee.add(id);
            }
        }
        return new DamageWindowSettings(
            reader.getWorldBoolean(world, PATH + "enabled", false),
            Set.copyOf(melee),
            ticks(reader, world, "projectile-ticks"),
            ticks(reader, world, "mob-ticks"),
            ticks(reader, world, "other-ticks")
        );
    }

    private static int ticks(final ConfigReader reader, final String world, final String key) {
        final int ticks = reader.getWorldInt(world, PATH + key, INHERIT);
        if (ticks < INHERIT) {
            reader.problem(ConfigReader.worldPath(world, PATH + key), Integer.toString(ticks), "-1 (the entity's own window) or a tick count", "-1");
            return INHERIT;
        }
        return ticks;
    }
}
