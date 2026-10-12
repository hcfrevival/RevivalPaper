package gg.hcfrevival.revival.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * The {@code legacy-combat} section of one world. A hit is a legacy hit when the attacker's weapon is in
 * {@link #weaponItems} or one of {@link #weaponTags} and has a {@link #weaponDamage} entry; every other hit
 * stays 26.x.
 *
 * @param weaponItems items listed in {@code weapons}
 * @param weaponTags item tags listed in {@code weapons} as {@code #namespace:path}
 * @param weaponDamage total 1.7.10 damage per weapon, the player's base 1 included
 * @param strengthPerLevel Strength multiplies damage by {@code 1 + this × level}
 * @param weaknessPerLevel Weakness subtracts {@code this × level} before Strength applies
 * @param sharpnessPerLevel extra damage per Sharpness level
 * @param criticalMultiplier damage multiplier for a falling hit
 * @param sweepAttack whether a legacy hit may sweep
 */
public record LegacyCombatSettings(
    Set<Identifier> weaponItems,
    List<TagKey<Item>> weaponTags,
    Map<Identifier, Float> weaponDamage,
    double strengthPerLevel,
    double weaknessPerLevel,
    double sharpnessPerLevel,
    double criticalMultiplier,
    boolean sweepAttack
) {

    static final String PATH = "legacy-combat.";

    static final List<String> DEFAULT_WEAPONS = List.of("#minecraft:swords");

    /** 1.7.10 ItemSword: 4 + ToolMaterial damage (0/1/2/3/0), plus the player's base 1. Copper and netherite follow their tier. */
    static final Map<String, Double> DEFAULT_WEAPON_DAMAGE;

    static {
        final Map<String, Double> damage = new LinkedHashMap<>();
        damage.put("wooden_sword", 5.0);
        damage.put("golden_sword", 5.0);
        damage.put("stone_sword", 6.0);
        damage.put("copper_sword", 6.0);
        damage.put("iron_sword", 7.0);
        damage.put("diamond_sword", 8.0);
        damage.put("netherite_sword", 9.0);
        DEFAULT_WEAPON_DAMAGE = Collections.unmodifiableMap(damage); // keeps revival.yml in tier order
    }

    static LegacyCombatSettings read(final ConfigReader reader, final String world) {
        final Set<Identifier> items = new HashSet<>();
        final List<TagKey<Item>> tags = new ArrayList<>();
        final String weaponsPath = ConfigReader.worldPath(world, PATH + "weapons");
        for (final String entry : reader.getWorldStringList(world, PATH + "weapons", DEFAULT_WEAPONS)) {
            final boolean tag = entry.startsWith("#");
            final Identifier id = identifier(reader, weaponsPath, tag ? entry.substring(1) : entry);
            if (id != null) {
                if (tag) {
                    tags.add(TagKey.create(Registries.ITEM, id));
                } else {
                    items.add(id);
                }
            }
        }

        final Map<Identifier, Float> damage = new HashMap<>();
        final String damagePath = ConfigReader.worldPath(world, PATH + "weapon-damage");
        for (final Map.Entry<String, Double> entry : reader.getWorldDoubleMap(world, PATH + "weapon-damage", DEFAULT_WEAPON_DAMAGE).entrySet()) {
            final Identifier id = identifier(reader, damagePath, entry.getKey());
            if (id != null) {
                damage.put(id, entry.getValue().floatValue());
            }
        }

        return new LegacyCombatSettings(
            Set.copyOf(items),
            List.copyOf(tags),
            Map.copyOf(damage),
            reader.getWorldDouble(world, PATH + "strength-multiplier-per-level", 1.3),
            reader.getWorldDouble(world, PATH + "weakness-per-level", 0.5),
            reader.getWorldDouble(world, PATH + "sharpness-per-level", 1.25),
            reader.getWorldDouble(world, PATH + "critical-multiplier", 1.5),
            reader.getWorldBoolean(world, PATH + "sweep-attack", false)
        );
    }

    private static @Nullable Identifier identifier(final ConfigReader reader, final String path, final String value) {
        final Identifier id = Identifier.tryParse(value);
        if (id == null) {
            reader.problem(path, value, "an item id such as minecraft:diamond_sword", "skipping it");
        }
        return id;
    }
}
